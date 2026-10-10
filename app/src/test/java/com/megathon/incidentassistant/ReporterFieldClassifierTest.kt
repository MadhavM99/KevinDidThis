package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.LlmException
import com.megathon.incidentassistant.form.HtmlFormParser
import com.megathon.incidentassistant.session.PREFILL_EMAIL
import com.megathon.incidentassistant.session.PREFILL_PHONE
import com.megathon.incidentassistant.session.PREFILL_REPORTER
import com.megathon.incidentassistant.session.ReporterFieldClassifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which fields are the reporter's own details: from the form when it says, else the model. */
class ReporterFieldClassifierTest {

    private val form = HtmlFormParser.parse(formFixture("incident_report.html").readText())

    @Test
    fun theModelsPicksBecomeProfileFilledFields() = runBlocking {
        val llm = ScriptedLlm.of("""Sure. {"name":"reported_by","email":"reporter_email","phone":null}""")
        val out = ReporterFieldClassifier(llm).classify(form)
        assertEquals(PREFILL_REPORTER, out.field("reported_by")!!.prefill)
        assertEquals(PREFILL_EMAIL, out.field("reporter_email")!!.prefill)
        assertEquals(2, out.fields.count { it.prefill != null }) // exactly these two
        // The model only sees the fields' own words; nothing app-side names them.
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("id=reported_by | label=\"Reported by\""))
        assertTrue(prompt.contains("id=location | label=\"Location\""))
        assertFalse(prompt.contains("id=witnesses")) // lists / choices are never the reporter
    }

    @Test
    fun aFormThatMarksItsReporterFieldsIsTakenAtItsWord() = runBlocking {
        val marked = HtmlFormParser.parse(
            """<form><input name="who" autocomplete="name"><input name="where" required></form>""",
        )
        val llm = ScriptedLlm.of()
        val out = ReporterFieldClassifier(llm).classify(marked)
        assertSame(marked, out)
        assertEquals(0, llm.calls)
        assertEquals(PREFILL_REPORTER, out.field("who")!!.prefill)
    }

    @Test
    fun invalidPicksAreIgnored() = runBlocking {
        val llm = ScriptedLlm.of("""{"name":"made_up_field","email":"witnesses","phone":"location"}""")
        val out = ReporterFieldClassifier(llm).classify(form)
        assertNull(out.field("witnesses")!!.prefill) // not a candidate (a list)
        assertEquals(PREFILL_PHONE, out.field("location")!!.prefill) // a candidate: trusted
        assertTrue(out.fields.none { it.id == "made_up_field" })
    }

    @Test
    fun anyFailureLeavesTheFormAsItIsAndTheFieldsAreAskedNormally() = runBlocking {
        val garbage = ReporterFieldClassifier(ScriptedLlm.of("I think it's the first one.")).classify(form)
        assertEquals(form, garbage)

        val broken = ReporterFieldClassifier(ScriptedLlm { _, _ -> throw LlmException("not loaded") }).classify(form)
        assertEquals(form, broken)

        val slow = ReporterFieldClassifier(ScriptedLlm { _, _ -> delay(5_000); "{}" }, timeoutMs = 50).classify(form)
        assertEquals(form, slow)
    }

    @Test
    fun theSameFormIsClassifiedOnlyOnce() = runBlocking {
        val llm = ScriptedLlm.of("""{"name":"reported_by","email":null,"phone":null}""")
        val classifier = ReporterFieldClassifier(llm)
        val first = classifier.classify(form)
        val again = classifier.classify(form) // e.g. "Refresh form" with an unchanged page
        assertEquals(first, again)
        assertEquals(1, llm.calls)
    }
}
