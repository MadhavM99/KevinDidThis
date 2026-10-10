package com.megathon.incidentassistant.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands-free turn-taking: after a spoken reply, decide when the microphone may reopen.
 *
 * The mic reopens when the TTS backend reports the reply finished ([ttsFinished]) — never
 * while it is still speaking — plus a short settle so the speaker tail stays out of the
 * mic. If that report never arrives (engine not initialised, `start()` rejected, no model
 * on the device, an exception swallowed inside the backend), a watchdog sized to the
 * reply's length reopens it anyway: a missing callback must not strand the conversation
 * with a question on screen and nobody listening.
 *
 * One reply at a time: a newer reply, or [cancel], drops the pending reopen, so a turn can
 * never trigger two recordings. Pure Kotlin; no audio code is touched.
 */
class ListenAfterReply(
    private val scope: CoroutineScope,
    private val ttsFinished: Flow<Unit>,
    private val settleMs: Long = SETTLE_MS,
    private val watchdogMs: (String) -> Long = ::speechWatchdogMs,
    private val log: (String) -> Unit = {},
) {
    private var pending: Job? = null

    /**
     * Speaks [text] via [speak] (which returns false when nothing will be spoken, e.g.
     * prompts muted or a screen reader is active) and, if [listenAfter], calls [onReady]
     * once the mic may reopen. Not spoken → no hands-free reopen: the user taps the mic.
     */
    fun afterReply(text: String, listenAfter: Boolean, speak: () -> Boolean, onReady: () -> Unit) {
        pending?.cancel()
        pending = null
        if (!listenAfter) {
            speak()
            return
        }
        // UNDISPATCHED: subscribe to the completion signal BEFORE speaking, so an engine
        // that finishes (or fails) instantly cannot be missed.
        pending = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val finished = async(start = CoroutineStart.UNDISPATCHED) { ttsFinished.first() }
            if (!speak()) {
                finished.cancel()
                log("TTS_SKIPPED listen=tap")
                return@launch
            }
            log("TTS_REQUESTED")
            val budget = watchdogMs(text)
            if (withTimeoutOrNull(budget) { finished.await() } == null) {
                finished.cancel()
                log("TIMEOUT tts_finished_missing after=${budget}ms")
            } else {
                log("TTS_PLAYBACK_COMPLETE")
            }
            delay(settleMs)
            log("MICROPHONE_REOPEN")
            onReady()
        }
    }

    /** Drops a pending reopen (user started talking, reset, screen closed). */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        /** Speaker drain before the mic reopens. */
        const val SETTLE_MS = 400L

        /**
         * Generous upper bound for synthesising + playing [text]: ~11 characters/s spoken
         * (normal speech is ~15) plus fixed synthesis/start-up headroom. Only reached when
         * the completion callback is missing; a working engine reopens the mic earlier.
         */
        fun speechWatchdogMs(text: String): Long = 4_000L + 90L * text.length
    }
}
