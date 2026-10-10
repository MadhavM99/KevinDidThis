package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.ExtractionPromptBuilder
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormDocuments
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.HtmlFormParser
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.PREFILL_EMAIL
import com.megathon.incidentassistant.session.PREFILL_REPORTER
import com.megathon.incidentassistant.session.ReporterFieldClassifier
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.UserIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTML form page → the schema the assistant fills; nothing about incidents is in the parser. */
class HtmlFormParserTest {

    private val html = formFixture("incident_report.html").readText()
    private val schema = HtmlFormParser.parse(html)

    @Test
    fun readsFieldsInPageOrderWithMandatoryFlagsFromRequired() {
        assertEquals("incident_report", schema.id)
        assertEquals("Incident Report", schema.title)
        assertEquals(
            listOf(
                "incident_type", "severity", "location", "occurred_at", "description",
                "immediate_actions", "persons_involved", "witnesses",
                "equipment_involved", "recommended_actions", "action_status",
                "reported_by", "reporter_email",
            ),
            schema.fields.map { it.id },
        )
        assertEquals(
            listOf("incident_type", "severity", "location", "occurred_at", "description", "immediate_actions", "reported_by"),
            schema.requiredFields.map { it.id },
        )
        // Optional fields are asked once, never insisted on.
        assertTrue(schema.fields.filterNot { it.required }.all { it.askOnce })
    }

    @Test
    fun mapsControlsToAnswerTypesAndChoices() {
        val type = schema.field("incident_type")!!
        assertEquals(FieldType.ENUM, type.type)
        assertEquals(9, type.options.size) // the empty "Select…" prompt is not an answer
        assertEquals("water_leak", type.options.first().value)
        assertEquals("Water leak", type.options.first().label)

        val severity = schema.field("severity")!!
        assertEquals(FieldType.ENUM, severity.type)
        assertEquals("Severity", severity.label) // radio group → its legend
        assertEquals(listOf("low", "medium", "high", "critical"), severity.options.map { it.value })
        assertEquals(listOf("Low", "Medium", "High", "Critical"), severity.options.map { it.label })
        assertTrue(severity.required)

        assertEquals(FieldType.DATETIME, schema.field("occurred_at")!!.type)
        assertEquals(FieldType.TEXTAREA, schema.field("description")!!.type)
        assertEquals(FieldType.STRING_LIST, schema.field("witnesses")!!.type)
        assertEquals("open", schema.field("action_status")!!.defaultValue)
    }

    @Test
    fun questionsHintsAndPrefillComeFromStandardAttributes() {
        assertEquals("Where did it happen?", schema.field("location")!!.question) // title
        assertEquals("Building, floor, room or area", schema.field("location")!!.hint) // placeholder
        assertTrue(schema.field("immediate_actions")!!.hint!!.contains("first aid given")) // aria-describedby
        // Nothing in the page marks the reporter's own fields: that is the model's job at load.
        assertTrue(schema.fields.all { it.prefill == null })
    }

    @Test
    fun optionalFieldsInAFieldsetAreAskedTogether() {
        assertEquals(listOf("people", "followup"), schema.questionGroups.map { it.id })
        assertEquals(listOf("persons_involved", "witnesses"), schema.group("people")!!.fields)
        assertEquals(listOf("equipment_involved", "recommended_actions", "action_status"), schema.group("followup")!!.fields)
        assertNull(schema.group("people")!!.prompt) // worded from the labels at runtime
    }

    @Test
    fun noHandWrittenKeywordsOrAliasesAreNeeded() {
        assertTrue(schema.fields.all { it.keywords.isEmpty() && it.askPhrase == null })
        assertTrue(schema.fields.flatMap { it.options }.all { it.aliases.isEmpty() })
    }

    @Test
    fun theExtractionPromptCarriesWhatTheModelNeedsToFillTheForm() {
        val prompt = ExtractionPromptBuilder.systemPrompt(schema, FormState(schema), "2026-10-11T10:00:00")
        assertTrue(prompt.contains("id=incident_type | label=\"Incident type\" | type=enum | REQUIRED | options=water_leak (Water leak)"))
        assertTrue(prompt.contains("id=location | label=\"Location\" | type=text | REQUIRED | hint=\"Building, floor, room or area\""))
        assertTrue(prompt.contains("id=witnesses | label=\"Witnesses\" | type=string_list"))
        assertFalse(prompt.contains("keywords="))
    }

    @Test
    fun anyOtherFormWorksTheSameWay() {
        val other = """
            <html><head><title>Vehicle check</title></head><body>
            <form>
              <label>Registration <input name="plate" required placeholder="e.g. KA01AB1234"></label>
              <input name="odometer" type="number" aria-label="Odometer reading" required>
              <fieldset><legend>Tyres OK?</legend>
                <input type="radio" id="t1" name="tyres" value="yes"><label for="t1">Yes</label>
                <input type="radio" id="t2" name="tyres" value="no"><label for="t2">No</label>
              </fieldset>
              <fieldset><legend>Damage</legend>
                <label><input type="checkbox" name="damage" value="scratch"> Scratch</label>
                <label><input type="checkbox" name="damage" value="dent"> Dent</label>
              </fieldset>
              <label><input type="checkbox" name="cleaned"> Vehicle cleaned</label>
              <label>Driver <input name="driver" autocomplete="name"></label>
              <label>Contact <input name="contact" type="email" autocomplete="work email"></label>
              <input type="hidden" name="csrf" value="x">
              <input name="notes_internal" disabled>
              <input type="submit" value="Send">
            </form></body></html>
        """.trimIndent()
        val s = HtmlFormParser.parse(other, fallbackId = "vehicle_check")

        assertEquals("vehicle_check", s.id)
        assertEquals("Vehicle check", s.title)
        assertEquals(listOf("plate", "odometer", "tyres", "damage", "cleaned", "driver", "contact"), s.fields.map { it.id })
        // HTML autocomplete tokens name the user's own details: filled from the profile.
        assertEquals(PREFILL_REPORTER, s.field("driver")!!.prefill)
        assertEquals(PREFILL_EMAIL, s.field("contact")!!.prefill)
        assertEquals(listOf("plate", "odometer"), s.requiredFields.map { it.id })
        assertEquals("Registration", s.field("plate")!!.label)
        assertEquals("e.g. KA01AB1234", s.field("plate")!!.hint)
        assertEquals("Odometer reading", s.field("odometer")!!.label)
        assertEquals(FieldType.NUMBER, s.field("odometer")!!.type)
        assertEquals("Tyres OK?", s.field("tyres")!!.label)
        assertEquals(listOf("Yes", "No"), s.field("tyres")!!.options.map { it.label })
        assertEquals(FieldType.STRING_LIST, s.field("damage")!!.type)
        assertEquals(listOf("scratch", "dent"), s.field("damage")!!.options.map { it.value })
        assertEquals(FieldType.BOOLEAN, s.field("cleaned")!!.type)
        assertEquals("Vehicle cleaned", s.field("cleaned")!!.label)
    }

    @Test
    fun formDocumentsReadsHtmlOrJson() {
        assertEquals(schema, FormDocuments.parse("\n  $html", "x"))
        val json = FormDocuments.parse(formFixture("incident_report.json").readText(), "x")
        assertEquals("incident_report", json.id)
        assertTrue(json.fields.first { it.id == "incident_type" }.keywords.isNotEmpty())
    }

    @Test
    fun aConversationOnTheHtmlFormFillsTheMandatoryFieldsAndFilesOnlyAfterYes() = runBlocking {
        // Form load: the model points out the reporter's own fields.
        val classifier = ScriptedLlm.of("""{"name": "reported_by", "email": "reporter_email", "phone": null}""")
        val form = ReporterFieldClassifier(classifier).classify(schema)
        assertEquals(1, classifier.calls)

        val llm = ScriptedLlm.of(
            recordFields(
                U("incident_type", "water_leak", 0.95, "water leak"),
                U("location", "Conference Room 2", 0.9, "near Conference Room 2"),
                U("occurred_at", "2026-10-11T10:00:00", 0.8, "around 10 AM"),
                U("description", "Water leak near Conference Room 2.", 0.8, "a water leak near Conference Room 2"),
                U("immediate_actions", "Area isolated by maintenance", 0.9, "maintenance isolated the area"),
            ),
        )
        val repository = RecordingRepository()
        val session = IncidentSession(
            schema = form,
            extraction = ExtractionEngine(llm, now = { NOW }),
            phrases = TestPhrases(),
            repository = repository,
            submitter = RecordingSubmitter(),
            now = { NOW },
            newReportId = { "RPT-HTML1" },
            reporter = UserIdentity("Asha Rao", "app profile", email = "asha@example.com"),
        )
        // Filled from the profile up front, so never asked.
        assertEquals("Asha Rao", session.form.text("reported_by"))
        assertEquals("asha@example.com", session.form.text("reporter_email"))

        val first = session.onUtterance(
            "There was a water leak near Conference Room 2 around 10 AM, maintenance isolated the area.",
        )
        // The only mandatory field still open is asked with the form's own wording.
        assertEquals(SessionPhase.COLLECTING, first.phase)
        assertEquals(listOf("severity"), first.pendingFieldIds)
        assertEquals("How severe is it — low, medium, high, or critical?", first.speech)
        assertTrue(llm.prompts.single().contains("id=severity"))

        val asked = mutableListOf(first.pendingFieldIds)
        var reply = session.onUtterance("Medium.") // exact option label → no model call
        asked += reply.pendingFieldIds
        assertEquals("medium", session.form.text("severity"))
        assertTrue(session.form.gatePasses())
        // Optional fieldsets are offered once, then the review; nothing is filed yet.
        while (reply.kind != ReplyKind.CONFIRM) {
            assertEquals(0, repository.saved.size)
            reply = session.onUtterance("No.")
            asked += reply.pendingFieldIds
        }
        assertEquals(SessionPhase.CONFIRMING, reply.phase)
        assertEquals(0, repository.saved.size)
        val filed = session.onUtterance("Yes.")
        assertEquals(SessionPhase.SUBMITTED, filed.phase)
        assertTrue(repository.saved.single().second.contains("Asha Rao"))
        assertTrue(asked.none { "reported_by" in it || "reporter_email" in it })
        assertEquals(1, repository.saved.size)
        assertEquals(1, llm.calls)
    }
}
