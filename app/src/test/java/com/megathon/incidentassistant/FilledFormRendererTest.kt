package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FilledFormRenderer
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.form.HtmlFormParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The original page, filled in with the answers, with a submit button that reaches the app. */
class FilledFormRendererTest {

    private val userPage = testFormPage("user_incident_form.html")

    @Test
    fun theUsersOwnPageComesBackFilledInWithASubmitButton() {
        val rendered = FilledFormRenderer.render(
                userPage,
                mapOf(
                    "type" to "Water leak",
                    "severity" to "medium",
                    "when" to "2026-10-11T10:00:00+05:30",
                    "where" to "Conference Room 2",
                    "reporter" to "Asha Rao",
                    "desc" to "Water leak near Conference Room 2.",
                ),
            )
        val out = Jsoup.parse(rendered)
        // The bridge script is emitted verbatim (an escaped "&gt;" would be a JS syntax error
        // and the page would navigate away instead of submitting — seen on the device).
        assertTrue(rendered.contains("group.length > 1"))
        assertFalse(rendered.contains("&gt; 1"))
        assertEquals("Water leak", out.getElementById("type")!!.attr("value"))
        assertEquals("Conference Room 2", out.getElementById("where")!!.attr("value"))
        assertEquals("Asha Rao", out.getElementById("reporter")!!.attr("value"))
        assertEquals("2026-10-11T10:00", out.getElementById("when")!!.attr("value")) // datetime-local format
        assertEquals("Water leak near Conference Room 2.", out.getElementById("desc")!!.text())
        assertEquals("Medium", out.select("#severity option[selected]").single().text())
        assertEquals("", out.getElementById("id")!!.attr("value")) // readonly field untouched

        // The page's random-data script is gone (it would overwrite the answers) …
        assertFalse(out.html().contains("function generate"))
        // … its look is kept, and the form can now be submitted to the app.
        assertTrue(out.select("style").isNotEmpty())
        assertEquals(1, out.select("form button[type=submit]").size)
        assertTrue(out.select("script").single().data().contains("${FilledFormRenderer.BRIDGE}.submit"))
        assertEquals("return false;", out.getElementById("form")!!.attr("onsubmit")) // never navigates
    }

    @Test
    fun radiosCheckboxesAndUnlistedChoicesAreShown() {
        val page = """
            <form id="f">
              <input type="radio" name="sev" value="low"><input type="radio" name="sev" value="high">
              <input type="checkbox" name="dmg" value="scratch"><input type="checkbox" name="dmg" value="dent">
              <input type="checkbox" name="ok">
              <select name="kind"><option value="a">A</option></select>
              <button>Send</button>
            </form>
        """.trimIndent()
        val out = Jsoup.parse(
            FilledFormRenderer.render(page, mapOf("sev" to "high", "dmg" to "scratch; dent", "ok" to "yes", "kind" to "Something else")),
        )
        assertTrue(out.selectFirst("input[name=sev][value=high]")!!.hasAttr("checked"))
        assertFalse(out.selectFirst("input[name=sev][value=low]")!!.hasAttr("checked"))
        assertEquals(2, out.select("input[name=dmg][checked]").size)
        assertTrue(out.selectFirst("input[name=ok]")!!.hasAttr("checked"))
        assertEquals("Something else", out.select("select[name=kind] option[selected]").single().text())
        assertEquals(1, out.select("form button").size) // the page's own submit button is reused
    }

    @Test
    fun aSpokenYesShowsTheFormAndOnlyItsSubmitFilesTheReport() = runBlocking {
        val schema = HtmlFormParser.parse(testFormPage("sample_incident.html"))
        val repository = RecordingRepository()
        val session = IncidentSession(
            schema = schema,
            extraction = ExtractionEngine(
                ScriptedLlm.of(
                    recordFields(
                        U("incident_type", "water_leak", 0.95, "water leak"),
                        U("severity", "medium", 0.9, "medium"),
                        U("location", "Conference Room 2", 0.9, "near Conference Room 2"),
                        U("occurred_at", "2026-10-11T10:00:00", 0.8, "around 10 AM"),
                        U("description", "Water leak near Conference Room 2.", 0.8, "a water leak"),
                        U("immediate_actions", "Area isolated", 0.9, "isolated the area"),
                        U("reported_by", "Asha Rao", 0.9, "I'm Asha Rao"),
                    ),
                ),
                now = { NOW },
            ),
            phrases = TestPhrases(),
            repository = repository,
            submitter = RecordingSubmitter(),
            now = { NOW },
            submitOnSpokenYes = false,
        )
        var reply = session.onUtterance("A medium water leak near Conference Room 2 around 10 AM, area isolated. I'm Asha Rao.")
        while (reply.kind != ReplyKind.CONFIRM) reply = session.onUtterance("No.")
        val yes = session.onUtterance("Yes.")
        assertEquals(ReplyKind.FORM_READY, yes.kind)
        assertEquals("FORM READY", yes.speech)
        assertEquals(0, repository.saved.size) // not filed by voice

        val filed = session.submitConfirmedByUser() // the form page's submit button
        assertEquals(SessionPhase.SUBMITTED, filed.phase)
        assertEquals(1, repository.saved.size)
    }
}
