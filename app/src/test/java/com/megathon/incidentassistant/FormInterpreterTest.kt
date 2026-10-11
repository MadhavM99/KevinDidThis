package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.LlmException
import com.megathon.incidentassistant.data.FormReadingStore
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.HtmlFormParser
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.FormInterpreter
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.PREFILL_EMAIL
import com.megathon.incidentassistant.session.PREFILL_REPORTER
import com.megathon.incidentassistant.session.UserIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model reads a new form once: its spoken questions and the reporter's own fields. */
class FormInterpreterTest {

    private val userForm = HtmlFormParser.parse(testFormPage("user_incident_form.html"))

    private val modelReading = """
        name: reporter
        email: none
        phone: none
        type: What kind of incident was it?
        severity: How severe was it — low, medium, high or critical?
        when: When did it happen?
        where: Where exactly did it happen?
        reporter: Who is reporting this?
        persons: Was anyone else involved?
        equipment: Was any equipment or asset involved?
        witness: Did anyone witness it?
        desc: Can you describe what happened?
        actions: What has been done about it so far?
        followup: What follow-up would you recommend?
    """.trimIndent()

    @Test
    fun aNewFormGetsItsQuestionsFromTheModelNotFromTheApp() = runBlocking {
        val llm = ScriptedLlm.of(modelReading)
        val form = FormInterpreter(llm).interpret(userForm)

        assertEquals("What kind of incident was it?", form.field("type")!!.question)
        assertEquals("Where exactly did it happen?", form.field("where")!!.question)
        assertEquals("Can you describe what happened?", form.field("desc")!!.question)
        // The reporter's own field is filled from the profile, so it gets no question.
        assertEquals(PREFILL_REPORTER, form.field("reporter")!!.prefill)
        assertNull(form.field("reporter")!!.question)
        // The prompt carries only what the page says about each field.
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("id=severity | label=\"Severity level\" | type=enum | required | choices=Low / Medium / High / Critical"))
        assertTrue(prompt.contains("id=where | label=\"Incident location\" | type=text | required"))
    }

    @Test
    fun theSessionAsksTheModelsQuestionsAndNeverTheReporter() = runBlocking {
        val form = FormInterpreter(ScriptedLlm.of(modelReading)).interpret(userForm)
        val llm = ScriptedLlm.of(
            recordFields(
                U("type", "Water leak", 0.95, "water leak"),
                U("where", "Conference Room 2", 0.9, "near Conference Room 2"),
                U("when", "2026-10-11T10:00", 0.8, "around 10 AM"),
                U("desc", "Water leak near Conference Room 2.", 0.8, "a water leak near Conference Room 2"),
            ),
        )
        val session = IncidentSession(
            schema = form,
            extraction = ExtractionEngine(llm, now = { NOW }),
            phrases = TestPhrases(),
            repository = RecordingRepository(),
            submitter = RecordingSubmitter(),
            now = { NOW },
            reporter = UserIdentity("Asha Rao", "app profile"),
        )
        assertEquals("Asha Rao", session.form.text("reporter"))
        val reply = session.onUtterance("There was a water leak near Conference Room 2 around 10 AM.")
        assertEquals("How severe was it — low, medium, high or critical?", reply.speech) // the model's words
    }

    @Test
    fun theFormsOwnWordingAndMarkingsWin() = runBlocking {
        val marked = HtmlFormParser.parse(
            """<form>
                 <input name="who" autocomplete="name">
                 <input name="mail" type="email" autocomplete="email">
                 <input name="where" required title="Where was it?">
                 <input name="what" required>
               </form>""",
        )
        val llm = ScriptedLlm.of("name: where\nwhere: Model question?\nwhat: What happened?")
        val form = FormInterpreter(llm).interpret(marked)
        assertEquals(PREFILL_REPORTER, form.field("who")!!.prefill)
        assertEquals(PREFILL_EMAIL, form.field("mail")!!.prefill)
        assertNull(form.field("where")!!.prefill) // the page marked its reporter: model ignored
        assertEquals("Where was it?", form.field("where")!!.question) // title kept
        assertEquals("What happened?", form.field("what")!!.question)
    }

    @Test
    fun aFullyMarkedAndWordedFormNeedsNoModelCall() = runBlocking {
        val complete = HtmlFormParser.parse(
            """<form><input name="who" autocomplete="name"><input name="where" title="Where?"></form>""",
        )
        val llm = ScriptedLlm.of()
        assertSame(complete, FormInterpreter(llm).interpret(complete))
        assertEquals(0, llm.calls)
    }

    @Test
    fun badPicksAndLinesAreIgnored() = runBlocking {
        val llm = ScriptedLlm.of(
            """
            Sure! Here you go:
            name: made_up_field
            email: severity
            ```
            - type: "What kind of incident?"
            nonsense line
            """.trimIndent(),
        )
        val form = FormInterpreter(llm).interpret(userForm)
        assertTrue(form.fields.none { it.prefill != null }) // unknown id / a choice field
        assertEquals("What kind of incident?", form.field("type")!!.question)
        assertNull(form.field("where")!!.question) // not answered → asked from its label
    }

    @Test
    fun anyFailureKeepsTheFormUsable() = runBlocking {
        assertEquals(userForm, FormInterpreter(ScriptedLlm { _, _ -> throw LlmException("not loaded") }).interpret(userForm))
        assertEquals(userForm, FormInterpreter(ScriptedLlm { _, _ -> delay(5_000); "" }, timeoutMs = 50).interpret(userForm))
    }

    @Test
    fun aSavedReadingIsReusedAcrossLaunchesUntilTheFormChanges() = runBlocking {
        val saved = mutableMapOf<String, FormSchema>()
        val store = object : FormInterpreter.Store {
            override fun load(key: String) = saved[key]
            override fun save(key: String, form: FormSchema) { saved[key] = form }
        }
        val first = FormInterpreter(ScriptedLlm.of(modelReading), store = store).interpret(userForm)

        val nextLaunch = ScriptedLlm.of()
        val reader = FormInterpreter(nextLaunch, store = store)
        assertTrue(reader.isRead(userForm))
        val again = reader.interpret(userForm)
        assertEquals(0, nextLaunch.calls) // no model run at startup
        assertEquals(first.fields, again.fields)
        assertEquals(userForm.sourceHtml, again.sourceHtml) // the page itself is not stored

        val changed = userForm.copy(fields = userForm.fields + FormField(id = "extra", label = "Extra"))
        assertFalse(reader.isRead(changed)) // a changed form is read again
        Unit
    }

    @Test
    fun aReadingSurvivesTheFileRoundTrip() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("readings").toFile()
        val first = FormInterpreter(ScriptedLlm.of(modelReading), store = FormReadingStore(dir)).interpret(userForm)
        val again = FormInterpreter(ScriptedLlm.of(), store = FormReadingStore(dir)).interpret(userForm)
        assertEquals(first, again)
        assertEquals(1, dir.listFiles()!!.size)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun aFormIsReadOnce() = runBlocking {
        val llm = ScriptedLlm.of(modelReading)
        val reader = FormInterpreter(llm)
        val first = reader.interpret(userForm)
        assertEquals(first, reader.interpret(userForm)) // "Refresh form" with the same page
        assertEquals(1, llm.calls)
    }
}
