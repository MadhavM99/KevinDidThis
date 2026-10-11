package com.megathon.incidentassistant.form

import com.megathon.incidentassistant.session.PREFILL_EMAIL
import com.megathon.incidentassistant.session.PREFILL_PHONE
import com.megathon.incidentassistant.session.PREFILL_REPORTER
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Turns an ordinary HTML `<form>` into the [FormSchema] the assistant fills.
 *
 * Only standard HTML form semantics are read — nothing about any particular form or domain
 * is known here, so any form page works:
 *
 *  - a field per control: `<input>`, `<textarea>`, `<select>`, and one per radio/checkbox
 *    `name` group. The field id is the control's `name` (else `id`).
 *  - label: `<label for=…>`, else an enclosing `<label>`, else `aria-label`, else the
 *    `<legend>` of a radio/checkbox group's `<fieldset>`, else `placeholder`, else the id.
 *  - mandatory: the `required` attribute (or `aria-required="true"`). Required fields are
 *    what the dialog insists on before the report can be submitted.
 *  - type: `<select>` and radio groups → choice with their `<option>`/radio values and
 *    visible labels; `<textarea>` → long text; `date`/`time`/`datetime-local`/`month`/`week`
 *    → date-time; `number`/`range` → number; a single checkbox → yes/no; a checkbox group or
 *    `multiple` → list; anything else → text.
 *  - question: the control's `title` (the standard advisory text), else asked from the label.
 *  - hint for the model: `placeholder` and `aria-describedby` text.
 *  - default: `value`, a `selected` option or a `checked` radio.
 *  - `autocomplete="name"` / `"email"` / `"tel"` mark the user's own details: filled from
 *    their profile instead of asked. Forms that don't mark them are read by the model
 *    when the form loads (session/FormInterpreter), which also words every question.
 *  - optional fields inside the same `<fieldset>` are asked together, once.
 *
 * Hidden, submit/button/reset/image, disabled and readonly controls are not part of the
 * report (readonly ones are filled by the page itself, e.g. a generated report number).
 * Scripts are never run: only the markup counts.
 */
object HtmlFormParser {

    fun parse(html: String, fallbackId: String = "form"): FormSchema {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form") ?: doc.body()
        val title = firstText(
            form.attr("aria-label"),
            form.selectFirst("h1, h2")?.text(),
            doc.selectFirst("h1")?.text(),
            doc.title(),
        )
        val fields = mutableListOf<FormField>()
        val seen = mutableSetOf<String>()
        val groups = linkedMapOf<String, MutableList<String>>()

        for (control in form.select("input, select, textarea")) {
            if (!isReportControl(control)) continue
            val id = control.attr("name").ifBlank { control.id() }.trim()
            if (id.isEmpty() || !seen.add(id)) continue // radio/checkbox siblings share a name

            val siblings = if (control.isChoiceInput()) {
                form.select("input[name=\"${cssEscape(id)}\"]").filter(::isReportControl)
            } else {
                listOf(control)
            }
            val field = buildField(doc, control, siblings, id)
            fields += field
            if (!field.required) {
                enclosingFieldset(control, groupedChoice = siblings.size > 1)?.let { set ->
                    groups.getOrPut(groupId(set)) { mutableListOf() } += field.id
                }
            }
        }

        // A fieldset bundles its optional fields into one question; a lone field is just asked.
        val questionGroups = groups.filterValues { it.size > 1 }
            .map { (gid, members) -> QuestionGroup(id = gid, prompt = null, fields = members) }
        val grouped = questionGroups.flatMap { g -> g.fields.map { it to g.id } }.toMap()

        return FormSchema(
            id = form.attr("id").ifBlank { form.attr("name") }.ifBlank { fallbackId },
            title = title,
            version = form.attr("data-version").ifBlank { "1.0" },
            fields = fields.map { f -> grouped[f.id]?.let { f.copy(group = it) } ?: f },
            questionGroups = questionGroups,
            sourceHtml = html,
        )
    }

    // ---------- fields ----------

    private fun buildField(doc: Document, control: Element, siblings: List<Element>, id: String): FormField {
        val tag = control.normalName()
        val inputType = control.attr("type").lowercase()
        val required = siblings.any { it.hasAttr("required") || it.attr("aria-required") == "true" }

        val type: FieldType
        var options: List<FieldOption> = emptyList()
        var default: String? = null
        when {
            tag == "select" -> {
                options = control.select("option").mapNotNull { option ->
                    val value = option.attr("value").ifBlank { option.text() }.trim()
                    // `<option value="">Select…</option>` is a prompt, not an answer.
                    if (option.hasAttr("value") && option.attr("value").isBlank()) null
                    else FieldOption(value = value, label = option.text().trim().ifBlank { value })
                }
                default = control.select("option[selected]").firstOrNull()?.attr("value")?.takeIf { it.isNotBlank() }
                type = if (control.hasAttr("multiple")) FieldType.STRING_LIST else FieldType.ENUM
            }
            tag == "textarea" -> {
                type = FieldType.TEXTAREA
                default = control.text().trim().takeIf { it.isNotEmpty() }
            }
            inputType == "radio" -> {
                options = siblings.map { FieldOption(value = it.attr("value").trim(), label = ownLabel(doc, it) ?: it.attr("value")) }
                default = siblings.firstOrNull { it.hasAttr("checked") }?.attr("value")
                type = FieldType.ENUM
            }
            inputType == "checkbox" -> {
                if (siblings.size > 1) {
                    options = siblings.map { FieldOption(value = it.attr("value").trim(), label = ownLabel(doc, it) ?: it.attr("value")) }
                    type = FieldType.STRING_LIST
                } else {
                    type = FieldType.BOOLEAN
                    default = if (control.hasAttr("checked")) "true" else null
                }
            }
            else -> {
                type = when (inputType) {
                    "date", "time", "datetime-local", "datetime", "month", "week" -> FieldType.DATETIME
                    "number", "range" -> FieldType.NUMBER
                    else -> if (control.hasAttr("multiple")) FieldType.STRING_LIST else FieldType.TEXT
                }
                default = control.attr("value").trim().takeIf { it.isNotEmpty() }
            }
        }

        val label = groupLabel(control, siblings)
            ?: ownLabel(doc, control)
            ?: control.attr("aria-label").trim().takeIf { it.isNotEmpty() }
            ?: control.attr("placeholder").trim().takeIf { it.isNotEmpty() }
            ?: humanize(id)

        val hint = listOfNotNull(
            control.attr("placeholder").trim().takeIf { it.isNotEmpty() && it != label },
            describedBy(doc, control),
        ).joinToString(". ").takeIf { it.isNotEmpty() }

        return FormField(
            id = id,
            label = label,
            type = type,
            required = required,
            askOnce = !required,
            question = control.attr("title").trim().takeIf { it.isNotEmpty() },
            defaultValue = default,
            options = options,
            prefill = prefillFor(control.attr("autocomplete")),
            hint = hint,
            input = inputType.takeIf { tag == "input" && it in INPUT_KINDS },
        )
    }

    /** The HTML autocomplete tokens for the user's own name / email / phone (WHATWG spec). */
    private fun prefillFor(autocomplete: String): String? =
        when (autocomplete.trim().lowercase().split(Regex("\\s+")).lastOrNull()) {
            "name" -> PREFILL_REPORTER
            "email" -> PREFILL_EMAIL
            "tel" -> PREFILL_PHONE
            else -> null
        }

    // ---------- labels ----------

    /** `<label for=id>` or the `<label>` wrapping the control (without the control's own text). */
    private fun ownLabel(doc: Document, control: Element): String? {
        val id = control.id()
        if (id.isNotBlank()) {
            doc.select("label[for=\"${cssEscape(id)}\"]").firstOrNull()?.let { return labelText(it) }
        }
        return control.parents().firstOrNull { it.normalName() == "label" }?.let(::labelText)
    }

    /** A radio/checkbox group is labelled by its fieldset's legend, not by one option. */
    private fun groupLabel(control: Element, siblings: List<Element>): String? {
        if (!control.isChoiceInput() || (siblings.size < 2 && control.attr("type").lowercase() == "checkbox")) return null
        val set = control.parents().firstOrNull { it.normalName() == "fieldset" } ?: return null
        return set.selectFirst("> legend")?.let(::labelText)
    }

    /**
     * The label's own words: "Incident type <span>(required)</span>" → "Incident type". Markers
     * and nested controls live in child elements; a label made only of child elements
     * (e.g. `<label><b>Name</b></label>`) falls back to all of its text.
     */
    private fun labelText(label: Element): String? {
        val own = label.ownText().trim().trimEnd('*', ':').trim()
        if (own.isNotEmpty()) return own
        val copy = label.clone()
        copy.select("input, select, textarea").remove()
        return copy.text().trim().trimEnd('*', ':').trim().takeIf { it.isNotEmpty() }
    }

    private fun describedBy(doc: Document, control: Element): String? =
        control.attr("aria-describedby").split(' ').filter { it.isNotBlank() }
            .mapNotNull { doc.getElementById(it)?.text()?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .joinToString(" ").takeIf { it.isNotEmpty() }

    // ---------- helpers ----------

    private fun isReportControl(e: Element): Boolean {
        if (e.hasAttr("disabled") || e.hasAttr("readonly")) return false
        if (e.normalName() != "input") return true
        return e.attr("type").lowercase() !in setOf("hidden", "submit", "button", "reset", "image", "file")
    }

    private fun Element.isChoiceInput() =
        normalName() == "input" && attr("type").lowercase() in setOf("radio", "checkbox")

    /**
     * The fieldset that groups this field with others. A radio/checkbox group's own fieldset
     * only holds its options, so it groups by the fieldset around that one.
     */
    private fun enclosingFieldset(control: Element, groupedChoice: Boolean): Element? {
        val sets = control.parents().filter { it.normalName() == "fieldset" }
        return if (groupedChoice) sets.getOrNull(1) else sets.firstOrNull()
    }

    private fun groupId(set: Element): String =
        set.id().ifBlank { set.attr("name") }.ifBlank {
            set.selectFirst("> legend")?.text()?.let(::slug).orEmpty()
        }.ifBlank { "group_${set.siblingIndex()}" }

    private fun firstText(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun humanize(id: String): String =
        id.replace(Regex("[_\\-]+"), " ").trim().replaceFirstChar { it.uppercase() }

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun cssEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Input types that say how a value is written or entered (kept as [FormField.input]). */
    private val INPUT_KINDS = setOf("date", "time", "datetime-local", "datetime", "month", "week", "number", "range", "email", "tel", "url")
}

/** Reads a form definition in either supported format: an HTML page or the JSON schema. */
object FormDocuments {
    /** File extensions of form documents, in the order they are looked up. */
    val EXTENSIONS = listOf("html", "json")

    fun parse(text: String, fallbackId: String): FormSchema {
        val body = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (body.startsWith("<")) HtmlFormParser.parse(body, fallbackId) else FormSchemaParser.parse(body)
    }
}
