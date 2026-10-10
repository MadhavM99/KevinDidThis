package com.megathon.incidentassistant.form

/** One line of recent conversation, fed back to the model for context. */
data class ConversationTurn(val role: String, val text: String)

/**
 * What the assistant is currently waiting for. One field for a normal follow-up question,
 * several for a combined optional-group question (docs/DESIGN.md §5.2).
 */
data class PendingQuestion(val question: String, val fields: List<FormField>)

/**
 * Builds the extraction prompt from the form definition (docs/DESIGN.md §4.3).
 *
 * Field ids, labels, keywords, questions and option values/labels/aliases are advertised
 * to the model, so the form itself is the extraction vocabulary. Nothing about a specific
 * form or model is hardcoded here. The digest is deliberately compact (one line per
 * field) because the prompt is prefilled on a phone NPU every turn.
 */
object ExtractionPromptBuilder {

    private const val EVIDENCE_PREVIEW = 60

    fun systemPrompt(schema: FormSchema, state: FormState, nowIso: String): String = buildString {
        appendLine("You are the extraction engine of a form-filling assistant. You never chat with the user.")
        appendLine("Read what the user said and return ONE tool call that records the facts into the form.")
        appendLine()
        appendLine("CURRENT TIME: $nowIso (resolve \"today\", \"this morning\", \"around 10 AM\" against it)")
        appendLine()
        appendLine("FORM: ${schema.title.ifBlank { schema.id }}")
        appendLine("FIELDS (the `field` id, its label, and the words that may indicate it):")
        for (field in schema.fields) {
            val parts = mutableListOf<String>()
            parts += "id=${field.id}"
            if (field.label.isNotBlank()) parts += "label=\"${field.label}\""
            parts += "type=${field.type.name.lowercase()}"
            if (field.required) parts += "REQUIRED"
            if (field.options.isNotEmpty()) parts += "options=" + field.options.joinToString(" / ") { describeOption(it) }
            if (field.keywords.isNotEmpty()) parts += "keywords=" + field.keywords.joinToString(",")
            field.question?.takeIf { it.isNotBlank() }?.let { parts += "asked as \"$it\"" }
            appendLine("  - " + parts.joinToString(" | "))
        }
        appendLine()
        appendLine("CURRENT VALUES:")
        for (field in schema.fields) {
            val value = state.get(field.id)
            val shown = when {
                state.isAppProvided(field.id) -> "${value.value} (set by the app: never output this field)"
                value.status == FieldStatus.UNKNOWN -> "(not specified)"
                value.value.isBlank() -> "(missing)"
                value.status == FieldStatus.AMBIGUOUS -> "${value.value} (unsure)"
                value.evidence.isNotBlank() -> "${value.value} (from \"${value.evidence.take(EVIDENCE_PREVIEW)}\")"
                else -> value.value
            }
            appendLine("  - ${field.id}: $shown")
        }
        appendLine()
        appendLine("OUTPUT FORMAT — exactly one JSON object, no prose, no markdown fences:")
        appendLine("""<tool_call>{"tool":"record_fields","arguments":{"updates":[{"field":"<field id>","value":"<string>","confidence":0.0,"evidence":"<user's words>","status":"inferred"}]}}</tool_call>""")
        appendLine("""If the user said nothing that fits a field: <tool_call>{"tool":"record_fields","arguments":{"updates":[]}}</tool_call>""")
        appendLine("""If the user is not talking about an incident or this form at all (greeting, small talk, weather, a joke, a question for you): <tool_call>{"tool":"off_topic","arguments":{}}</tool_call>""")
        appendLine("""If you cannot tell whether they are describing an incident: <tool_call>{"tool":"off_topic","arguments":{"unsure":true}}</tool_call>""")
        appendLine()
        appendLine("RULES:")
        appendLine("1. Record EVERY field the user gave information about, not only the one that was asked.")
        appendLine("2. Extract only what the user said or clearly implied. Never invent or guess values; leave unmentioned fields out.")
        appendLine("3. For enum fields return the option id before the parentheses; match spoken labels or aliases. If no option fits, leave the field out.")
        appendLine("4. evidence = the user's own words that support the value.")
        appendLine("5. status \"answered\" = stated directly or a correction (\"actually it was 11\"); \"inferred\" = implied; \"unknown\" = the user does not know or wants to skip that field (value \"\").")
        appendLine("6. Confidence: 0.9+ explicit, 0.6-0.9 implied, below 0.6 unsure.")
        appendLine("7. Facts that fit no field are ignored. Never call submit_report: only the user can file the report.")
        appendLine("8. Damage, leaks, injuries, faults or hazards described in plain words ARE incidents. A short answer to the question just asked (\"medium\", \"near the lobby\") is never off_topic.")
        appendLine("9. A value \"set by the app\" is final; the person speaking is that reporter, not an involved person or witness unless they say so.")
    }

    fun userPrompt(pendingField: FormField?, utterance: String): String =
        userPrompt(
            pending = pendingField?.let { PendingQuestion(it.question ?: it.label, listOf(it)) },
            utterance = utterance,
            history = emptyList(),
        )

    fun userPrompt(
        pending: PendingQuestion?,
        utterance: String,
        history: List<ConversationTurn>,
    ): String = buildString {
        if (history.isNotEmpty()) {
            appendLine("RECENT CONVERSATION:")
            history.forEach { appendLine("  ${it.role}: ${it.text}") }
            appendLine()
        }
        if (pending != null && pending.fields.isNotEmpty()) {
            appendLine("The assistant just asked: \"${pending.question}\"")
            if (pending.fields.size == 1) {
                appendLine(
                    "It expects an answer for field id=${pending.fields.single().id}, " +
                        "but also extract any other fields the user mentions."
                )
            } else {
                appendLine(
                    "It expects answers for field ids=${pending.fields.joinToString(",") { it.id }}, " +
                        "but also extract any other fields the user mentions."
                )
            }
            appendLine()
        }
        append("USER SAID: \"$utterance\"")
    }

    /** `value ("Label": alias, alias)` — the canonical id plus every spoken surface form. */
    private fun describeOption(option: FieldOption): String {
        val surfaces = (listOf(option.label) + option.aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(option.value, ignoreCase = true) }
            .distinct()
        return if (surfaces.isEmpty()) option.value else "${option.value} (${surfaces.joinToString(", ")})"
    }
}
