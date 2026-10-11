package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.ExtractionPromptBuilder
import com.megathon.incidentassistant.form.FieldFormats
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FilledFormRenderer
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.HtmlFormParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Date and time answers are kept in each field's own HTML format and shown readably. */
class FieldFormatsTest {

    private fun field(input: String?) = FormField(id = "f", type = FieldType.DATETIME, input = input)

    @Test
    fun anyReadableValueIsWrittenInTheFieldsOwnFormat() {
        val model = "2026-10-11T10:05:00+05:30"
        assertEquals("2026-10-11", FieldFormats.normalize(field("date"), model))
        assertEquals("10:05", FieldFormats.normalize(field("time"), model))
        assertEquals("2026-10-11T10:05", FieldFormats.normalize(field("datetime-local"), model))
        assertEquals("2026-10", FieldFormats.normalize(field("month"), model))
        assertEquals("2026-W41", FieldFormats.normalize(field("week"), model))
        // Date-only answer for a date-time field: midnight, still a valid input value.
        assertEquals("2026-10-11T00:00", FieldFormats.normalize(field("datetime-local"), "2026-10-11"))
        // Picker / input formats read back.
        assertEquals("2026-10-01", FieldFormats.normalize(field("date"), "2026-10"))
        assertEquals("2026-10-05", FieldFormats.normalize(field("date"), "2026-W41")) // its Monday
        assertEquals("09:30", FieldFormats.normalize(field("time"), "09:30:00"))
        assertEquals("2026-10-11T10:00", FieldFormats.normalize(field(null), "2026-10-11 10:00"))
    }

    @Test
    fun unreadableAnswersAreKeptAsSaid() {
        assertEquals("last night", FieldFormats.normalize(field("date"), "last night"))
        assertEquals("2026-10-11", FieldFormats.normalize(field("time"), "2026-10-11")) // no time in it
        assertEquals("last night", FieldFormats.display(field("date"), "last night"))
    }

    @Test
    fun valuesAreShownForPeople() {
        assertEquals("Sun, 11 Oct 2026 · 10:05", FieldFormats.display(field("datetime-local"), "2026-10-11T10:05:00+05:30"))
        assertEquals("Sun, 11 Oct 2026", FieldFormats.display(field("date"), "2026-10-11"))
        assertEquals("10:05", FieldFormats.display(field("time"), "10:05"))
        assertEquals("October 2026", FieldFormats.display(field("month"), "2026-10"))
        assertEquals("Week 41, 2026", FieldFormats.display(field("week"), "2026-W41"))
    }

    @Test
    fun aDateFieldStoresADateWhateverTheModelWrote() {
        val form = FormSchema(id = "f", fields = listOf(field("date").copy(id = "day")))
        val state = FormState(form)
        state.setDirect("day", "2026-10-11T10:00:00+05:30")
        assertEquals("2026-10-11", state.text("day"))
    }

    @Test
    fun theFilledPageGetsValidValuesForEveryDateInput() {
        val page = """<form>
            <input name="d" type="date"><input name="t" type="time"><input name="dt" type="datetime-local">
            <input name="m" type="month"><input name="w" type="week"></form>"""
        val model = "2026-10-11T10:05:00+05:30"
        val out = Jsoup.parse(FilledFormRenderer.render(page, listOf("d", "t", "dt", "m", "w").associateWith { model }))
        assertEquals("2026-10-11", out.selectFirst("[name=d]")!!.attr("value"))
        assertEquals("10:05", out.selectFirst("[name=t]")!!.attr("value"))
        assertEquals("2026-10-11T10:05", out.selectFirst("[name=dt]")!!.attr("value"))
        assertEquals("2026-10", out.selectFirst("[name=m]")!!.attr("value"))
        assertEquals("2026-W41", out.selectFirst("[name=w]")!!.attr("value"))
    }

    @Test
    fun theModelIsToldEachDateFieldsFormat() {
        val form = HtmlFormParser.parse(
            """<form><input name="day" type="date" required><input name="at" type="time"><input name="m" type="month"></form>""",
        )
        assertEquals("date", form.field("day")!!.input)
        val prompt = ExtractionPromptBuilder.systemPrompt(form, FormState(form), "2026-10-11T10:00:00+05:30")
        assertTrue(prompt.contains("id=day | label=\"Day\" | type=datetime | format=YYYY-MM-DD"))
        assertTrue(prompt.contains("format=HH:MM"))
        assertTrue(prompt.lines().any { it.endsWith("format=YYYY-MM") })
    }
}
