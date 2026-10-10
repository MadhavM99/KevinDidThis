package com.megathon.incidentassistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device TTS: a Piper (VITS) voice run by sherpa-onnx on the CPU.
 *
 * Why the CPU and not the NPU: the Qualcomm VoiceAI TTS SDK (1.1.1.0, the newest on QPM)
 * only contains the MeloTTS core, and that core cannot run the AI Hub 8 Gen 3 MeloTTS
 * assets (different input order and float tensors; on the QIDK it returns TTS_FAIL and then
 * crashes the app's shared CDSP session, taking Whisper and Gemma with it). Piper on the CPU
 * is faster than real time on the 8 Gen 3 and leaves the NPU to Whisper and Gemma.
 *
 * Audio is streamed: sherpa-onnx hands over each generated chunk (sentence) through
 * `generateWithCallback`, and it is written straight to an AudioTrack, so playback starts
 * after the first sentence instead of the whole reply. [utteranceFinished] fires once the
 * track has actually played the last frame, for the hands-free loop.
 *
 * Model files live in `<external files>/tts/piper/` (see `scripts/fetch-piper-tts.sh`):
 * one `*.onnx` voice, `tokens.txt` and the `espeak-ng-data/` directory.
 */
class PiperSpeechSynthesizer(context: Context) : SpeechSynthesizer {

    private val appContext = context.applicationContext

    private val workerThread = HandlerThread("piper-tts").apply { start() }
    private val worker = Handler(workerThread.looper)

    /** Only touched on [worker]. */
    private var tts: OfflineTts? = null
    private var loadFailed = false

    /** Bumped by [speak] and [stop]: a running generation whose id is stale stops early. */
    private val generation = AtomicInteger(0)
    @Volatile private var track: AudioTrack? = null

    private val _utteranceFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val utteranceFinished: Flow<Unit> = _utteranceFinished

    override suspend fun prepare() {
        val done = CompletableDeferred<Unit>()
        worker.post {
            try {
                ensureLoaded()
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        val id = generation.incrementAndGet()
        worker.post { synthesize(id, text) }
    }

    override fun stop() {
        generation.incrementAndGet()
        // Cut the speaker now; the worker sees the stale id and ends its generation.
        track?.let { t -> runCatching { t.pause(); t.flush() } }
    }

    override fun shutdown() {
        stop()
        worker.post {
            runCatching { tts?.release() }
            tts = null
            workerThread.quitSafely()
        }
    }

    // ---------- worker ----------

    private fun ensureLoaded(): OfflineTts? {
        tts?.let { return it }
        if (loadFailed) return null
        val files = PiperModels.resolve(appContext) ?: run {
            Log.e(TAG, "no Piper voice on device - ${PiperModels.describe(appContext)}")
            loadFailed = true
            return null
        }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = files.model.absolutePath,
                        tokens = files.tokens.absolutePath,
                        dataDir = files.espeakData.absolutePath,
                    ),
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
            )
            OfflineTts(assetManager = null, config = config).also {
                tts = it
                Log.i(
                    TAG,
                    "piper ready: ${files.model.name} ${it.sampleRate()} Hz, " +
                        "load ${SystemClock.elapsedRealtime() - t0} ms",
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "piper load failed: ${e.message}", e)
            loadFailed = true
            null
        }
    }

    private fun synthesize(id: Int, text: String) {
        if (id != generation.get()) return // a newer reply or a stop arrived first
        val engine = ensureLoaded()
        if (engine == null) {
            // No voice: the reply stays on screen and the conversation keeps its cadence.
            _utteranceFinished.tryEmit(Unit)
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val out = try {
            openTrack(engine.sampleRate())
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack failed: ${e.message}")
            _utteranceFinished.tryEmit(Unit)
            return
        }
        track = out
        val sink = ChunkWriter(out, id, t0)
        try {
            engine.generateWithCallback(text, SPEAKER_ID, SPEED, sink)
            val synthMs = SystemClock.elapsedRealtime() - t0
            val audioMs = sink.frames * 1000 / engine.sampleRate()
            Log.i(TAG, "spoke ${text.length} chars: first audio ${sink.firstAudioMs} ms, synth ${synthMs} ms for ${audioMs} ms audio")
            drain(out, sink.frames, id)
        } catch (e: Throwable) {
            Log.e(TAG, "speak failed: ${e.message}", e)
        } finally {
            if (track === out) track = null
            runCatching { out.stop() }
            out.release()
        }
        // Stopped replies don't report "finished": whoever stopped them owns what happens next.
        if (id == generation.get()) _utteranceFinished.tryEmit(Unit)
    }

    /**
     * Receives each generated chunk and plays it; returning 0 stops the generation.
     *
     * A named class on purpose: sherpa-onnx's JNI looks the callback up by the exact
     * signature `invoke([F)Ljava/lang/Integer;`. A Kotlin lambda is desugared by D8 into a
     * synthetic class that only has `invoke(Object)`, and the lookup then aborts the process
     * (NoSuchMethodError inside JNI, seen on the QIDK).
     */
    private inner class ChunkWriter(
        private val out: AudioTrack,
        private val id: Int,
        private val startedAt: Long,
    ) : (FloatArray) -> Int {
        var frames = 0L
        var firstAudioMs = -1L

        override fun invoke(samples: FloatArray): Int {
            if (id != generation.get()) return 0 // stopped or superseded
            if (firstAudioMs < 0) firstAudioMs = SystemClock.elapsedRealtime() - startedAt
            val n = out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (n > 0) frames += n
            return if (id == generation.get()) 1 else 0
        }
    }

    private fun openTrack(sampleRate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRate)
                    .build(),
            )
            .setBufferSizeInBytes(min * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** Waits for the play-head to reach the last written frame (bounded), unless stopped. */
    private fun drain(t: AudioTrack, frames: Long, id: Int) {
        val deadline = SystemClock.elapsedRealtime() + MAX_DRAIN_MS
        while (id == generation.get() && SystemClock.elapsedRealtime() < deadline) {
            val played = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(frames)
            if (played >= frames) break
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    private companion object {
        const val TAG = "PiperTTS"
        /** The big cores; synthesis is several times faster than real time with 4. */
        const val NUM_THREADS = 4
        const val SPEAKER_ID = 0
        const val SPEED = 1.0f
        const val DRAIN_POLL_MS = 20L
        const val MAX_DRAIN_MS = 60_000L
    }
}

/** Locates the side-loaded Piper voice (`<external files>/tts/piper/`). */
object PiperModels {

    class Files(val model: File, val tokens: File, val espeakData: File)

    fun dir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "tts/piper") }

    fun resolve(context: Context): Files? {
        val d = dir(context)?.takeIf { it.isDirectory } ?: return null
        val model = d.listFiles { f -> f.isFile && f.name.endsWith(".onnx") && f.length() > 0 }
            ?.minByOrNull { it.name } ?: return null
        val tokens = File(d, "tokens.txt").takeIf { it.isFile } ?: return null
        val espeak = File(d, "espeak-ng-data").takeIf { it.isDirectory } ?: return null
        return Files(model, tokens, espeak)
    }

    fun describe(context: Context): String {
        val d = dir(context) ?: return "no external files dir"
        if (!d.isDirectory) return "${d.absolutePath} (missing)"
        return "${d.absolutePath} contains [${d.list()?.joinToString(",").orEmpty()}], " +
            "want *.onnx + tokens.txt + espeak-ng-data/"
    }
}
