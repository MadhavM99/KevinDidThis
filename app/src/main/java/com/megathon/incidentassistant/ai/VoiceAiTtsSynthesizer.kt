package com.megathon.incidentassistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.qualcomm.qti.voice.assist.tts.sdk.TTS
import com.qualcomm.qti.voice.assist.tts.sdk.TTSResultCallback
import com.qualcomm.tts.TTSNative
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * On-device TTS backed by the Qualcomm **VoiceAI TTS** SDK running **PiperTTS-EN** on the
 * Hexagon NPU.
 *
 * Qualcomm references for Piper on the NPU (not guessed):
 *   - AI Hub model card `PiperTTS-EN` (aihub.qualcomm.com/models/pipertts_en), MIT,
 *     `real-time`, "Qualcomm Voice AI" runtime — pre-exported asset on HF
 *     `qualcomm/PiperTTS-EN`. PiperTTS-DE / PiperTTS-IT exist the same way.
 *   - `qai-appbuilder` sample `pipertts_en`: "runs fully on the Snapdragon NPU (HTP)".
 *   - Qualcomm Audio Analytics container docs: "Whisper, OpusMT, MeloTTS and PiperTTS
 *     models all run on the Qualcomm NPU" — `piper-tts-en` @ 22050 Hz output.
 *
 * The Voice AI TTS SDK is model-agnostic: the same `tts-sdk.jar` + `TTS` API the QIDK
 * ASR-LLM-TTS sample uses for MeloTTS serves the combined Piper `.qnn` blob, so switching
 * models changes nothing here but the file. `scripts/build-piper-model.sh` recombines the
 * AI Hub piper sub-models (T5 text encoder/decoder, VITS encoder/flow/decoder, sdp) into
 * the single file [TTS.setModelPath] requires; `scripts/push-tts-model.sh` pushes it.
 *
 * Two non-obvious things the sources pin down (same SDK as the QIDK sample):
 *
 * 1. [TTS.setModelPath] takes a **single file**, not a directory. `TTSModel::loadModel`
 *    reads the entire blob into rpcmem and hands the fd to the DSP, so the sub-models must
 *    be recombined first. [TTS.setSkelLibPath] takes a directory - it is appended to
 *    ADSP_LIBRARY_PATH (see TTS.java:72-82).
 *
 * 2. Audio arrives via `onAudioAvailable(byte[], offset, size)` and must be played by the
 *    caller. The format comes from `onStart(sampleRateInHz, audioFormat, channelCount)`,
 *    so the AudioTrack is created there rather than assumed (Piper produces 22050 Hz).
 *
 * Lifecycle: `init()` runs once on a worker thread at start-up (it matters for
 * correctness too: the SDK asks FastRPC for a 128 KB DSP thread stack, which must happen
 * before Whisper/Gemma bring up the process threads) and is kept alive; the guide
 * explicitly advises against calling deInit() between utterances. TTS is complementary
 * output: every failure path keeps the hands-free conversation loop alive via
 * [utteranceFinished] and never crashes the app.
 */
class VoiceAiTtsSynthesizer(context: Context) : SpeechSynthesizer {

    private val appContext = context.applicationContext

    private val workerThread = HandlerThread("piper-tts").apply { start() }
    private val worker = Handler(workerThread.looper)

    @Volatile private var tts: TTS? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var speaking = false

    /** A failed init is not retried: repeated native init attempts can crash the process. */
    @Volatile private var initFailed = false

    /** Frames (not bytes) written to the current track, for the drain-before-release wait. */
    @Volatile private var framesWritten = 0L
    @Volatile private var bytesPerFrame = 2

    /** Emits when a reply finished playing, for the hands-free loop. */
    private val _utteranceFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val utteranceFinished: Flow<Unit> = _utteranceFinished

    private val modelFile: String? = TtsModels.resolve(appContext)

    /**
     * Loads the engine once at start-up, like the other backends. This also matters for
     * correctness: the SDK asks FastRPC for a 128 KB DSP thread stack in `init()`, which
     * must happen before any other FastRPC user (Whisper/Gemma) brings the process up with
     * the default small stack — otherwise `tts_get`'s 40 KB local overflows it at init.
     */
    override suspend fun prepare() {
        val model = modelFile ?: run {
            Log.e(TAG, "no Piper TTS model on device - ${TtsModels.describe(appContext)}")
            return
        }
        val done = CompletableDeferred<Unit>()
        worker.post {
            try {
                ensureInit(model)
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        val model = modelFile
        if (model == null) {
            Log.e(TAG, "no Piper TTS model on device - ${TtsModels.describe(appContext)}")
            _utteranceFinished.tryEmit(Unit) // keep the hands-free loop alive, text-only
            return
        }
        speaking = true
        worker.post {
            try {
                ensureInit(model)
                val engine = tts
                if (engine == null) {
                    // Init failed earlier (e.g. DSP crash): the reply stays text-only, but
                    // the conversation keeps its cadence — the hands-free loop proceeds.
                    speaking = false
                    Log.w(TAG, "TTS engine unavailable - reply stays text-only")
                    _utteranceFinished.tryEmit(Unit)
                    return@post
                }
                engine.start(text)
            } catch (e: Throwable) {
                // TTS is complementary: an engine failure must never crash the flow.
                speaking = false
                Log.e(TAG, "speak failed: ${e.message}")
                _utteranceFinished.tryEmit(Unit) // keep the hands-free loop alive
            }
        }
    }

    override fun stop() {
        speaking = false
        worker.post {
            runCatching { tts?.stop() }
            releaseTrack()
        }
    }

    override fun shutdown() {
        speaking = false
        worker.post {
            runCatching { tts?.stop() }
            releaseTrack()
            runCatching { tts?.deInit() }   // frees DSP resources; model reload is expensive
            tts = null
            workerThread.quitSafely()
        }
    }

    // ---------- lifecycle ----------

    private fun ensureInit(modelPath: String) {
        if (tts != null || initFailed) return
        val dir = TtsModels.dirOf(modelPath)
        val t = TTS.newInstance(callback)
        t.setLanguage("English")
        t.setModelPath(modelPath)
        t.setAudioEncoding(AUDIO_ENCODING_LINEAR16)
        t.setSpeechRate("1.0")
        t.setPitch("0.0")
        t.setVolumeGain("0.0")
        t.setSampleRate(PIPER_SAMPLE_RATE)
        t.setSkelLibPath(dir)
        t.init()
        // `init()` is void and logs its own error code: the state is the only reliable
        // success signal. A failed init (e.g. DSP crash) leaves IDLE, and calling start()
        // on it would be rejected ("status is not INIT or START").
        if (t.state != TTSNative.TTSEngineState.INIT) {
            Log.e(TAG, "piper tts init failed, state=${t.state}")
            initFailed = true
            return
        }
        tts = t
        Log.i(TAG, "piper tts ready, model=$modelPath")
    }

    // ---------- SDK callbacks ----------

    private val callback = object : TTSResultCallback {

        override fun onStart(sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            Log.d(TAG, "onStart rate=$sampleRateInHz fmt=$audioFormat ch=$channelCount")
            framesWritten = 0L
            bytesPerFrame = 2 * maxOf(1, channelCount) // 16-bit PCM
            val channelMask = if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO
                              else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRateInHz, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Log.e(TAG, "AudioTrack unsupported at $sampleRateInHz Hz")
                return
            }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .setSampleRate(sampleRateInHz)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }

        override fun onAudioAvailable(buff: ByteArray, offset: Int, size: Int) {
            if (!speaking) return
            val t = track ?: return
            val written = t.write(buff, offset, size, AudioTrack.WRITE_BLOCKING)
            if (written > 0) framesWritten += written / bytesPerFrame
        }

        override fun onDone() {
            Log.d(TAG, "onDone")
            // Don't release here: the NPU synthesises much faster than real time, so most
            // of the PCM is still buffered. Wait for playback, then release.
            val t = track
            val frames = framesWritten
            worker.post {
                drainAndRelease(t, frames)
                if (speaking) _utteranceFinished.tryEmit(Unit)
            }
        }

        override fun onError(errorCode: Int) {
            Log.e(TAG, "tts error $errorCode")
            worker.post {
                releaseTrack()
                _utteranceFinished.tryEmit(Unit) // don't strand the loop on a failed reply
            }
        }

        override fun onPerformanceResult(
            processingTimeMs: Int, audioLengthInMs: Int, realTimeFactor: Float
        ) {
            Log.d(
                TAG, "perf ${processingTimeMs}ms -> ${audioLengthInMs}ms audio, rtf=$realTimeFactor"
            )
        }
    }

    private fun releaseTrack() {
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
    }

    private fun releaseTrack(t: AudioTrack?) {
        runCatching {
            t?.stop()
            t?.release()
        }
        if (track === t) track = null
    }

    /**
     * Waits for the AudioTrack play-head to reach the last written frame, then releases.
     * `speaking` is cleared by [stop], so a user interrupt ends the wait promptly.
     */
    private fun drainAndRelease(t: AudioTrack?, frames: Long) {
        if (t == null) return
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + MAX_DRAIN_MS
        while (speaking && System.currentTimeMillis() < deadline) {
            val played = runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(frames)
            if (played >= frames) break
            Thread.sleep(DRAIN_POLL_MS)
        }
        releaseTrack(t)
        Log.d(TAG, "playback drained in ${System.currentTimeMillis() - startedAt}ms ($frames frames)")
    }

    private companion object {
        const val TAG = "PiperTTS"
        const val AUDIO_ENCODING_LINEAR16 = "0" // see TTS.AUDIO_ENCODING + guide §3.5
        const val DRAIN_POLL_MS = 20L
        const val MAX_DRAIN_MS = 60_000L // safety cap; real replies are a few seconds
        /** Piper output rate per the Qualcomm Audio Analytics container reference. */
        const val PIPER_SAMPLE_RATE = "22050"
    }
}

/** Locates the combined Piper TTS model on the device (built by `scripts/build-piper-model.sh`). */
object TtsModels {

    private const val TAG = "PiperTTS"
    private const val MODEL_NAME = "piper_en.64_bit.qnn_v2.50.0.qnn"

    fun dirOf(modelFile: String): String = modelFile.substringBeforeLast('/')

    fun appDir(context: Context): String? =
        context.getExternalFilesDir(null)?.let { "${it.absolutePath}/tts" }

    fun resolve(context: Context): String? {
        val dir = appDir(context) ?: return null
        val f = java.io.File(dir, MODEL_NAME)
        return if (f.isFile && f.length() > 0L) f.absolutePath else null
    }

    fun describe(context: Context): String {
        val dir = appDir(context) ?: return "no external files dir"
        val d = java.io.File(dir)
        if (!d.isDirectory) return "$dir (missing)"
        val have = d.list()?.joinToString(",") ?: ""
        return "$dir contains [$have], want $MODEL_NAME"
    }
}