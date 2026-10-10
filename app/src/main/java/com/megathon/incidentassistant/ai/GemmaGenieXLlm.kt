package com.megathon.incidentassistant.ai

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device **Gemma 4 E2B-it** (Q4_0 GGUF) through Qualcomm **GenieX** on the llama.cpp
 * runtime (`com.qualcomm.qti:geniex-android`). See docs/LLM.md for why this route:
 *
 *  - Qualcomm AI Hub publishes Gemma-4-E2B-it ONLY as a `geniex_llamacpp` / `q4_0` asset
 *    (`google/gemma-4-E2B-it-qat-q4_0-gguf`, file `gemma-4-E2B_q4_0-it.gguf`). There is no
 *    QAIRT/Genie context-binary bundle for it, so the `genie_bundle` + GenieDialog JNI path
 *    of the QIDK AI-Assistant sample cannot load it.
 *  - Gemma runs on the Hexagon HTP (NPU): the GenieX AAR ships the GGML Hexagon backend
 *    with `libggml-htp-v75.so` (SM8650 / 8 Gen 3 = HTP v75). The backend loads that skel
 *    onto the DSP through FastRPC, which only searches a fixed DSP library path list — the
 *    app's native-library dir is not on it, so [stageHtpSkeletons] copies the skel into
 *    the Whisper model dir, which the Whisper SDK registers on that list at init (see
 *    docs/LLM.md "Hexagon skel staging"). "npu" is the only compute unit tried: GPU/CPU
 *    fallbacks only hide NPU failures and add seconds of latency (measured on the QIDK:
 *    ~4 s load on NPU vs ~24 s on the old GPU fallback).
 *
 * API used exactly as in the GenieX 0.7.1 Android sources (`LlmWrapper.kt`,
 * `ModelManagerWrapper.kt`, `GenieXSdk.kt`). The weights are side-loaded with
 * `scripts/push-gemma-model.sh` and imported with `HubSource.LOCALFS` — no network.
 *
 * Lifecycle: loaded once ([prepare]), reused across turns, one generation at a time
 * (the native handle is not documented as thread-safe), conversation state reset before
 * each turn (every prompt carries the full form state). The native handle is kept for the
 * process lifetime ([release] is intentionally a no-op — re-creating it aborts). Logs carry
 * timings and token counts only — never prompts or outputs (incident data).
 */
class GemmaGenieXLlm(
    context: Context,
    private val modelDir: String,
    private val settings: Settings = Settings(),
) : LlmBackend {

    /** Conservative decoding for extraction; see docs/LLM.md "Inference configuration". */
    data class Settings(
        /**
         * Compute units to try, in order. NPU only: Gemma must run on the Hexagon HTP,
         * so a failure to open the HTP session is reported instead of silently falling
         * back to the much slower Adreno/CPU paths. "gpu"/"cpu" remain valid GenieX
         * aliases for callers that explicitly opt into a fallback.
         */
        val computeUnits: List<String> = listOf("npu"),
        /** Prompt (~1-1.5k tokens for the incident form) + output, with headroom. */
        val contextLength: Int = 4096,
        /** A record_fields call for ~6 fields is ~250 tokens. */
        val maxOutputTokens: Int = 320,
        /** topK = 1 → greedy: the same utterance gives the same extraction. */
        val temperature: Float = 0.1f,
        val topK: Int = 1,
        val stopWords: List<String> = listOf("</tool_call>"),
    )

    private val appContext = context.applicationContext

    @Volatile private var computeUnit: String? = null
    override val id: String get() = "$MODEL_ID@geniex-llama_cpp/${computeUnit ?: "unloaded"}"

    private val _status = MutableStateFlow<LlmStatus>(LlmStatus.NotLoaded)
    override val status: StateFlow<LlmStatus> = _status.asStateFlow()

    private val loadLock = Mutex()
    private val runLock = Mutex()
    @Volatile private var wrapper: LlmWrapper? = null

    /** Loads eagerly at start-up so the first utterance does not pay the load. Failure is in [status]. */
    override suspend fun prepare() {
        try {
            ensureLoaded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already reported through status; generate() will retry the load.
        }
    }

    override suspend fun generate(prompt: String): String = runLock.withLock {
        val llm = try {
            ensureLoaded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Gemma not loaded: ${e.javaClass.simpleName}", e)
        }

        // Each turn is self-contained (full form state in the prompt): drop the KV cache /
        // chat history the previous turn left behind.
        llm.reset()

        val templated = llm.applyChatTemplate(
            messages = arrayOf(ChatMessage(role = "user", content = prompt)),
            tools = null,               // app-defined JSON tool protocol, see docs/LLM.md
            enableThinking = false,     // no reasoning tokens: latency + parseable output
        ).getOrElse { throw LlmException("chat template failed", it) }.formattedText

        val config = GenerationConfig(
            maxTokens = settings.maxOutputTokens,
            stopWords = settings.stopWords.toTypedArray(),
            stopCount = settings.stopWords.size,
            samplerConfig = SamplerConfig(temperature = settings.temperature, topK = settings.topK),
        )

        val out = StringBuilder()
        val finished = AtomicBoolean(false)
        val t0 = System.nanoTime()
        Log.i(TAG, "GEMMA_INFERENCE_START unit=$computeUnit")
        try {
            coroutineScope {
                // The native generate() call blocks until done, so cancelling the collector
                // alone would not stop it: stopStream() is what interrupts the decode loop.
                val stopper = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) {
                            withContext(NonCancellable + Dispatchers.IO) { llm.stopStream() }
                        }
                    }
                }
                try {
                    llm.generateStreamFlow(templated, config).collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> out.append(result.text)
                            is LlmStreamResult.Completed -> result.profile.let { p ->
                                Log.d(
                                    TAG,
                                    "gen prompt=${p.promptTokens}tok ttft=${p.ttftMs.toLong()}ms " +
                                        "out=${p.generatedTokens}tok decode=${"%.1f".format(p.decodingSpeed)}tok/s " +
                                        "stop=${p.stopReason}",
                                )
                            }
                            is LlmStreamResult.Error ->
                                throw LlmException("generation failed: ${result.throwable.javaClass.simpleName}", result.throwable)
                        }
                    }
                } finally {
                    finished.set(true)
                    stopper.cancel()
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "CANCELLED gemma after=${(System.nanoTime() - t0) / 1_000_000}ms")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GEMMA_INFERENCE_FAILURE after=${(System.nanoTime() - t0) / 1_000_000}ms", e)
            throw e
        }
        // Generation has returned (Completed/stop word): the native decode loop is done.
        Log.i(TAG, "GEMMA_INFERENCE_SUCCESS took=${(System.nanoTime() - t0) / 1_000_000}ms chars=${out.length}")
        out.toString()
    }

    override fun release() {
        // GenieX native contexts do not survive a destroy() + rebuild cycle in the same
        // process: a second LlmWrapper.build() aborts (SIGABRT in the native dispatch
        // thread, observed on the QIDK when leaving and re-entering the screen). This
        // backend is a singleton in AppContainer and the OS reclaims the process when
        // needed, so the loaded handle is kept for the process lifetime.
    }

    // ---------- loading ----------

    private suspend fun ensureLoaded(): LlmWrapper = loadLock.withLock {
        wrapper ?: load().also { wrapper = it }
    }

    private suspend fun load(): LlmWrapper = withContext(Dispatchers.IO) {
        _status.value = LlmStatus.Loading
        val start = System.nanoTime()
        try {
            stageHtpSkeletons()
            GenieXSdk.getInstance().init(appContext, object : GenieXSdk.InitCallback {
                override fun onSuccess() = Unit
                // e.g. the unused qairt plugin failing to register: llama_cpp may still work.
                override fun onFailure(reason: String) {
                    Log.w(TAG, "GenieX init: $reason")
                }
            })
            val modelPath = importLocalModel()

            var lastError: Throwable? = null
            for (unit in diagnosticComputeUnits() ?: settings.computeUnits) {
                val built = LlmWrapper.builder()
                    .llmCreateInput(
                        LlmCreateInput(
                            model_path = modelPath,
                            config = ModelConfig(nCtx = settings.contextLength),
                            runtime_id = RuntimeIdValue.LLAMA_CPP.value,
                            compute_unit = unit,
                        )
                    )
                    .build()
                val llm = built.getOrNull()
                if (llm != null) {
                    val loadMs = (System.nanoTime() - start) / 1_000_000
                    computeUnit = unit
                    Log.i(TAG, "loaded $MODEL_ID on $unit in ${loadMs}ms")
                    _status.value = LlmStatus.Ready("Gemma 4 E2B · GenieX llama.cpp · ${unit.uppercase()}", loadMs)
                    return@withContext llm
                }
                lastError = built.exceptionOrNull()
                Log.w(TAG, "create on $unit failed: ${lastError?.message}")
            }
            throw LlmException("no compute unit could load the model", lastError)
        } catch (e: CancellationException) {
            _status.value = LlmStatus.NotLoaded
            throw e
        } catch (e: Exception) {
            _status.value = LlmStatus.Failed(e.message ?: e.javaClass.simpleName)
            Log.e(TAG, "load failed", e)
            throw e
        }
    }

    /**
     * Diagnostics only (NPU-contention experiments): a `LLM_COMPUTE_UNIT` file in the model
     * dir holding `gpu` or `cpu` loads Gemma there instead of the NPU, so Whisper/TTS have
     * the HTP to themselves. Absent (the default) → [Settings.computeUnits].
     */
    private fun diagnosticComputeUnits(): List<String>? {
        val unit = runCatching { File(modelDir, "LLM_COMPUTE_UNIT").readText().trim().lowercase() }
            .getOrNull()?.takeIf { it in setOf("npu", "gpu", "cpu") } ?: return null
        Log.w(TAG, "diagnostic override: loading on $unit (LLM_COMPUTE_UNIT)")
        return listOf(unit)
    }

    /**
     * FastRPC resolves the GGML Hexagon skel (`file:///libggml-htp-vXX.so`) from a fixed
     * DSP library search list that does not include this app's native-library dir. The
     * Whisper SDK adds its model dir to that list in `Whisper.init()` (the app always
     * prepares ASR first, see `IncidentViewModel`), so the skels packaged in the APK are
     * copied there before the model is created. Verified on the QIDK (SM8650): without
     * this, HTP session open fails with `0x80000406` and GenieX used the GPU at ~6x the
     * load time; with it, the model loads on `npu` (docs/LLM.md "Hexagon skel staging").
     */
    private fun stageHtpSkeletons() {
        val sourceDir = File(appContext.applicationInfo.nativeLibraryDir)
        val skels = sourceDir.listFiles { f ->
            f.name.startsWith("libggml-htp-v") && f.name.endsWith(".so")
        }.orEmpty()
        if (skels.isEmpty()) {
            Log.w(TAG, "no libggml-htp-v*.so in ${sourceDir.absolutePath}; the NPU load will fail")
            return
        }
        val destDir = WhisperModels.appDir(appContext)?.let(::File) ?: return
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            Log.w(TAG, "cannot create ${destDir.absolutePath}; the NPU load will fail")
            return
        }
        var copied = 0
        for (src in skels) {
            val dst = File(destDir, src.name)
            if (dst.length() == src.length()) continue
            runCatching { src.copyTo(dst, overwrite = true) }
                .onSuccess { copied++ }
                .onFailure { Log.w(TAG, "failed to stage ${src.name}: ${it.message}") }
        }
        Log.i(
            TAG,
            "staged ${skels.size} HTP skel(s) in ${destDir.absolutePath}, copied=$copied",
        )
    }

    /**
     * Registers the side-loaded GGUF with the GenieX model manager (`HubSource.LOCALFS`,
     * no network) as a text-only LLM — the mmproj vision/audio projector is not needed —
     * and returns the path the runtime should open.
     */
    private suspend fun importLocalModel(): String {
        ModelManagerWrapper.getPaths(MANAGER_NAME)?.let { return it.model_path }

        var error: String? = null
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = MANAGER_NAME,
                hub = HubSource.LOCALFS,
                local_path = modelDir,
                model_type = ModelType.LLM,
            )
        ).collect { event ->
            if (event is ModelManagerWrapper.PullEvent.Error) error = "rc=${event.code} ${event.message}"
        }
        error?.let { throw LlmException("model import failed: $it") }
        return ModelManagerWrapper.getPaths(MANAGER_NAME)?.model_path
            ?: throw LlmException("model import produced no paths for $MANAGER_NAME")
    }

    private companion object {
        const val TAG = "GemmaLlm"
        const val MODEL_ID = "gemma-4-E2B-it-q4_0"
        /** Name in the GenieX model manager (local import, not a hub repo). */
        const val MANAGER_NAME = "local/gemma-4-e2b-it-q4_0"
    }
}

/**
 * Locates the side-loaded Gemma GGUF, same convention as [WhisperModels] / [TtsModels]:
 * `<externalFilesDir>/llm/gemma-4-E2B_q4_0-it.gguf` (`scripts/push-gemma-model.sh`).
 */
object GemmaModels {
    /** Exact file name of Qualcomm AI Hub's Gemma-4-E2B-it `geniex_llamacpp` / `q4_0` asset. */
    const val FILE_NAME = "gemma-4-E2B_q4_0-it.gguf"

    fun appDir(context: Context): String? =
        context.getExternalFilesDir(null)?.let { File(it, "llm").absolutePath }

    /** Directory holding the GGUF, or null when it has not been pushed. */
    fun resolve(context: Context): String? {
        val dir = appDir(context) ?: return null
        val file = File(dir, FILE_NAME)
        return if (file.isFile && file.length() > 0L) dir else null
    }
}
