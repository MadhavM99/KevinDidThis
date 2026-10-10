package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.AsrSessionController
import com.megathon.incidentassistant.ai.NoSpeechException
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.session.ListenAfterReply
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.SessionReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-turn voice loop with the real [AsrSessionController], [ListenAfterReply] and
 * [com.megathon.incidentassistant.session.IncidentSession], wired in IncidentViewModel's
 * order (onStartRecording → utteranceFinished → finalizeTranscript → process → applyReply
 * → afterReply → maybeAutoListen). One shared timeline checks the handoffs: the mic never
 * opens while TTS plays, and the previous Whisper session is closed before the next starts.
 */
class VoiceTurnLifecycleTest {

    private sealed interface Say {
        data class Words(val text: String) : Say
        /** HTP/engine failure reported by the SDK as an error, no finish. */
        data object EngineError : Say
        data object Nothing : Say
    }

    private class Timeline {
        val events = mutableListOf<String>()
        fun add(e: String) = synchronized(events) { events += e }
        fun idx(e: String) = synchronized(events) { events.indexOf(e) }
        fun all(prefix: String) = synchronized(events) { events.withIndex().filter { it.value.startsWith(prefix) } }
    }

    private class Tts(private val scope: CoroutineScope, private val t: Timeline) : SpeechSynthesizer {
        private val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val utteranceFinished: Flow<Unit> = finished
        private var job: Job? = null
        var n = 0
        override fun speak(text: String) {
            val i = ++n
            t.add("tts_start$i")
            job = scope.launch {
                delay(60) // synthesis + playback; "finished" = playback drained
                t.add("tts_done$i")
                finished.tryEmit(Unit)
            }
        }
        override fun stop() {
            job?.cancel()
        }
        override fun shutdown() = Unit
    }

    private class Sdk(private val scope: CoroutineScope, private val t: Timeline, private val script: List<Say>) :
        AsrSessionController.Engine {
        lateinit var c: AsrSessionController
        private var n = 0
        private var running: Int? = null
        override fun openInput(): AsrSessionController.Input {
            val i = ++n
            t.add("mic_open$i")
            return object : AsrSessionController.Input {
                override fun goSilent() = Unit
                override fun release() = t.add("mic_release$i")
            }
        }
        override suspend fun start(input: AsrSessionController.Input) {
            check(running == null) { "SDK start while session $running is open" }
            running = n
            t.add("asr_start$n")
            val say = script.getOrElse(n - 1) { Say.Nothing }
            scope.launch {
                delay(25)
                when (say) {
                    is Say.Words -> {
                        c.onTranscription(say.text, isFinal = true)
                        running = null
                        c.onFinished()
                    }
                    Say.EngineError -> c.onError(-1)
                    Say.Nothing -> c.onError(NO_SPEECH)
                }
            }
        }
        override fun stop() {
            running = null
            t.add("asr_stop")
        }
    }

    /** IncidentViewModel's turn loop, minus Android. */
    private class Loop(private val scope: CoroutineScope, val f: SessionFixture, script: List<Say>, val maxTurns: Int) {
        val t = Timeline()
        val tts = Tts(scope, t)
        private val sdk = Sdk(scope, t, script)
        val asr = AsrSessionController(sdk, NO_SPEECH, flushTimeoutMs = 300, forceStopTimeoutMs = 200).also { sdk.c = it }
        private val handsFree = ListenAfterReply(scope, tts.utteranceFinished, settleMs = 10, watchdogMs = { 2_000 })
        val replies = mutableListOf<SessionReply>()
        val done = CompletableDeferred<Unit>()
        var question: String? = null
        var turns = 0
        var reprompts = 0

        fun onStartRecording() {
            if (++turns > maxTurns) return run { done.complete(Unit) }
            tts.stop()
            scope.launch {
                val ended = scope.launch { asr.utteranceFinished.first() }
                asr.start()
                ended.join()
                finalize()
            }
        }

        private suspend fun finalize() {
            val text = try {
                asr.stop()
            } catch (_: NoSpeechException) {
                ""
            }
            if (text.isEmpty()) return reprompt()
            val reply = f.session.onUtterance(text)
            replies += reply
            val open = reply.phase != SessionPhase.SUBMITTED && reply.phase != SessionPhase.CANCELLED
            val listen = open && reply.kind != ReplyKind.INFO
            question = reply.speech.takeIf { listen }
            handsFree.afterReply(reply.speech, listen, speak = { tts.speak(reply.speech); true }, onReady = ::onStartRecording)
            if (!listen) done.complete(Unit)
        }

        private fun reprompt() {
            val q = question ?: return run { done.complete(Unit) }
            reprompts++
            handsFree.afterReply(q, listenAfter = true, speak = { tts.speak(q); true }, onReady = ::onStartRecording)
        }
    }

    private val partialLeak = recordFields(
        U("incident_type", "water_leak", 0.95, "water leak"),
        U("location", "Conference Room 2", 0.9, "near Conference Room 2"),
        U("description", "Water leak near Conference Room 2.", 0.8, "a water leak near Conference Room 2"),
    )
    private val timeAndActions = recordFields(
        U("occurred_at", "2026-10-10T10:00:00", 0.8, "around 10 AM"),
        U("immediate_actions", "Area isolated by maintenance", 0.9, "maintenance isolated the area"),
    )

    private fun run(script: List<Say>, llm: ScriptedLlm, maxTurns: Int = 20) = runBlocking {
        val loop = Loop(this, SessionFixture(llm), script, maxTurns)
        loop.onStartRecording()
        withTimeout(20_000) { loop.done.await() }
        delay(30)
        loop
    }

    /** The handoff invariants, checked on the shared timeline. */
    private fun assertHandoffs(loop: Loop) {
        val t = loop.t
        val starts = t.all("asr_start")
        for ((k, s) in starts.withIndex()) {
            val n = k + 1
            if (n > 1) {
                // The question that preceded this answer had finished playing.
                assertTrue("asr_start$n before tts_done${n - 1}", t.idx("tts_done${n - 1}") in 0 until s.index)
                // The previous session was stopped and its mic released first.
                assertTrue("session ${n - 1} not released before $n", t.idx("mic_release${n - 1}") in 0 until s.index)
            }
        }
        // Never more Whisper sessions than SDK stops + 1 (each closed exactly once).
        assertTrue(t.all("asr_stop").size >= starts.size - 1)
    }

    @Test
    fun threeOrMoreSpokenFollowUpsReachTheReviewAndFileOnlyAfterYes() {
        val loop = run(
            listOf(
                Say.Words("There was a water leak near Conference Room 2."),
                Say.Words("Medium."),
                Say.Words("Around 10 AM, maintenance isolated the area."),
                Say.Words("No."), Say.Words("No."),
                Say.Words("Yes."),
            ),
            ScriptedLlm.of(partialLeak, timeAndActions),
        )
        assertHandoffs(loop)
        assertEquals(6, loop.t.all("asr_start").size)
        assertEquals(SessionPhase.CONFIRMING, loop.replies[4].phase) // review read out before filing
        assertEquals(SessionPhase.SUBMITTED, loop.replies.last().phase)
        assertEquals(1, loop.f.repository.saved.size)
        assertEquals("Conference Room 2", loop.f.session.form.text("location"))
        assertEquals("medium", loop.f.session.form.text("severity"))
        assertEquals("Area isolated by maintenance", loop.f.session.form.text("immediate_actions"))
    }

    @Test
    fun anEngineErrorOnTheFollowUpIsReAskedAndKeepsEarlierFields() {
        val loop = run(
            listOf(
                Say.Words("There was a water leak near Conference Room 2."),
                Say.EngineError,               // the device symptom: answer recorded, nothing transcribed
                Say.Words("Medium."),
                Say.Words("Around 10 AM, maintenance isolated the area."),
            ),
            ScriptedLlm.of(partialLeak, timeAndActions),
            maxTurns = 4,
        )
        assertHandoffs(loop)
        assertEquals(1, loop.reprompts)
        assertEquals("Conference Room 2", loop.f.session.form.text("location")) // not erased
        assertEquals("medium", loop.f.session.form.text("severity"))          // retry answered it
        assertEquals("2026-10-10T10:00:00", loop.f.session.form.text("occurred_at"))
        assertEquals(0, loop.f.repository.saved.size)                           // no submit without yes
    }

    @Test
    fun silenceTwiceThenAnAnswerStillAdvances() {
        val loop = run(
            listOf(
                Say.Words("There was a water leak near Conference Room 2."),
                Say.Nothing, Say.Nothing,
                Say.Words("Medium."),
            ),
            ScriptedLlm.of(partialLeak),
            maxTurns = 4,
        )
        assertHandoffs(loop)
        assertEquals(2, loop.reprompts)
        assertEquals("medium", loop.f.session.form.text("severity"))
    }

    private companion object {
        const val NO_SPEECH = -4
    }
}
