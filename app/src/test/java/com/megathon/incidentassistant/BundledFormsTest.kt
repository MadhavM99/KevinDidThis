package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FilledFormRenderer
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.HtmlFormParser
import com.megathon.incidentassistant.session.PREFILL_EMAIL
import com.megathon.incidentassistant.session.PREFILL_PHONE
import com.megathon.incidentassistant.session.PREFILL_REPORTER
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The incident forms in forms/ (form1 … form4): all generic, offline, and different enough. */
class BundledFormsTest {

    private val dir = listOf(File("../forms"), File("forms")).first { it.isDirectory }
    private val files = (1..4).map { File(dir, "form$it.html") }
    private val forms: Map<String, FormSchema> =
        files.associate { it.name to HtmlFormParser.parse(it.readText(), it.nameWithoutExtension) }

    @Test
    fun formsComeInTheRequestedFlavours() {
        val text = files.associate { it.name to it.readText() }
        assertFalse("<style" in text.getValue("form1.html")) // plain HTML
        assertTrue("<style" in text.getValue("form2.html")) // HTML + CSS
        assertFalse("<script" in text.getValue("form2.html"))
        for (name in listOf("form3.html", "form4.html")) { // HTML + CSS + JS
            assertTrue(name, "<style" in text.getValue(name))
            assertTrue(name, "<script" in text.getValue(name))
        }
    }

    @Test
    fun eachIsAGenericIncidentFormWithMandatoryFields() {
        for ((name, form) in forms) {
            assertTrue("$name: ${form.title}", Regex("Incident").containsMatchIn(form.title))
            assertTrue("$name: ${form.fields.size} fields", form.fields.size >= 12)
            assertTrue(name, form.requiredFields.size >= 4)
            // The common ground of any incident report: when, where, what happened, who reports.
            assertTrue(name, form.fields.any { it.type == FieldType.DATETIME && it.required })
            assertTrue(name, form.fields.any { it.type == FieldType.TEXTAREA && it.required })
            assertTrue(name, form.fields.none { it.label.isBlank() })
        }
    }

    @Test
    fun atLeastFortyPercentOfEachFormsQuestionsAreItsOwn() {
        for ((name, form) in forms) {
            val others = forms.filterKeys { it != name }.values.flatMap { f -> f.fields.map { it.id } }.toSet()
            val own = form.fields.count { it.id !in others }
            assertTrue("$name: $own of ${form.fields.size} unique", own * 10 >= form.fields.size * 4)
        }
    }

    @Test
    fun noFormLoadsAnythingFromOutside() {
        for (file in files) {
            val text = file.readText()
            for (banned in listOf("http://", "https://", "<link", "@import", "url(", "src=")) {
                assertFalse("${file.name} uses $banned", text.contains(banned, ignoreCase = true))
            }
        }
    }

    @Test
    fun eachRendersFilledInWithItsFieldsVisibleAndASubmitButton() {
        for ((name, form) in forms) {
            val values = form.fields.associate { f ->
                f.id to (f.options.firstOrNull()?.value ?: if (f.type == FieldType.DATETIME) "2026-10-11T10:00" else "1")
            }
            val page = Jsoup.parse(FilledFormRenderer.render(form.sourceHtml!!, values))
            assertEquals(name, 1, page.select("script").size) // only the app's bridge script
            assertTrue(name, page.select("form button[type=submit], form button:not([type])").isNotEmpty())
            // Nothing starts hidden in the markup: without the page's scripts, every answer shows.
            assertTrue(name, page.select("form [hidden], form .hidden").isEmpty())
        }
    }

    @Test
    fun theFormsUseTheDifferentKindsOfDateAndTimeInputs() {
        val inputs = forms.values.flatMap { f -> f.fields.mapNotNull { it.input } }.toSet()
        assertTrue(inputs.containsAll(listOf("date", "time", "datetime-local", "month", "week", "number", "email", "tel")))
        assertEquals("date", forms.getValue("form2.html").field("event_date")!!.input)
        assertEquals("time", forms.getValue("form2.html").field("event_time")!!.input)
    }

    @Test
    fun scriptsAndButtonsAreNotQuestions() {
        val form3 = forms.getValue("form3.html")
        assertNull(form3.field("now")) // a type=button
        assertEquals("How likely is it to happen again?", form3.field("likelihood")!!.label)
        assertEquals(FieldType.STRING_LIST, form3.field("contributing_factors")!!.type)
        assertEquals(PREFILL_REPORTER, form3.field("reported_by")!!.prefill)
        val form4 = forms.getValue("form4.html")
        assertEquals(PREFILL_REPORTER, form4.field("submitter_name")!!.prefill)
        assertEquals(PREFILL_EMAIL, form4.field("submitter_email")!!.prefill)
        assertEquals(PREFILL_PHONE, form4.field("follow_up_phone")!!.prefill)
        assertEquals(FieldType.BOOLEAN, form4.field("insurance_claim")!!.type)
    }
}
