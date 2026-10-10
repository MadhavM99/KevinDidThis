package com.megathon.incidentassistant.form

/**
 * Builds the extraction prompt from the form definition (docs/DESIGN.md §4.3).
 *
 * Field ids, labels, keywords and option values/aliases are advertised to the model, so
 * the form itself is the extraction vocabulary. Nothing about a specific form or model
 * is hardcoded here.
 */
object ExtractionPromptBuilder {

    fun systemPrompt(schema: FormSchema, state: FormState, nowIso: String): String = buildString {
        appendLine("You are the extraction engine of a form-filling assistant. You never chat with the user.")
        appendLine("Read the user's sentence and return tool calls that fill the form. Never invent values.")
        appendLine()
        appendLine("CURRENT TIME: $nowIso")
        appendLine()
        appendLine("FORM: ${schema.title.ifBlank { schema.id }}")
        appendLine("FIELDS (the `field` id, its label, and the words that may indicate it):")
        for (field in schema.fields) {
            val parts = mutableListOf<String>()
            parts += "id=${field.id}"
            if (field.label.isNotBlank()) parts += "label=\"${field.label}\""
            parts += "type=${field.type.name.lowercase()}"
            if (field.required) parts += "REQUIRED"
            if (field.options.isNotEmpty()) parts += "options=" + field.options.joinToString("/") { it.value }
            if (field.keywords.isNotEmpty()) parts += "keywords=" + field.keywords.joinToString(",")
            appendLine("  - " + parts.joinToString(" | "))
        }
        appendLine()
        appendLine("CURRENT VALUES:")
        for (field in schema.fields) {
            val value = state.get(field.id)
            val shown = when {
                value.status == FieldStatus.UNKNOWN -> "(not specified)"
                value.value.isBlank() -> "(missing)"
                else -> value.value
            }
            appendLine("  - ${field.id}: $shown")
        }
        appendLine()
        appendLine("OUTPUT FORMAT — exactly one JSON object, no prose, no markdown fences:")
        appendLine("""<tool_call>{"tool":"record_fields","arguments":{"updates":[{"field":"<field id>","value":"<string>","confidence":0.0,"evidence":"<short quote>","status":"inferred"}]}}</tool_call>""")
        appendLine()
        appendLine("RULES:")
        appendLine("1. Extract only what the user said or clearly implied.")
        appendLine("2. For enum fields return the option `value`; match spoken labels or aliases.")
        appendLine("3. If the user was just asked about a field, put the answer into it with status \"answered\".")
        appendLine("4. \"I don't know / skip\" for a field → status \"unknown\".")
        appendLine("5. Confidence: 0.9+ explicit, 0.6-0.9 implied, below 0.6 ambiguous.")
        appendLine("6. Use ask_user only if one clarification is essential; use submit_report only if the user confirms submission.")
    }

    fun userPrompt(pendingField: FormField?, utterance: String): String = buildString {
        if (pendingField != null) {
            appendLine("The assistant just asked: \"${pendingField.question ?: pendingField.label}\"")
            appendLine(
                "It expects an answer for field id=${pendingField.id}, " +
                    "but also extract any other fields the user mentions."
            )
            appendLine()
        }
        append("USER SAID: \"$utterance\"")
    }
}
