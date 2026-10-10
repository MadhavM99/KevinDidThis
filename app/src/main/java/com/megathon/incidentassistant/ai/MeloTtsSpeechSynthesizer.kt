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
 * On-device TTS backed by the Qualcomm **VoiceAI TTS** SDK (MeloTTS English).
 *
 * Everything here follows the shipped SDK, not guesswork:
 *   - `melo_sdk/sampleapp/npu_rpc_android_sample/TTSSample/` (README + TTSManager.java)
 *   - `src/npu/rpc/tts_wrapper/src/TTSImpl.cpp` / `TTSModel.cpp`
 *   - `src/npu/rpc/android/java/.../sdk/TTS.java`
 *
 * Two non-obvious things the sources pin down:
 *
 * 1. [TTS.setModelPath] takes a **single file**, not a directory. `TTSModel::loadModel`
 *    does `std::ifstream fp(model_file, std::ios::binary | std::ios::ate)` and reads the
 *    entire blob into rpcmem, handing the fd to the DSP. AI Hub ships the 8 sub-models
 *    separately, so `scripts/build-melo-model.sh` recombines them first.
 *    [TTS.setSkelLibPath] is the one that takes a directory - it is appended to
 *    ADSP_LIBRARY_PATH (see TTS.java:72-82).
 *
 * 2. Audio arrives via `onAudioAvailable(byte[], offset, size)` and must be played by the
 *    caller. The format comes from `onStart(sampleRateInHz, audioFormat, channelCount)`
 *    (parameter names taken from the sample's TTSManager.java), so the AudioTrack is
 *    created there rather than assumed - the model config says 44100 but we don't hardcode.
 *
 * `init()` loads ~312 MB, so it runs once on a worker thread and is kept alive
 * (the guide explicitly advises against calling deInit() between utterances).
 */
class MeloTtsSpeechSynthesizer(context: Context) : SpeechSynthesizer {

    private val appContext = context.applicationContext

    private val workerThread = HandlerThread("melo-tts").apply { start() }
    private val worker = Handler(workerThread.looper)

    @Volatile private var tts: TTS? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var speaking = false

    /** A failed init is not retried: repeated native init attempts can crash the process. */
    @Volatile private var initFailed = false

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
            Log.e(TAG, "no MeloTTS model on device - ${TtsModels.describe(appContext)}")
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
            Log.e(TAG, "no MeloTTS model on device - ${TtsModels.describe(appContext)}")
            return
        }
        speaking = true
        worker.post {
            ensureInit(model)
            tts?.start(text)
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
        t.setSampleRate("44100")
        t.setSkelLibPath(dir)
        t.init()
        // `init()` is void and logs its own error code: the state is the only reliable
        // success signal. A failed init (e.g. DSP crash) leaves IDLE, and calling start()
        // on it would be rejected ("status is not INIT or START").
        if (t.state != TTSNative.TTSEngineState.INIT) {
            Log.e(TAG, "melo tts init failed, state=${t.state}")
            initFailed = true
            return
        }
        tts = t
        Log.i(TAG, "melo tts ready, model=$modelPath")
    }

    // ---------- SDK callbacks ----------

    private val callback = object : TTSResultCallback {

        override fun onStart(sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            Log.d(TAG, "onStart rate=$sampleRateInHz fmt=$audioFormat ch=$channelCount")
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
            t.write(buff, offset, size, AudioTrack.WRITE_BLOCKING)
        }

        override fun onDone() {
            Log.d(TAG, "onDone")
            releaseTrack()
            _utteranceFinished.tryEmit(Unit)
        }

        override fun onError(errorCode: Int) {
            Log.e(TAG, "tts error $errorCode")
            releaseTrack()
            _utteranceFinished.tryEmit(Unit) // don't strand the loop on a failed reply
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

    private companion object {
        const val TAG = "MeloTTS"
        const val AUDIO_ENCODING_LINEAR16 = "0" // see TTS.AUDIO_ENCODING + guide §3.5
    }
}

/** Locates the combined MeloTTS model file on the device. */
object TtsModels {

    private const val TAG = "MeloTTS"
    private const val MODEL_NAME = "melo_en.64_bit.qnn_v2.50.0.qnn"

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
