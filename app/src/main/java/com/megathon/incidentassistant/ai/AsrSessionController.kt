package com.megathon.incidentassistant.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Session lifecycle for a callback-driven streaming ASR engine (the Qualcomm Whisper SDK):
 * one utterance = open input → engine start → (VAD finish | error | forced stop) → engine
 * stop → input released. Pure Kotlin so the ordering is testable on the host; the SDK and
 * the microphone live behind [Engine] / [Input] (see [WhisperSpeechToText]).
 *
 * Invariants (each one closes a way the follow-up turn used to go silent):
 *  - `engine.stop()` is called by [stop] exactly once per session, under the session lock,
 *    so it always completes before the next `engine.start()` — never from inside an SDK
 *    callback, where it could land on a session that started meanwhile.
 *  - A session that was never stopped (e.g. its error was never acted on) is closed —
 *    stopped AND its finish awaited — before the next one starts.
 *  - Errors end the utterance like a VAD finish does ([utteranceFinished] fires), so the
 *    caller finalises the turn instead of showing "listening" forever.
 *  - Callbacks arriving while no session is open are ignored (logged as stale): they can
 *    neither end nor add text to a later session.
 *  - A failed start releases the input and propagates, instead of a silent fake session.
 */
class AsrSessionController(
    private val engine: Engine,
    private val noSpeechErrorCode: Int,
    private val flushTimeoutMs: Long = 4_000,
    private val forceStopTimeoutMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** The audio source handed to the engine for one session. */
    interface Input {
        /** Replace capture with silence so the engine's VAD can observe end-of-speech. */
        fun goSilent()

        /** End the stream and free the microphone. */
        fun release()
    }

    interface Engine {
        /** Opens (and starts) a fresh audio input; throws if the microphone is unavailable. */
        fun openInput(): Input

        /** Starts recognition on [input]; throws if the engine rejects it. */
        suspend fun start(input: Input)

        /** Closes the current engine session (no-op if it already stopped itself). */
        fun stop()
    }

    private class Session(val id: Int, val input: Input, val startedAt: Long) {
        val finished = CompletableDeferred<Unit>()
        @Volatile var error: Int? = null
        val finalText = StringBuilder()
        val partialText = StringBuilder()
    }

    private val partial = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = partial.asStateFlow()

    private val completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The engine ended the utterance (VAD finish or error): the caller should [stop]. */
    val utteranceFinished: Flow<Unit> = completed

    private val lock = Mutex()
    @Volatile private var current: Session? = null
    private var nextId = 0

    suspend fun start() = lock.withLock {
        current?.let { close(it, reason = "restart") }
        val id = ++nextId
        partial.value = ""
        val input = engine.openInput()
        val s = Session(id, input, clock())
        current = s
        log("ASR_START session=$id")
        try {
            engine.start(input)
        } catch (e: Throwable) {
            current = null
            runCatching { input.release() }
            if (e !is CancellationException) log("ASR_FAILURE session=$id start ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** Ends the open session and returns its final text. */
    suspend fun stop(): String = lock.withLock {
        val s = current ?: throw IllegalStateException("no ASR session open")
        log("ASR_STOP_REQUESTED session=${s.id}")
        close(s, reason = "stop")
        val text = synchronized(s) { s.finalText.toString().trim() }
        val error = s.error
        if (error == noSpeechErrorCode && text.isEmpty()) throw NoSpeechException("no speech detected")
        text
    }

    /** Frees the microphone without waiting (screen closed); the engine stays initialised. */
    fun abandon() {
        val s = current ?: return
        current = null
        runCatching { s.input.release() }
        runCatching { engine.stop() }
        log("CANCELLED session=${s.id}")
    }

    // ---------- engine callbacks (any thread) ----------

    fun onTranscription(text: String, isFinal: Boolean) {
        val s = current ?: return
        if (text.isEmpty()) return
        synchronized(s) {
            s.partialText.setLength(0)
            if (isFinal) {
                if (s.finalText.isNotEmpty()) s.finalText.append(' ')
                s.finalText.append(text)
            } else {
                s.partialText.append(text)
            }
            val f = s.finalText.toString()
            val p = s.partialText.toString()
            if (current === s) partial.value = if (p.isEmpty()) f else if (f.isEmpty()) p else "$f $p"
        }
    }

    fun onError(code: Int) {
        val s = current ?: return log("STALE_CALLBACK error=$code (no open session)")
        s.error = code
        log("ASR_ERROR session=${s.id} code=$code after=${clock() - s.startedAt}ms")
        end(s)
    }

    fun onFinished() {
        val s = current ?: return log("STALE_CALLBACK finished (no open session)")
        log("ASR_FINISHED session=${s.id} after=${clock() - s.startedAt}ms")
        end(s)
    }

    // ---------- helpers ----------

    private fun end(s: Session) {
        // Only the first end of a session signals the caller; repeats are no-ops.
        if (s.finished.complete(Unit)) completed.tryEmit(Unit)
    }

    /**
     * Lets the engine flush (input silenced → VAD closes the utterance), then closes the
     * engine session; if no finish came, the stop forces one and is waited for, bounded.
     */
    private suspend fun close(s: Session, reason: String) {
        val t0 = clock()
        s.input.goSilent()
        val flushed = withTimeoutOrNull(flushTimeoutMs) { s.finished.await() } != null
        if (!flushed) log("TIMEOUT asr_flush session=${s.id} after=${flushTimeoutMs}ms error=${s.error}")
        runCatching { engine.stop() }
            .onFailure { log("ASR_FAILURE session=${s.id} stop ${it.javaClass.simpleName}: ${it.message}") }
        if (!flushed) withTimeoutOrNull(forceStopTimeoutMs) { s.finished.await() }
        current = null
        runCatching { s.input.release() }
        log(
            "ASR_STOP_COMPLETED session=${s.id} reason=$reason flushed=$flushed " +
                "finished=${s.finished.isCompleted} error=${s.error} took=${clock() - t0}ms " +
                "chars=${synchronized(s) { s.finalText.length }}",
        )
    }
}
