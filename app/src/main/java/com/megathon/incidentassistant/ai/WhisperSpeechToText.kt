package com.megathon.incidentassistant.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import com.qualcomm.qti.voice.assist.whisper.sdk.Whisper
import com.qualcomm.qti.voice.assist.whisper.sdk.WhisperResponseListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val partial = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = partial.asStateFlow()

    @Volatile private var whisper: Whisper? = null
    @Volatile private var audio: MicInputStream? = null

    /** Final text accumulated from `isFinal == true` callbacks. */
    private val finalText = StringBuilder()
    /** Rolling partial text, cleared whenever a final segment lands. */
    private val partialText = StringBuilder()

    private var finished = CompletableDeferred<Unit>()
    private var lastError: Int? = null
    private var ready = false

    // ---------- SpeechToText ----------

    override suspend fun prepare() = withContext(Dispatchers.Default) {
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

        val rc = w.init(modelDir)
        if (rc == Whisper.NO_ERROR) {
            whisper = w
            ready = true
            Log.i(TAG, "whisper ready, modelDir=$modelDir version=${w.version}")
        } else {
            Log.e(TAG, "whisper.init failed rc=$rc modelDir=$modelDir")
            w.deInit()
        }
        Unit
    }

    override suspend fun startListening() {
        resetUtterance()
        lastError = null
        val w = whisper ?: error("Whisper SDK not initialised (model files missing?)")

        val mic = MicInputStream().also { it.start() }
        audio = mic
        withContext(Dispatchers.Main) { w.start(mic) }
    }

    override suspend fun stopListening(): String {
        val w = whisper ?: throw IllegalStateException("ASR unavailable")

        // Let the VAD see the end of the utterance: swap mic audio for silence and wait for
        // the SDK's own end-of-speech -> flush -> onFinished sequence.
        audio?.goSilent()
        val flushed = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { finished.await() }

        if (flushed == null) {
            // No end-of-speech detected in time; force a stop and keep whatever finals we got.
            Log.w(TAG, "flush timed out, forcing stop (error=$lastError)")
            w.stop()
            withTimeoutOrNull(FORCE_STOP_TIMEOUT_MS) { finished.await() }
        }

        audio?.release()
        audio = null

        val text = finalText.toString().trim()
        if (lastError == WhisperResponseListener.NO_SPEECH_TIMEOUT && text.isEmpty()) {
            throw NoSpeechException("no speech detected")
        }
        return text
    }

    override fun release() {
        runCatching { audio?.release() }
        audio = null
        runCatching { whisper?.deInit() }
        whisper = null
        ready = false
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
            if (transcription.isEmpty()) return

            synchronized(this@WhisperSpeechToText) {
                if (isFinal) {
                    partialText.setLength(0)
                    if (finalText.isNotEmpty()) finalText.append(' ')
                    finalText.append(transcription)
                } else {
                    partialText.setLength(0)
                    partialText.append(transcription)
                }
                publish()
            }
        }

        override fun onError(errorCode: Int, ex: Exception?) {
            Log.e(TAG, "whisper error $errorCode: ${ex?.message}")
            lastError = errorCode
            if (!finished.isCompleted) finished.complete(Unit)
        }

        override fun onRecordingStopped() {
            Log.d(TAG, "onRecordingStopped")
        }

        override fun onSpeechStart() = Unit

        override fun onSpeechEnd() = Unit

        /** All audio processed and the SDK has stopped itself -> release the waiter. */
        override fun onFinished() {
            Log.d(TAG, "onFinished")
            if (!finished.isCompleted) finished.complete(Unit)
        }
    }

    // ---------- helpers ----------

    private fun resetUtterance() = synchronized(this) {
        finalText.setLength(0)
        partialText.setLength(0)
        partial.value = ""
        finished = CompletableDeferred()
    }

    /** Live caption = whatever has been finalised so far + the in-flight partial. */
    private fun publish() {
        val f = finalText.toString()
        val p = partialText.toString()
        partial.value = if (p.isEmpty()) f else if (f.isEmpty()) p else "$f $p"
    }

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
private class MicInputStream : InputStream() {

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
    }

    /** Stop capturing and return silence so the VAD can close the utterance. */
    fun goSilent() {
        silent = true
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    /** Ends the stream: subsequent reads return -1, unblocking the SDK's read loop. */
    fun release() {
        closed = true
        goSilent()
    }

    override fun read(): Int = throw UnsupportedOperationException("byte-at-a-time not supported")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (silent || record == null) {
            java.util.Arrays.fill(b, off, off + len, 0)
            return len
        }
        val n = record!!.read(b, off, len, AudioRecord.READ_BLOCKING)
        return if (n <= 0) -1 else n // <=0 => end of stream, unblocks the SDK's read loop
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 1_280 // 40 ms, per the guide
    }
}
