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
 *  - The GenieX AAR ships the GGML Hexagon backend with `libggml-htp-v75.so` (SM8650 /
 *    8 Gen 3 = HTP v75) plus OpenCL (Adreno) and CPU backends. Qualcomm validates GenieX
 *    on SM8750/SM8850 only, so 8 Gen 3 is "expected to work", not "validated": the
 *    backend tries NPU → GPU → CPU and reports which one actually loaded.
 *
 * API used exactly as in the GenieX 0.7.1 Android sources (`LlmWrapper.kt`,
 * `ModelManagerWrapper.kt`, `GenieXSdk.kt`). The weights are side-loaded with
 * `scripts/push-gemma-model.sh` and imported with `HubSource.LOCALFS` — no network.
 *
 * Lifecycle: loaded once ([prepare]), reused across turns, one generation at a time
 * (the native handle is not documented as thread-safe), conversation state reset before
 * each turn (every prompt carries the full form state), [release] frees the handle.
 * Logs carry timings and token counts only — never prompts or outputs (incident data).
 */
class GemmaGenieXLlm(
    context: Context,
    private val modelDir: String,
    private val settings: Settings = Settings(),
) : LlmBackend {

    /** Conservative decoding for extraction; see docs/LLM.md "Inference configuration". */
    data class Settings(
        /** Tried in order until one loads. GenieX aliases: "npu", "gpu", "cpu". */
        val computeUnits: List<String> = listOf("npu", "gpu", "cpu"),
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
        out.toString()
    }

    override fun release() {
        // Not under runLock: release is called from teardown; an in-flight generate is
        // cancelled by its owner first. destroy() is idempotent in the SDK.
        wrapper?.let { runCatching { it.destroy() } }
        wrapper = null
        computeUnit = null
        _status.value = LlmStatus.NotLoaded
    }

    // ---------- loading ----------

    private suspend fun ensureLoaded(): LlmWrapper = loadLock.withLock {
        wrapper ?: load().also { wrapper = it }
    }

    private suspend fun load(): LlmWrapper = withContext(Dispatchers.IO) {
        _status.value = LlmStatus.Loading
        val start = System.nanoTime()
        try {
            GenieXSdk.getInstance().init(appContext, object : GenieXSdk.InitCallback {
                override fun onSuccess() = Unit
                // e.g. the unused qairt plugin failing to register: llama_cpp may still work.
                override fun onFailure(reason: String) {
                    Log.w(TAG, "GenieX init: $reason")
                }
            })
            val modelPath = importLocalModel()

            var lastError: Throwable? = null
            for (unit in settings.computeUnits) {
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
