package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.LlmException
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The required-field loop, clarification, confirmation and submission safety (host-side). */
class IncidentSessionTest {

    private val severityQuestion = "How severe is it — low, medium, high, or critical?"
    private val peoplePrompt = "Were any other people involved, or any witnesses you'd like to note?"

    // ---------- missing required fields ----------

    @Test
    fun oneMissingRequiredFieldIsAskedWithTheSchemaQuestion() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        val reply = f.session.onUtterance(WATER_LEAK)

        assertEquals(severityQuestion, reply.speech)
        assertEquals(ReplyKind.ASK, reply.kind)
        assertEquals(listOf("severity"), reply.pendingFieldIds)
        assertEquals("water_leak", f.session.form.text("incident_type"))
        assertEquals("Conference Room 2", f.session.form.text("location"))
        assertEquals("2026-10-10T10:00:00", f.session.form.text("occurred_at"))
        assertEquals("Area isolated by maintenance", f.session.form.text("immediate_actions"))
        // Nothing invented for what the user did not say.
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("witnesses").status)
    }

    @Test
    fun severalMissingFieldsAreAskedOneAtATimeInSchemaOrder() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(recordFields(U("incident_type", "fire", evidence = "a fire"))))
        assertEquals(severityQuestion, f.session.onUtterance("There is a fire").speech)
        // "High" is an exact option: no model call needed.
        val next = f.session.onUtterance("High")
        assertEquals("Where did it happen?", next.speech)
        assertEquals("high", f.session.form.text("severity"))
        assertEquals(1, (f.llm as ScriptedLlm).calls)
    }

    @Test
    fun answerToThePendingQuestionGoesToTheModelWithContext() = runBlocking {
        val llm = ScriptedLlm.of(
            WATER_LEAK_EXTRACTION,
            recordFields(U("severity", "medium", 0.6, "medium I think")),
        )
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("It's medium I think")

        assertTrue(llm.prompts[1].contains("expects an answer for field id=severity"))
        assertTrue(llm.prompts[1].contains("USER SAID: \"It's medium I think\""))
        // An answer to the question just asked counts as explicit (confidence floor 0.8).
        assertEquals("medium", f.session.form.text("severity"))
        assertEquals(FieldStatus.FILLED, f.session.form.get("severity").status)
        assertEquals(0.8, f.session.form.get("severity").confidence, 1e-9)
        assertEquals(peoplePrompt, reply.speech)
    }

    @Test
    fun oneAnswerCanFillSeveralFieldsIncludingACorrection() = runBlocking {
        val llm = ScriptedLlm.of(
            WATER_LEAK_EXTRACTION,
            recordFields(
                U("severity", "high", 0.9, "high"),
                U("occurred_at", "2026-10-10T11:00:00", 0.9, "it was actually 11", "answered"),
            ),
        )
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("High, and it was actually 11 not 10")

        assertEquals(listOf("severity", "occurred_at"), reply.changedFieldIds)
        assertEquals("high", f.session.form.text("severity"))
        assertEquals("2026-10-10T11:00:00", f.session.form.text("occurred_at"))
    }

    @Test
    fun correctionWhileAnotherQuestionIsPendingIsNotAFailedAnswer() = runBlocking {
        val llm = ScriptedLlm.of(
            WATER_LEAK_EXTRACTION,
            recordFields(U("occurred_at", "2026-10-10T11:00:00", 0.9, "the time was 11 AM", "answered")),
        )
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("Actually, the time was 11 AM.")

        assertEquals("2026-10-10T11:00:00", f.session.form.text("occurred_at"))
        assertEquals("the time was 11 AM", f.session.form.get("occurred_at").evidence)
        // Severity is asked again with the plain question, not a "didn't catch that" rephrase.
        assertEquals(severityQuestion, reply.speech)
    }

    @Test
    fun invalidEnumAnswerIsRejectedAndReAskedWithTheOptions() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION, recordFields(U("severity", "extreme")))
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("It's extreme")

        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)
        assertEquals("AGAIN: Severity? Say Low, Medium, High, or Critical.", reply.speech)
        assertEquals(listOf("severity"), reply.pendingFieldIds)
    }

    // ---------- unknown / skip ----------

    @Test
    fun dontKnowNeedsAnAudibleConfirmationBeforeMarkingNotSpecified() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)

        val offer = f.session.onUtterance("I don't know")
        assertEquals("Mark Severity as not specified?", offer.speech)
        assertEquals(SessionPhase.CONFIRM_UNKNOWN, offer.phase)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)

        val next = f.session.onUtterance("Yes")
        assertEquals(FieldStatus.UNKNOWN, f.session.form.get("severity").status)
        assertTrue(f.session.form.gatePasses())
        assertEquals(peoplePrompt, next.speech)
    }

    @Test
    fun decliningNotSpecifiedAsksTheQuestionAgain() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("not sure")
        val reply = f.session.onUtterance("No")
        assertEquals(severityQuestion, reply.speech)
        assertEquals(SessionPhase.COLLECTING, reply.phase)
    }

    @Test
    fun modelReportingUnknownForARequiredFieldIsNotAppliedSilently() = runBlocking {
        val llm = ScriptedLlm.of(
            WATER_LEAK_EXTRACTION,
            recordFields(U("severity", "", 0.9, "not sure how serious", "unknown")),
        )
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("I'm not sure how serious it is.")

        assertEquals("Mark Severity as not specified?", reply.speech)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)
    }

    @Test
    fun optionalGroupIsAskedOnceAndUnansweredMembersAreSkipped() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION, recordFields(U("witnesses", "Bob", 0.9, "Bob saw it")))
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        assertEquals(peoplePrompt, f.session.onUtterance("Medium").speech)

        val reply = f.session.onUtterance("Bob saw it")
        assertEquals("Bob", f.session.form.text("witnesses"))
        assertEquals(FieldStatus.UNKNOWN, f.session.form.get("persons_involved").status)
        assertEquals("skipped", f.session.form.get("persons_involved").evidence)
        // Next group; the people group is never asked again.
        assertTrue(reply.speech.startsWith("Was any equipment or asset involved"))
    }

    @Test
    fun noClosesTheWholeOptionalGroupWithoutAModelCall() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION)
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Medium")
        f.session.onUtterance("No")
        val confirm = f.session.onUtterance("Nothing to add")

        assertEquals(SessionPhase.CONFIRMING, confirm.phase)
        assertEquals(1, llm.calls)
        for (id in listOf("persons_involved", "witnesses", "equipment_involved", "recommended_actions", "action_status")) {
            assertEquals(id, FieldStatus.UNKNOWN, f.session.form.get(id).status)
        }
    }

    // ---------- malformed model output / failures ----------

    @Test
    fun malformedOutputGetsOneRepairRetryThenAReAsk() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION, "Sure! The severity is medium.", "still {not json")
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("it's kind of in the middle")

        assertEquals(3, llm.calls)
        assertTrue(llm.prompts[2].contains("could not be read"))
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)
        assertEquals("AGAIN: Severity? Say Low, Medium, High, or Critical.", reply.speech)
        assertEquals("Conference Room 2", f.session.form.text("location")) // nothing corrupted
    }

    @Test
    fun repairedOutputIsApplied() = runBlocking {
        val llm = ScriptedLlm.of("I think it was a fire.", recordFields(U("incident_type", "fire")))
        val f = SessionFixture(llm)
        f.session.onUtterance("Something is burning")
        assertEquals("fire", f.session.form.text("incident_type"))
        assertEquals(2, llm.calls)
    }

    @Test
    fun modelFailureOnAFreeTextQuestionKeepsTheAnswer() = runBlocking {
        val llm = ScriptedLlm { _, i ->
            if (i == 0) recordFields(U("incident_type", "fire"), U("severity", "high")) else throw LlmException("npu fault")
        }
        val f = SessionFixture(llm)
        assertEquals("Where did it happen?", f.session.onUtterance("Big fire, very serious").speech)
        val reply = f.session.onUtterance("In the basement car park")

        assertEquals("In the basement car park", f.session.form.text("location"))
        assertEquals("When did it happen?", reply.speech)
        assertEquals("model error: LlmException", reply.metrics?.llmFailure)
    }

    @Test
    fun modelFailureOnTheFirstUtteranceInventsNothingAndKeepsTheUtterance() = runBlocking {
        val f = SessionFixture(ScriptedLlm { _, _ -> throw LlmException("not loaded") })
        val reply = f.session.onUtterance(WATER_LEAK)

        assertEquals("Did not understand. What kind of incident is it?", reply.speech)
        assertTrue(f.session.form.entries().isEmpty())
        assertEquals(listOf(WATER_LEAK), f.session.utterances)
    }

    @Test
    fun unansweredRequiredFieldIsBoundedThenOffersNotSpecified() = runBlocking {
        val empty = recordFields()
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION, empty, empty))
        f.session.onUtterance(WATER_LEAK)
        assertTrue(f.session.onUtterance("hmm well").speech.startsWith("AGAIN"))
        val offer = f.session.onUtterance("hmm")
        assertEquals("Mark Severity as not specified?", offer.speech)
    }

    @Test
    fun withoutUnknownAllowedTheLoopKeepsAskingButPointsAtCancel() = runBlocking {
        val schema = FormSchemaParser.parse(
            """{"id":"strict","fields":[{"id":"severity","label":"Severity","type":"enum","required":true,
               "options":[{"value":"low"},{"value":"high"}]}],"behavior":{"allow_unknown_required":false}}"""
        )
        val f = SessionFixture(ScriptedLlm { _, _ -> recordFields() }, schema = schema)
        f.session.onUtterance("hello")
        f.session.onUtterance("hmm")
        val third = f.session.onUtterance("hmm")
        assertTrue(third.speech.endsWith("(or say cancel)"))
        assertEquals(SessionPhase.COLLECTING, f.session.onUtterance("I don't know").phase)
    }

    @Test
    fun unsureValueIsReadBackForConfirmation() = runBlocking {
        val llm = ScriptedLlm.of(
            recordFields(
                U("incident_type", "water_leak"), U("severity", "low"),
                U("location", "the lobby", 0.4, "somewhere near the lobby maybe"),
                U("occurred_at", "2026-10-10T09:00:00"), U("description", "Leak"), U("immediate_actions", "None yet"),
            ),
        )
        val f = SessionFixture(llm)
        val reply = f.session.onUtterance("water somewhere near the lobby maybe")
        assertEquals("Is Location the lobby?", reply.speech)
        assertEquals(FieldStatus.AMBIGUOUS, f.session.form.get("location").status)

        f.session.onUtterance("yes")
        assertEquals(FieldStatus.FILLED, f.session.form.get("location").status)
    }

    // ---------- cancel / repeat ----------

    @Test
    fun cancelNeedsConfirmationAndFilesNothing() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)
        assertEquals("Discard?", f.session.onUtterance("cancel").speech)
        val done = f.session.onUtterance("yes")

        assertEquals(SessionPhase.CANCELLED, done.phase)
        assertTrue(f.repository.saved.isEmpty())
        assertEquals("Closed.", f.session.onUtterance("hello?").speech)
    }

    @Test
    fun cancelThenNoResumesTheQuestion() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("stop")
        assertEquals(severityQuestion, f.session.onUtterance("no").speech)
        f.session.onUtterance("medium")
        assertEquals("medium", f.session.form.text("severity"))
    }

    @Test
    fun repeatReSpeaksWithoutAModelCall() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION)
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        assertEquals(severityQuestion, f.session.onUtterance("say that again").speech)
        assertEquals(1, llm.calls)
    }

    // ---------- confirmation + submission safety ----------

    private suspend fun SessionFixture.toConfirmation() {
        session.onUtterance(WATER_LEAK)
        session.onUtterance("Medium")
        session.onUtterance("No")
        session.onUtterance("No")
    }

    @Test
    fun completionIsReadBackAndFiledOnlyAfterYes() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.toConfirmation()
        assertEquals(SessionPhase.CONFIRMING, f.session.phase)
        assertTrue(f.repository.saved.isEmpty())

        val filed = f.session.onUtterance("Yes, file it")
        assertEquals(SessionPhase.SUBMITTED, filed.phase)
        assertEquals("Filed RPT-TEST1.", filed.speech)
        assertTrue(filed.delivered)
        assertEquals(listOf("RPT-TEST1"), f.repository.saved.map { it.first })
        assertEquals(listOf("RPT-TEST1"), f.submitter.sent)
    }

    @Test
    fun modelSubmitReportCallIsNeverAUserConfirmation() = runBlocking {
        val sneaky = WATER_LEAK_EXTRACTION + "\n<tool_call>{\"tool\":\"submit_report\",\"arguments\":{}}</tool_call>"
        val llm = ScriptedLlm.of(
            sneaky,
            "<tool_call>{\"tool\":\"submit_report\",\"arguments\":{\"summary\":\"done\"}}</tool_call>",
        )
        val f = SessionFixture(llm)
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Medium")
        f.session.onUtterance("No")
        f.session.onUtterance("No")
        // In confirmation, an utterance that is not a clear yes goes to the model, which
        // "decides" to submit: still not filed.
        val reply = f.session.onUtterance("go on then do what you have to")
        assertEquals(SessionPhase.CONFIRMING, reply.phase)
        assertTrue(f.repository.saved.isEmpty())
        assertTrue(f.submitter.sent.isEmpty())
    }

    @Test
    fun declineStaysInReviewAndACorrectionIsReadBackAgain() = runBlocking {
        val llm = ScriptedLlm.of(
            WATER_LEAK_EXTRACTION,
            recordFields(U("severity", "high", 0.9, "make it high", "answered")),
        )
        val f = SessionFixture(llm)
        f.toConfirmation()
        assertEquals("What should I change?", f.session.onUtterance("No").speech)
        val reread = f.session.onUtterance("Make the severity high")
        assertEquals(SessionPhase.CONFIRMING, reread.phase)
        assertTrue(reread.speech.contains("Severity: High."))
        assertTrue(f.repository.saved.isEmpty())
    }

    @Test
    fun secondYesDoesNotFileTwice() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.toConfirmation()
        f.session.onUtterance("yes")
        assertEquals("Already filed RPT-TEST1.", f.session.onUtterance("yes").speech)
        assertEquals("Already filed RPT-TEST1.", f.session.submitConfirmedByUser().speech)
        assertEquals(1, f.repository.saved.size)
        assertEquals(1, f.submitter.sent.size)
    }

    @Test
    fun deliveryFailureIsNotAnnouncedAsFiled() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION), submitter = RecordingSubmitter(ok = false))
        f.toConfirmation()
        val reply = f.session.onUtterance("yes")
        assertFalse(reply.delivered)
        assertEquals("Saved RPT-TEST1, not delivered.", reply.speech)
    }

    @Test
    fun localSaveFailureKeepsTheDraftAndSaysSo() = runBlocking {
        val f = SessionFixture(
            ScriptedLlm.of(WATER_LEAK_EXTRACTION),
            repository = RecordingRepository(failWith = IllegalStateException("disk full")),
        )
        f.toConfirmation()
        val reply = f.session.onUtterance("yes")
        assertEquals("Save failed.", reply.speech)
        assertEquals(SessionPhase.CONFIRMING, reply.phase)
        assertTrue(f.submitter.sent.isEmpty())
    }

    @Test
    fun submitButtonStillRespectsTheMandatoryGate() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.submitConfirmedByUser()
        assertEquals("Missing: Severity. $severityQuestion", reply.speech)
        assertTrue(f.repository.saved.isEmpty())
    }

    @Test
    fun reportJsonCarriesValuesProvenanceSkipsAndMetrics() = runBlocking {
        val f = SessionFixture(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("I don't know")
        f.session.onUtterance("yes")
        f.session.onUtterance("No")
        f.session.onUtterance("No")
        f.session.onUtterance("yes")

        val root = Json.parseToJsonElement(f.repository.saved.single().second).jsonObject
        val fields = root["fields"]!!.jsonObject
        assertEquals("water_leak", fields["incident_type"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("unknown", fields["severity"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("skipped", fields["witnesses"]!!.jsonObject["evidence"]!!.jsonPrimitive.content)
        // A required field the user could not answer is flagged for the recipient.
        assertEquals(listOf("severity"), root["missing_information"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Metrics are captured when the report is built, i.e. the turns before the filing "yes".
        assertEquals("5", root["metrics"]!!.jsonObject["turns"]!!.jsonPrimitive.content)
        assertEquals("1", root["metrics"]!!.jsonObject["llm_calls"]!!.jsonPrimitive.content)
    }

    // ---------- concurrency ----------

    @Test
    fun overlappingUtterancesNeverRunTheModelConcurrently() = runBlocking {
        val llm = ScriptedLlm { _, i ->
            delay(50)
            if (i == 0) WATER_LEAK_EXTRACTION else recordFields(U("severity", "low"))
        }
        val f = SessionFixture(llm)
        listOf(
            async { f.session.onUtterance(WATER_LEAK) },
            async { f.session.onUtterance("it is pretty low I suppose") },
        ).awaitAll()
        assertEquals(1, llm.maxConcurrent)
        assertEquals(2, llm.calls)
        assertEquals("low", f.session.form.text("severity"))
    }
}
