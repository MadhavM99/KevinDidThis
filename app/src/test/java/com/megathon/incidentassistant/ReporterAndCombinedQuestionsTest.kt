package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.ExtractionPromptBuilder
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.form.HtmlFormParser
import com.megathon.incidentassistant.session.FormInterpreter
import com.megathon.incidentassistant.form.UpdateStatus
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.UserIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reporter prefill from a trusted identity, and combined questions for missing fields. */
class ReporterAndCombinedQuestionsTest {

    /** The sample HTML form, with its reporter field found by the model at load. */
    private val appForm: FormSchema = runBlocking {
        FormInterpreter(ScriptedLlm.of("name: reported_by\nemail: none\nphone: none"))
            .interpret(HtmlFormParser.parse(testFormPage("sample_incident.html")))
    }
    private val alice = UserIdentity("Alice Rao", source = "device management profile")
    private val severityQuestion = "How severe is it — low, medium, high, or critical?"

    private fun session(llm: ScriptedLlm, schema: FormSchema = appForm, reporter: UserIdentity? = alice) =
        IncidentSession(
            schema = schema,
            extraction = ExtractionEngine(llm, now = { NOW }),
            phrases = TestPhrases(),
            repository = RecordingRepository(),
            submitter = RecordingSubmitter(),
            now = { NOW },
            newReportId = { "RPT-1" },
            reporter = reporter,
        )

    private val leakNearRoom2 = recordFields(
        U("incident_type", "water_leak", 0.95, "water leak"),
        U("location", "Conference Room 2", 0.9, "near Conference Room 2"),
        U("description", "Water leak near Conference Room 2.", 0.8, "There was a water leak near Conference Room 2"),
    )

    // ---------- Change 1: reporter ----------

    @Test
    fun knownUserIsTheReporterFromTheStart() {
        val s = session(ScriptedLlm.of())
        val reporter = s.form.get("reported_by")
        assertEquals("Alice Rao", reporter.value)
        assertEquals(FieldStatus.FILLED, reporter.status)
        assertEquals("signed-in user (device management profile)", reporter.evidence)
        assertTrue(s.form.isAppProvided("reported_by"))
        // Separate from the incident's people.
        assertEquals(FieldStatus.EMPTY, s.form.get("persons_involved").status)
        assertEquals(FieldStatus.EMPTY, s.form.get("witnesses").status)
    }

    @Test
    fun theReporterIsNeverAskedWhenKnown() = runBlocking {
        val s = session(ScriptedLlm.of(leakNearRoom2))
        val first = s.onUtterance("There was a water leak near Conference Room 2.")
        assertEquals(severityQuestion, first.speech)
        val second = s.onUtterance("Medium")
        assertFalse(second.speech.contains("who is reporting"))
        assertFalse("reported_by" in second.pendingFieldIds)
    }

    @Test
    fun theModelCannotOverwriteTheAppProvidedReporter() = runBlocking {
        val s = session(ScriptedLlm.of(recordFields(U("reported_by", "John", 0.99, "John reported it", "answered"))))
        s.onUtterance("John reported it to me")
        assertEquals("Alice Rao", s.form.text("reported_by"))
        assertTrue(
            ExtractionPromptBuilder.systemPrompt(appForm, s.form, NOW)
                .contains("reported_by: Alice Rao (set by the app: never output this field)")
        )
    }

    @Test
    fun noTrustedIdentityMeansNothingIsAssumedAndTheFieldIsAsked() = runBlocking {
        val s = session(ScriptedLlm.of(leakNearRoom2), reporter = null)
        assertEquals(FieldStatus.EMPTY, s.form.get("reported_by").status)
        // Without a profile nothing is assumed: reported_by stays a normal required field and
        // is asked in its turn (last on this form) — the app shows the profile screen first,
        // so this only happens on devices without one.
        val reply = s.onUtterance("There was a water leak near Conference Room 2.")
        assertEquals(severityQuestion, reply.speech)
        assertEquals(FieldStatus.EMPTY, s.form.get("reported_by").status)
        assertTrue("reported_by" in s.form.missingRequired().map { it.id })
    }

    @Test
    fun formsWithoutAReporterFieldIgnoreTheIdentity() {
        val s = session(ScriptedLlm.of(), schema = incidentSchema())
        assertTrue(s.form.entries().isEmpty())
    }

    // ---------- Change 2: combined questions ----------

    @Test
    fun threeMissingOpenFieldsShareOneQuestion() = runBlocking {
        val s = session(ScriptedLlm.of(recordFields(U("incident_type", "fire"), U("severity", "high"))))
        val reply = s.onUtterance("A serious fire")
        assertEquals("TELL ME: the location | the date & time | the description?", reply.speech)
        assertEquals(listOf("location", "occurred_at", "description"), reply.pendingFieldIds)
    }

    @Test
    fun oneAnswerFillsSeveralFieldsThenOnlyTheRestIsAsked() = runBlocking {
        val llm = ScriptedLlm.of(
            leakNearRoom2,
            recordFields(
                U("occurred_at", "2026-10-10T10:00:00", 0.7, "around 10 AM"),
                U("immediate_actions", "Area isolated by maintenance", 0.9, "Maintenance has isolated the area"),
            ),
        )
        val s = session(llm)
        assertEquals(severityQuestion, s.onUtterance("There was a water leak near Conference Room 2.").speech)

        // Severity (closed answer) was asked alone; the open fields come together.
        val together = s.onUtterance("Medium")
        assertEquals(
            "TELL ME: the date & time | the immediate actions taken?",
            together.speech,
        )

        val reply = s.onUtterance("Around 10 AM. Maintenance has isolated the area.")
        assertTrue(llm.prompts[1].contains("expects answers for field ids=occurred_at,immediate_actions"))
        assertEquals(listOf("occurred_at", "immediate_actions"), reply.changedFieldIds)
        // Answers to the question asked are explicit, with their own evidence.
        assertEquals(FieldStatus.FILLED, s.form.get("occurred_at").status)
        assertEquals("Maintenance has isolated the area", s.form.get("immediate_actions").evidence)
        assertTrue(s.form.gatePasses())
        assertEquals(SessionPhase.OPTIONAL, reply.phase)
    }

    @Test
    fun aPartlyAnsweredCombinedQuestionAsksOnlyWhatIsLeftPlainly() = runBlocking {
        val llm = ScriptedLlm.of(
            recordFields(U("incident_type", "fire"), U("severity", "high")),
            recordFields(U("location", "Lab 3", 0.9, "in Lab 3"), U("occurred_at", "2026-10-10T09:00:00", 0.9, "at nine")),
        )
        val s = session(llm)
        s.onUtterance("A serious fire")
        val reply = s.onUtterance("In Lab 3 at nine")
        // Not a failed answer: no "sorry, I didn't catch that", just the remaining fields.
        assertEquals("TELL ME: the description | the immediate actions taken?", reply.speech)
        assertFalse(reply.speech.contains("where it happened"))
    }

    @Test
    fun closedAnswerAndAskAloneFieldsAreNeverCombined() = runBlocking {
        val schema = FormSchemaParser.parse(
            """{"id":"x","fields":[
              {"id":"where","label":"Where","required":true,"ask_phrase":"where it was"},
              {"id":"hazard","label":"Hazard","required":true,"ask_alone":true,"question":"Is anyone still in danger?"},
              {"id":"when","label":"When","type":"datetime","required":true,"ask_phrase":"when it was"},
              {"id":"level","label":"Level","type":"enum","required":true,"question":"Low or high?",
               "options":[{"value":"low"},{"value":"high"}]},
              {"id":"what","label":"What","type":"textarea","required":true}]}"""
        )
        val s = session(ScriptedLlm { _, _ -> recordFields() }, schema = schema, reporter = null)
        val first = s.onUtterance("something happened at the depot")
        // ask_alone and the enum are skipped over; the label is the fallback phrase.
        assertEquals("TELL ME: where it was | when it was | the what?", first.speech)
        assertEquals(listOf("where", "when", "what"), first.pendingFieldIds)
    }

    @Test
    fun unansweredCombinedQuestionFallsBackToFocusedQuestions() = runBlocking {
        val s = session(ScriptedLlm { _, i -> if (i == 0) recordFields(U("incident_type", "fire"), U("severity", "high")) else recordFields() })
        s.onUtterance("A serious fire")
        val reply = s.onUtterance("hmm let me think")
        assertEquals("AGAIN: Where did it happen?", reply.speech) // bounded, focused, one field
    }

    @Test
    fun dontKnowToACombinedQuestionAsksEachPartOnItsOwn() = runBlocking {
        val s = session(ScriptedLlm.of(recordFields(U("incident_type", "fire"), U("severity", "high"))))
        s.onUtterance("A serious fire")
        val reply = s.onUtterance("I don't know")
        assertEquals("Where did it happen?", reply.speech)
        // ...where "I don't know" now has a clear target.
        assertEquals("Mark Location as not specified?", s.onUtterance("not sure").speech)
    }

    @Test
    fun filledFieldsAreNeverAskedAgain() = runBlocking {
        val s = session(
            ScriptedLlm.of(
                recordFields(
                    U("incident_type", "fire"), U("severity", "high"), U("location", "Lab 3"),
                    U("description", "Bin fire in Lab 3"),
                ),
            ),
        )
        val reply = s.onUtterance("A serious bin fire in Lab 3")
        assertEquals(listOf("occurred_at", "immediate_actions"), reply.pendingFieldIds)
    }

    @Test
    fun offTopicKeepsTheCombinedQuestionPending() = runBlocking {
        val s = session(
            ScriptedLlm.of(
                recordFields(U("incident_type", "fire"), U("severity", "high")),
                """<tool_call>{"tool":"off_topic","arguments":{}}</tool_call>""",
            ),
        )
        val asked = s.onUtterance("A serious fire")
        val before = s.form.entries()
        val reply = s.onUtterance("Is the canteen open today?")
        assertEquals("REDIRECT. ${asked.speech}", reply.speech)
        assertEquals(asked.pendingFieldIds, reply.pendingFieldIds)
        assertEquals(before, s.form.entries())
    }

    @Test
    fun appProvidedValueStillPassesTheSchemaValidation() {
        // An identity that is not a valid value for the field is not written.
        val schema = FormSchemaParser.parse(
            """{"id":"x","fields":[{"id":"who","type":"number","required":true,"prefill":"reporter"}]}"""
        )
        val s = session(ScriptedLlm.of(), schema = schema)
        assertEquals(FieldStatus.EMPTY, s.form.get("who").status)
        assertFalse(s.form.isAppProvided("who"))
        // ...and a normal update path still works for it.
        assertTrue(s.form.apply("who", "42", 0.9, null, UpdateStatus.ANSWERED).accepted)
    }
}
