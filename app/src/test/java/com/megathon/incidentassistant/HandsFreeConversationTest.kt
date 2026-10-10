package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.LlmException
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechToText
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.session.ListenAfterReply
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.SessionReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow-up cycle end to end on the host: mic → ASR → session (scripted Gemma) →
 * reply spoken by a TTS that takes time to play → [ListenAfterReply] → mic again …
 * Mirrors IncidentViewModel's calls and ordering; only Android is absent.
 */
class HandsFreeConversationTest {

    /** Plays for [playMs]; a broken engine never reports completion (the device-stall case). */
    private class FakeTts(private val scope: CoroutineScope, private val playMs: Long, private val broken: Boolean) :
        SpeechSynthesizer {
        private val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val utteranceFinished: Flow<Unit> = finished
        @Volatile var speaking = false
        val spoken = mutableListOf<String>()
        override fun speak(text: String) {
            spoken += text
            speaking = true
            scope.launch {
                delay(playMs)
                speaking = false
                if (!broken) finished.tryEmit(Unit)
            }
        }
        override fun stop() {
            speaking = false
        }
        override fun shutdown() = Unit
    }

    /** ASR that records whether the mic was ever opened while the assistant was talking. */
    private class CheckedAsr(
        private val transcripts: List<String>,
        private val failOnStop: Set<Int> = emptySet(),
    ) : SpeechToText {
        override val partialTranscript: StateFlow<String> = MutableStateFlow("")
        override val utteranceFinished: Flow<Unit> = MutableSharedFlow()
        var starts = 0
        var overlaps = 0
        private var next = 0
        private var stops = 0
        val exhausted get() = next >= transcripts.size
        override suspend fun prepare() = Unit
        override suspend fun startListening() {
            starts++
        }
        override suspend fun stopListening(): String {
            stops++
            if (stops in failOnStop) throw IllegalStateException("whisper error")
            return transcripts[next++]
        }
        override fun release() = Unit
    }

    /** The view model's turn loop, minus Android. */
    private class Driver(
        private val scope: CoroutineScope,
        private val f: SessionFixture,
        private val asr: CheckedAsr,
        private val tts: FakeTts,
        watchdogMs: Long,
    ) {
        val replies = mutableListOf<SessionReply>()
        val asrErrors = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        private val handsFree = ListenAfterReply(scope, tts.utteranceFinished, settleMs = 10, watchdogMs = { watchdogMs })

        /** onStartRecording → (VAD) → finalizeTranscript → process → applyReply. */
        fun startRecording() {
            if (asr.exhausted) {
                done.complete(Unit)
                return
            }
            // A reopen that arrives while the reply is still playing would cut it off:
            // checked here, before onStartRecording's own synthesizer.stop().
            if (tts.speaking) asr.overlaps++
            scope.launch {
                tts.stop() // onStartRecording: never talk over the user
                asr.startListening()
                val text = try {
                    asr.stopListening()
                } catch (e: Exception) {
                    // fail(): recoverable error state, the user taps the mic again.
                    asrErrors += e.message.orEmpty()
                    startRecording()
                    return@launch
                }
                val reply = f.session.onUtterance(text)
                replies += reply
                val awaitsAnswer = reply.phase != SessionPhase.SUBMITTED &&
                    reply.phase != SessionPhase.CANCELLED && reply.kind != ReplyKind.INFO
                handsFree.afterReply(reply.speech, awaitsAnswer, speak = { tts.speak(reply.speech); true }) {
                    startRecording() // maybeAutoListen → onStartRecording
                }
                if (!awaitsAnswer) done.complete(Unit)
            }
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
    private val answers = listOf(
        "There was a water leak near Conference Room 2.",
        "Medium.",                                            // follow-up 1: severity (no LLM)
        "Around 10 AM, maintenance isolated the area.",       // follow-up 2: combined (LLM)
        "No.", "No.",                                         // optional groups
        "Yes.",                                               // confirmation → filed
    )

    private fun runConversation(broken: Boolean, llm: ScriptedLlm, transcripts: List<String> = answers, failOnStop: Set<Int> = emptySet()) =
        runBlocking {
            val f = SessionFixture(llm)
            val tts = FakeTts(this, playMs = 120, broken = broken)
            val asr = CheckedAsr(transcripts, failOnStop)
            val driver = Driver(this, f, asr, tts, watchdogMs = 400)
            driver.startRecording()
            withTimeout(15_000) { driver.done.await() }
            delay(50)
            Result(f, tts, asr, driver)
        }

    private data class Result(val f: SessionFixture, val tts: FakeTts, val asr: CheckedAsr, val driver: Driver)

    @Test
    fun severalFollowUpsAreAnsweredHandsFreeThroughToFiling() {
        val (f, tts, asr, driver) = runConversation(broken = false, llm = ScriptedLlm.of(partialLeak, timeAndActions))

        assertEquals(6, asr.starts)                       // every turn re-opened the mic
        assertEquals("mic opened while TTS was playing", 0, asr.overlaps)
        assertEquals(SessionPhase.SUBMITTED, driver.replies.last().phase)
        assertEquals(severityQuestion, driver.replies[0].speech)
        assertEquals(listOf("occurred_at", "immediate_actions"), driver.replies[1].pendingFieldIds)
        // Second-turn answer reached the same session and filled both fields; earlier ones kept.
        assertEquals("2026-10-10T10:00:00", f.session.form.text("occurred_at"))
        assertEquals("Area isolated by maintenance", f.session.form.text("immediate_actions"))
        assertEquals("Conference Room 2", f.session.form.text("location"))
        assertEquals("medium", f.session.form.text("severity"))
        assertEquals(1, f.repository.saved.size)          // filed once, after the explicit yes
        assertEquals(6, tts.spoken.size)
    }

    @Test
    fun ttsThatNeverReportsCompletionNoLongerStallsTheConversation() {
        // The device stall: the follow-up is shown/spoken, the "finished" callback never comes.
        val (f, _, asr, driver) = runConversation(broken = true, llm = ScriptedLlm.of(partialLeak, timeAndActions))
        assertEquals(6, asr.starts)
        assertEquals(0, asr.overlaps)
        assertEquals(SessionPhase.SUBMITTED, driver.replies.last().phase)
        assertEquals("medium", f.session.form.text("severity"))
    }

    @Test
    fun aGemmaFailureMidConversationKeepsTheLoopGoing() {
        val llm = ScriptedLlm { _, i ->
            when (i) {
                0 -> partialLeak
                1 -> throw LlmException("NPU busy")       // the combined answer cannot be read
                else -> timeAndActions
            }
        }
        val transcripts = listOf(
            "There was a water leak near Conference Room 2.", "Medium.",
            "uh around ten and they closed it off", "Around 10 AM, maintenance isolated the area.",
        )
        val (f, _, asr, driver) = runConversation(broken = false, llm = llm, transcripts = transcripts)
        assertEquals(4, asr.starts)
        assertEquals("AGAIN: When did it happen?", driver.replies[2].speech) // recoverable re-ask
        assertEquals(FieldStatus.FILLED, f.session.form.get("occurred_at").status)
        assertEquals(FieldStatus.FILLED, f.session.form.get("immediate_actions").status)
    }

    @Test
    fun anAsrFailureIsRecoverableAndThePendingQuestionSurvives() {
        val transcripts = listOf("There was a water leak near Conference Room 2.", "Medium.")
        val (f, _, asr, driver) = runConversation(
            broken = false, llm = ScriptedLlm.of(partialLeak), transcripts = transcripts, failOnStop = setOf(2),
        )
        assertEquals(listOf("whisper error"), driver.asrErrors)
        assertEquals(3, asr.starts)                        // failed attempt + retry
        assertEquals("medium", f.session.form.text("severity")) // the retry answered the same question
    }

    private companion object {
        const val severityQuestion = "How severe is it — low, medium, high, or critical?"
    }
}
