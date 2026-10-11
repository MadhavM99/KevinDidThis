package com.megathon.incidentassistant.form

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element

/**
 * The form page the report came from, filled in with the collected answers and ready to send:
 * the user sees exactly the form they would have typed into, with a working submit button.
 *
 *  - every control gets its value (text, date-time, select option, radio, checkbox), matched
 *    by `name` or `id` — the same keys [HtmlFormParser] used for the field ids;
 *  - the page's own scripts are removed: only the markup is trusted, and a script that
 *    (re)fills the form, like a demo generator, would overwrite the answers;
 *  - if the form has no submit button, one is added; submitting is handed to the app through
 *    [BRIDGE] (`submit(json)` with the values as they are on screen, so last-second edits on
 *    the page count) instead of navigating anywhere.
 */
object FilledFormRenderer {

    /** JavaScript interface name the hosting WebView registers. */
    const val BRIDGE = "IncidentForm"

    fun render(html: String, values: Map<String, String>, submitLabel: String = "Submit report"): String {
        val doc = Jsoup.parse(html)
        doc.select("script, noscript").remove()
        doc.select("[onsubmit], [onclick], [onload]").forEach {
            it.removeAttr("onsubmit"); it.removeAttr("onclick"); it.removeAttr("onload")
        }
        val form = doc.selectFirst("form") ?: doc.body()

        for ((key, raw) in values) {
            val value = raw.trim()
            if (value.isEmpty()) continue
            val controls = form.select("input, select, textarea").filter { (it.attr("name").ifBlank { it.id() }) == key }
            for (control in controls) fill(control, value, controls.size)
        }

        val hasSubmit = form.select("button:not([type]), button[type=submit], input[type=submit]").isNotEmpty()
        if (!hasSubmit) {
            form.appendElement("button").attr("type", "submit").attr("id", "incident_assistant_submit").text(submitLabel)
        }
        if (form.id().isBlank()) form.attr("id", "incident_assistant_form")
        // Never navigate away (the page's own handlers were removed): the bridge below submits.
        form.attr("onsubmit", "return false;")
        // A DataNode, not text: script content must not be HTML-escaped ("a > b" → "a &gt; b").
        doc.body().appendElement("script").appendChild(DataNode(bridgeScript(form.id())))
        return doc.outerHtml()
    }

    private fun fill(control: Element, value: String, siblings: Int) {
        when (control.normalName()) {
            "textarea" -> control.text(value)
            "select" -> {
                val wanted = listValues(value).map(::norm).toSet()
                val options = control.select("option")
                options.forEach { it.removeAttr("selected") }
                val match = options.filter { norm(optionValue(it)) in wanted || norm(it.text()) in wanted }
                if (match.isNotEmpty()) {
                    match.forEach { it.attr("selected", "selected") }
                } else if (!control.hasAttr("multiple")) {
                    // An answer the list doesn't offer: show it rather than silently drop it.
                    control.appendElement("option").attr("value", value).attr("selected", "selected").text(value)
                }
            }
            else -> when (control.attr("type").lowercase()) {
                "radio" -> setChecked(control, norm(control.attr("value")) == norm(value) || norm(labelOf(control)) == norm(value))
                "checkbox" -> if (siblings > 1) {
                    val wanted = listValues(value).map(::norm)
                    setChecked(control, norm(control.attr("value")) in wanted || norm(labelOf(control)) in wanted)
                } else {
                    setChecked(control, norm(value) in TRUE_WORDS)
                }
                "datetime-local" -> control.attr("value", value.take(16)) // yyyy-MM-ddTHH:mm
                "date" -> control.attr("value", value.take(10))
                "time" -> control.attr("value", value.substringAfter('T').take(5))
                else -> control.attr("value", value)
            }
        }
    }

    private fun setChecked(e: Element, on: Boolean) {
        if (on) e.attr("checked", "checked") else e.removeAttr("checked")
    }

    private fun optionValue(o: Element) = if (o.hasAttr("value")) o.attr("value") else o.text()

    private fun labelOf(control: Element): String {
        val id = control.id()
        val doc = control.ownerDocument() ?: return ""
        if (id.isNotBlank()) doc.selectFirst("label[for=\"$id\"]")?.let { return it.text() }
        return control.parents().firstOrNull { it.normalName() == "label" }?.text().orEmpty()
    }

    private fun listValues(value: String) = value.split(LIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("[\\s_]+"), " ")

    private fun bridgeScript(formId: String) = """
        (function () {
          var form = document.getElementById(${quote(formId)});
          if (!form) return;
          form.addEventListener('submit', function (e) {
            e.preventDefault();
            var data = {};
            Array.prototype.forEach.call(form.elements, function (el) {
              var key = el.name || el.id;
              if (!key || el.disabled || el.readOnly) return;
              if (el.type === 'submit' || el.type === 'button' || el.type === 'reset') return;
              if (el.type === 'radio') { if (el.checked) data[key] = el.value; return; }
              if (el.type === 'checkbox') {
                var group = form.querySelectorAll('input[type=checkbox][name="' + key + '"]');
                if (group.length > 1) {
                  if (el.checked) data[key] = data[key] ? data[key] + '; ' + el.value : el.value;
                } else { data[key] = el.checked ? 'true' : 'false'; }
                return;
              }
              if (el.tagName === 'SELECT' && el.multiple) {
                data[key] = Array.prototype.filter.call(el.options, function (o) { return o.selected; })
                  .map(function (o) { return o.value; }).join('; ');
                return;
              }
              data[key] = el.value;
            });
            if (window.$BRIDGE) window.$BRIDGE.submit(JSON.stringify(data));
          });
        })();
    """.trimIndent()

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private val LIST_SPLIT = Regex("\\s*[;,]\\s*")
    private val TRUE_WORDS = setOf("true", "yes", "y", "on", "1", "checked")
}
