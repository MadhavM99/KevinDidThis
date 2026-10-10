package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finds the fields of a form that hold the reporting user's own name / email / phone, so they
 * are filled from the user's profile instead of asked ("Reported by?" → "me").
 *
 * A form can say so itself (HTML `autocomplete="name|email|tel"`, or `prefill` in JSON); then
 * the model is not consulted. Otherwise the on-device model reads the field labels once, when
 * the form loads — no field names or keywords are known to the app. Any failure (no model,
 * timeout, unparseable answer) leaves the form unchanged: those fields are then simply asked.
 */
class ReporterFieldClassifier(
    private val llm: LlmBackend,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    private val cache = mutableMapOf<FormSchema, FormSchema>()

    suspend fun classify(schema: FormSchema): FormSchema {
        if (schema.fields.any { it.prefill in PREFILL_KINDS }) return schema // the form says
        val candidates = schema.fields.filter { it.type == FieldType.TEXT && it.options.isEmpty() }
        if (candidates.isEmpty()) return schema
        cache[schema]?.let { return it }

        val answer = try {
            withTimeoutOrNull(timeoutMs) { llm.generate(prompt(schema, candidates)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("REPORTER_FIELDS_FAILED ${e.javaClass.simpleName}")
            null
        } ?: return schema.also { log("REPORTER_FIELDS_NONE (no answer)") }

        val picks = parse(answer, candidates.map { it.id }.toSet())
        log("REPORTER_FIELDS ${picks.entries.joinToString { "${it.value}=${it.key}" }.ifEmpty { "none" }}")
        val result = schema.copy(fields = schema.fields.map { f -> picks[f.id]?.let { f.copy(prefill = it) } ?: f })
        cache[schema] = result
        return result
    }

    internal fun prompt(schema: FormSchema, candidates: List<FormField>): String = buildString {
        appendLine("You read web forms. A person is filling in the form below about something they are reporting.")
        appendLine("Which fields ask for that person's OWN details: their own name, their own email, their own phone number?")
        appendLine("Fields about anyone else (people involved, witnesses, contacts, managers) are NOT the person's own details.")
        appendLine()
        appendLine("FORM: ${schema.title.ifBlank { schema.id }}")
        appendLine("ALL FIELDS (for context):")
        schema.fields.forEach { appendLine("  - ${it.label.ifBlank { it.id }}") }
        appendLine("CANDIDATE FIELDS:")
        candidates.forEach { f ->
            val hint = f.hint?.takeIf { it.isNotBlank() }?.let { " | hint=\"$it\"" }.orEmpty()
            appendLine("  - id=${f.id} | label=\"${f.label}\"$hint")
        }
        appendLine()
        appendLine("Answer with exactly one JSON object and nothing else. Use a candidate id, or null:")
        append("""{"name": null, "email": null, "phone": null}""")
    }

    /** `{"name":"<id>","email":…,"phone":…}` → field id → prefill kind (known, distinct ids only). */
    internal fun parse(answer: String, candidateIds: Set<String>): Map<String, String> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val obj = runCatching { lenient.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject }
            .getOrNull() ?: return emptyMap()
        val picks = linkedMapOf<String, String>()
        for ((key, kind) in KINDS) {
            val id = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
            if (id in candidateIds && id !in picks) picks[id] = kind
        }
        return picks
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private val KINDS = listOf("name" to PREFILL_REPORTER, "email" to PREFILL_EMAIL, "phone" to PREFILL_PHONE)
        private val lenient = Json { isLenient = true }
    }
}
