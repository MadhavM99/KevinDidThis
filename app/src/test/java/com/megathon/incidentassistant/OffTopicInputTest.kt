package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.form.ToolCallParser
import com.megathon.incidentassistant.session.FastIntent
import com.megathon.incidentassistant.session.FastIntents
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-topic input: redirected politely, never changes the draft, never files, and the
 * conversation resumes where it was (pending question kept).
 */
class OffTopicInputTest {

    private val offTopic = """<tool_call>{"tool":"off_topic","arguments":{}}</tool_call>"""
    private val unsure = """<tool_call>{"tool":"off_topic","arguments":{"unsure":true}}</tool_call>"""
    private val severityQuestion = "How severe is it — low, medium, high, or critical?"

    @Test
    fun weatherQuestionFirstThingChangesNothingAndRedirects() = runBlocking {
        val llm = ScriptedLlm.of(offTopic)
        val f = SessionFixture(llm)
        // Not one of the canned small-talk phrases: the model decides.
        val reply = f.session.onUtterance("Do you think it will rain later this week?")

        assertEquals("REDIRECT: describe the incident.", reply.speech)
        assertEquals(ReplyKind.ASK, reply.kind) // keeps the hands-free loop listening
        assertEquals(SessionPhase.COLLECTING, reply.phase)
        assertTrue(reply.changedFieldIds.isEmpty())
        assertTrue(f.session.form.entries().isEmpty())
        assertEquals(1, llm.calls)
    }

    @Test
    fun greetingNeedsNoModelCallAndCannotExtractOrSubmit() = runBlocking {
        val llm = ScriptedLlm.of() // any model call fails the test
        val f = SessionFixture(llm)
        for (said in listOf("Hello, how are you?", "Tell me a joke.", "What's the weather today?")) {
            assertEquals(said, FastIntent.SMALL_TALK, FastIntents.classify(said))
        }
        val reply = f.session.onUtterance("Hello, how are you?")
        assertEquals("REDIRECT: describe the incident.", reply.speech)
        assertEquals(0, llm.calls)
        assertTrue(f.session.form.entries().isEmpty())
        assertTrue(f.repository.saved.isEmpty())
        assertTrue(f.submitter.sent.isEmpty())
    }

    @Test
    fun genuineIncidentStillFollowsTheExtractionFlow() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        val reply = f.session.onUtterance(WATER_LEAK)
        assertEquals(severityQuestion, reply.speech)
        assertEquals("water_leak", f.session.form.text("incident_type"))
    }

    @Test
    fun plainWordsIncidentWithoutKeywordsIsExtracted() = runBlocking {
        val f = SessionFixture(
            ScriptedLlm.of(recordFields(U("incident_type", "water_leak", 0.8, "water is coming through the ceiling"))),
        )
        f.session.onUtterance("Water is coming through the ceiling")
        assertEquals("water_leak", f.session.form.text("incident_type"))
    }

    @Test
    fun mediumIsAcceptedWhileSeverityIsPending() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION)
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Medium.")
        assertEquals("medium", f.session.form.text("severity"))
        assertEquals(1, llm.calls)
    }

    @Test
    fun offTopicAnswerKeepsThePendingQuestionAndAsksItAgain() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)

        val reply = f.session.onUtterance("What's the weather today?")
        assertEquals("REDIRECT. $severityQuestion", reply.speech)
        assertEquals(listOf("severity"), reply.pendingFieldIds)
        assertEquals(SessionPhase.COLLECTING, reply.phase)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)

        // Off-topic is not a failed answer: no "didn't catch that" rephrase, no "not specified" offer.
        assertEquals(severityQuestion, f.session.onUtterance("repeat").speech)
        f.session.onUtterance("Medium")
        assertEquals("medium", f.session.form.text("severity"))
    }

    @Test
    fun existingValuesAndProvenanceSurvive() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION, offTopic))
        f.session.onUtterance(WATER_LEAK)
        val before = f.session.form.entries()

        f.session.onUtterance("Can you recommend a good restaurant nearby?")
        assertEquals(before, f.session.form.entries()) // values, confidence, evidence, utterance index
    }

    @Test
    fun hallucinatedUpdatesNextToOffTopicAreNotApplied() = runBlocking {
        val contradictory = offTopic + recordFields(U("severity", "high"), U("location", "Mars"))
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION, contradictory))
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("Who won the match yesterday?")

        assertEquals("UNSURE. $severityQuestion", reply.speech)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)
        assertEquals("Conference Room 2", f.session.form.text("location"))
    }

    @Test
    fun offTopicCannotSubmitEvenAtConfirmation() = runBlocking {
        val sneaky = offTopic + """<tool_call>{"tool":"submit_report","arguments":{}}</tool_call>"""
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION, sneaky))
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Medium")
        f.session.onUtterance("No")
        val confirm = f.session.onUtterance("No")
        assertEquals(SessionPhase.CONFIRMING, confirm.phase)

        val reply = f.session.onUtterance("Play some music for me")
        assertEquals(SessionPhase.CONFIRMING, reply.phase)
        assertTrue(reply.speech.endsWith("File it?")) // the confirmation is asked again
        assertTrue(f.repository.saved.isEmpty())
        assertTrue(f.submitter.sent.isEmpty())
    }

    @Test
    fun offTopicDuringAnOptionalQuestionSkipsNothing() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION, offTopic))
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Medium") // → people group question
        val reply = f.session.onUtterance("Is the cafeteria open now?")

        assertEquals(SessionPhase.OPTIONAL, reply.phase)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("witnesses").status) // not marked skipped
    }

    @Test
    fun unsureClassificationAsksInsteadOfRejecting() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(unsure))
        val reply = f.session.onUtterance("Something weird happened with the thing")
        assertEquals("UNSURE. REDIRECT: describe the incident.", reply.speech)
        assertTrue(f.session.form.entries().isEmpty())
    }

    @Test
    fun aValidIncidentAfterOffTopicIsProcessedNormally() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(offTopic, WATER_LEAK_EXTRACTION))
        f.session.onUtterance("What's your favourite colour?")
        val reply = f.session.onUtterance(WATER_LEAK)
        assertEquals(severityQuestion, reply.speech)
        assertEquals(5, reply.changedFieldIds.size)
    }

    @Test
    fun repeatedOffTopicPausesTheLoopAndStillRecovers() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION)
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)

        assertEquals(ReplyKind.ASK, f.session.onUtterance("hello").kind)
        assertEquals(ReplyKind.ASK, f.session.onUtterance("hi there").kind)
        val paused = f.session.onUtterance("tell me a joke")
        assertEquals("PAUSED.", paused.speech)
        assertEquals(ReplyKind.INFO, paused.kind) // UI stops re-opening the mic
        assertEquals(listOf("severity"), paused.pendingFieldIds)
        assertEquals(1, llm.calls) // small talk never reached the model

        // The draft is intact and the next real answer resumes the conversation.
        f.session.onUtterance("High")
        assertEquals("high", f.session.form.text("severity"))
        assertEquals(ReplyKind.ASK, f.session.onUtterance("hey").kind) // streak was reset
    }

    @Test
    fun parserReadsTheOffTopicTool() {
        assertEquals(listOf(FormToolCall.OffTopic(unsure = false)), ToolCallParser.parse(offTopic).calls)
        assertEquals(listOf(FormToolCall.OffTopic(unsure = true)), ToolCallParser.parse(unsure).calls)
    }
}
