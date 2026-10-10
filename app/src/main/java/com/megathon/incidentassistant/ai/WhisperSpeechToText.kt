package com.megathon.incidentassistant.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import com.qualcomm.qti.voice.assist.whisper.sdk.Whisper
import com.qualcomm.qti.voice.assist.whisper.sdk.WhisperResponseListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Real on-device ASR backed by the Qualcomm **Whisper SDK** (Qualcomm VoiceAI ASR, runtime
 * "Qualcomm(R) Voice AI", model whisper_small on AI Hub).
 *
 * Contract taken from the shipped docs, not guessed:
 *   - `80-15314-12_AE_Whisper_SDK_for_Android_Developer_Guide.pdf` (Rev. AE, June 2026)
 *   - `whisper-sdk.jar` (javap) and `src/.../Whisper.java` / `TranscriptionManager.cpp`
 *
 * Things the docs pin down that drive this implementation:
 *   - Audio MUST be 16 kHz, mono, PCM 16-bit (guide section 2.5). The SDK pulls 1280-byte
 *     (40 ms) frames from the InputStream we hand it.
 *   - `Whisper.init(path)` needs a directory holding `vocab.bin`, `encoder_model_htp.bin`,
 *     `decoder_model_htp.bin` (and `libQnnHtpVxxSkel.so` on the DSP side).
 *   - Partial transcriptions are OFF by default; `setPartialBufferMs` defaults to 3000 ms.
 *   - In non-continuous mode (the default) a final transcription is produced only when the
 *     VAD detects end-of-speech; then the SDK stops itself and fires `onFinished()`.
 *     Calling `whisper.stop()` directly on a live stream discards the un-flushed buffer
 *     (see TranscriptionManager::runnable(): only the audio-file branch processes the
 *     remainder). So [stopListening] feeds silence until VAD closes the utterance rather
 *     than stopping cold, otherwise the tail of the sentence is lost.
 */
class WhisperSpeechToText(context: Context) : SpeechToText {

    private val appContext = context.applicationContext

    @Volatile private var whisper: Whisper? = null
    private var ready = false

    /**
     * Session lifecycle (start → finish/error → stop → next start) lives in
     * [AsrSessionController] so its ordering is unit-tested on the host. In short: the SDK
     * session is closed with stop() before the next start() — never from inside an SDK
     * callback — and an SDK error ends the utterance instead of leaving "listening" open.
     */
    private val sessions = AsrSessionController(
        engine = object : AsrSessionController.Engine {
            override fun openInput(): AsrSessionController.Input = MicInputStream().also { it.start() }

            override suspend fun start(input: AsrSessionController.Input) {
                val w = whisper ?: error("Whisper SDK not initialised (model files missing?)")
                withContext(Dispatchers.Main) { w.start(input as MicInputStream) }
            }

            override fun stop() {
                whisper?.stop()
            }
        },
        noSpeechErrorCode = WhisperResponseListener.NO_SPEECH_TIMEOUT,
        flushTimeoutMs = FLUSH_TIMEOUT_MS,
        forceStopTimeoutMs = FORCE_STOP_TIMEOUT_MS,
        log = { Log.i(TAG, it) },
    )

    override val partialTranscript: StateFlow<String> = sessions.partialTranscript

    /** Emitted when the SDK's VAD closed the utterance (single-shot auto-stop) or it failed. */
    override val utteranceFinished: Flow<Unit> = sessions.utteranceFinished

    /** Serialises [prepare]; the native engine must be created exactly once per process. */
    private val initMutex = Mutex()
    private var initDone = false

    // ---------- SpeechToText ----------

    override suspend fun prepare() {
        initMutex.withLock {
            if (initDone) return@withLock
            initDone = true
            withContext(Dispatchers.Default) {
                val modelDir = resolveModelDir()
                val w = Whisper.newInstance()
                w.setListener(listener)
                w.enablePartialTranscriptions(true)
                w.enableContinuousTranscription(false) // one utterance per tap, as the UI expects
                w.showProgressIndicators(false)
                w.setNoStartSpeechTimeout(NO_SPEECH_TIMEOUT_MS)
                w.setEndOfSpeechTimeoutMs(END_OF_SPEECH_TIMEOUT_MS)
                w.setPartialBufferMs(PARTIAL_BUFFER_MS)
                w.setAILogLevel(Whisper.LOG_LEVEL_WARN)

                // Field debugging: touch DEBUG_AUDIO in the model dir to dump every captured
                // utterance as a WAV (SDK feature; see docs/ASR.md).
                val debugDir = File(modelDir, "debug_audio")
                if (File(modelDir, "DEBUG_AUDIO").exists()) {
                    debugDir.mkdirs()
                    w.setDebugDir(debugDir)
                    w.enableDebug(true)
                    Log.i(TAG, "audio debug dump enabled -> $debugDir")
                }

                val rc = w.init(modelDir)
                if (rc == Whisper.NO_ERROR) {
                    whisper = w
                    ready = true
                    Log.i(TAG, "whisper ready, modelDir=$modelDir version=${w.version}")
                } else {
                    Log.e(TAG, "whisper.init failed rc=$rc modelDir=$modelDir")
                    w.deInit()
                }
            }
        }
    }

    override suspend fun startListening() {
        if (whisper == null) error("Whisper SDK not initialised (model files missing?)")
        sessions.start()
    }

    /**
     * Ends the utterance without losing its tail: calling `whisper.stop()` on a live stream
     * discards the un-flushed buffer, so the mic is swapped for silence and the SDK's own
     * end-of-speech → flush → onFinished is awaited first (see [AsrSessionController]).
     */
    override suspend fun stopListening(): String {
        if (whisper == null) throw IllegalStateException("ASR unavailable")
        return sessions.stop()
    }

    override fun release() {
        // The Whisper SDK's native engine is process-scoped: a new init after deInit() is
        // observed to SIGSEGV inside WhisperLib::init (Android 14 / QIDK tombstone, same
        // process re-entering the screen). This backend is a singleton in [AppContainer],
        // so keep the initialised engine and only stop the microphone stream.
        sessions.abandon()
    }

    /** True when the SDK initialised successfully with real model files on disk. */
    val isReady: Boolean get() = ready

    // ---------- SDK callbacks ----------

    private val listener = object : WhisperResponseListener {
        override fun onTranscription(
            transcription: String,
            language: String,
            isFinal: Boolean,
            code: Int,
            extras: Bundle?,
        ) {
            if (code != VALID_TRANSCRIPTION && code != PROGRESS_INDICATOR) return
            sessions.onTranscription(transcription, isFinal)
        }

        override fun onError(errorCode: Int, ex: Exception?) {
            Log.e(TAG, "whisper error $errorCode: ${ex?.message}", ex)
            sessions.onError(errorCode)
        }

        override fun onRecordingStopped() {
            Log.d(TAG, "onRecordingStopped")
        }

        override fun onSpeechStart() = Unit

        override fun onSpeechEnd() = Unit

        /**
         * All audio processed and the SDK has stopped itself. The matching `stop()` (QIDK
         * sample parity) is issued by [stopListening], ordered before any next start().
         */
        override fun onFinished() {
            sessions.onFinished()
        }
    }

    // ---------- helpers ----------

    /**
     * Locates the model directory: the app-specific external dir first (no root needed,
     * see `scripts/push-whisper-models.sh`), then the sample app's /vendor/etc/whisper.
     * When nothing is found, a path is returned anyway so `Whisper.init` reports the
     * specific failure instead of a null-dereference.
     */
    private fun resolveModelDir(): String =
        WhisperModels.resolve(appContext)
            ?: (WhisperModels.appDir(appContext) ?: WhisperModels.VENDOR_DIR).also { dir ->
                Log.e(TAG, "model files not found - ${WhisperModels.describe(dir)}")
            }

    private companion object {
        const val TAG = "WhisperSTT"
        const val NO_SPEECH_TIMEOUT_MS = 15_000
        const val END_OF_SPEECH_TIMEOUT_MS = 1_200 // SDK default
        const val PARTIAL_BUFFER_MS = 1_000
        const val FLUSH_TIMEOUT_MS = 4_000L
        const val FORCE_STOP_TIMEOUT_MS = 2_000L

        const val VALID_TRANSCRIPTION = 0
        const val PROGRESS_INDICATOR = 3
    }
}

/** Thrown when the user stopped recording without saying anything. */
class NoSpeechException(message: String) : Exception(message)

/**
 * Microphone-backed [InputStream] for the Whisper SDK.
 *
 * 16 kHz / mono / PCM 16-bit per the developer guide (section 2.5). The SDK reads it from its
 * own worker thread, so [read] blocks. After [goSilent] the mic is released and zero-filled
 * frames are returned instead, which is what lets the VAD observe end-of-speech.
 */
private class MicInputStream : InputStream(), AsrSessionController.Input {

    private var record: AudioRecord? = null
    @Volatile private var silent = false
    @Volatile private var closed = false

    fun start() {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(min > 0) { "AudioRecord unsupported, minBufferSize=$min" }
        // Several frames worth so a 1280-byte pull never starves.
        val size = maxOf(min, FRAME_BYTES * 4)

        @Suppress("MissingPermission")
        val r = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        r.startRecording()
        record = r
        openedAt = System.currentTimeMillis()
        Log.i(TAG, "MICROPHONE_READY buffer=${size}B")
    }

    /** Stop capturing and return silence so the VAD can close the utterance. */
    override fun goSilent() {
        if (!silent && record != null) {
            Log.i(TAG, "MICROPHONE_CLOSED captured=${totalBytes / 32000.0}s peak=$peak")
        }
        silent = true
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    /** Ends the stream: subsequent reads return -1, unblocking the SDK's read loop. */
    override fun release() {
        closed = true
        goSilent()
    }

    override fun read(): Int = throw UnsupportedOperationException("byte-at-a-time not supported")

    private var openedAt = 0L
    private var totalBytes = 0L
    private var peak = 0
    private var lastLogAt = 0L

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (silent || record == null) {
            java.util.Arrays.fill(b, off, off + len, 0)
            return len
        }
        val n = record!!.read(b, off, len, AudioRecord.READ_BLOCKING)
        if (n <= 0) return -1 // <=0 => end of stream, unblocks the SDK's read loop
        if (totalBytes == 0L) Log.i(TAG, "AUDIO_BUFFER_RECEIVED first after ${System.currentTimeMillis() - openedAt}ms")
        totalBytes += n
        var i = off
        while (i < off + n - 1) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
            if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
            i += 2
        }
        val now = System.currentTimeMillis()
        if (now - lastLogAt > 3000) {
            lastLogAt = now
            Log.d(TAG, "mic stream: ${totalBytes / 32000.0}s, peak=$peak (${if (peak > 300) "signal" else "quiet"})")
        }
        return n
    }

    private companion object {
        const val TAG = "WhisperSTT.mic"
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 1_280 // 40 ms, per the guide
    }
}
