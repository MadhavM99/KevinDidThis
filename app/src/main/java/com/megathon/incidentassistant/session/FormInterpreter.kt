package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Reads a freshly loaded form once with the on-device model, so nothing about any form is
 * written in the app:
 *
 *  1. **Questions.** Every field the page doesn't word itself (no `title`) gets a short,
 *     natural spoken question written by the model from the field's label, type, options and
 *     hint — a new form brings new questions with no code or data change.
 *  2. **The reporter's own details.** Fields asking for the person filling the form in (their
 *     own name / email / phone) are filled from the user's profile instead of asked: people
 *     answer "who is reporting?" with "me". A form can say so itself (HTML
 *     `autocomplete="name|email|tel"`); then the model's opinion on that is not used.
 *
 * Output is a few plain lines (cheap to generate, robust to parse). Anything missing or
 * invalid falls back safely: no question → asked from the label; no reporter pick → asked.
 */
class FormInterpreter(
    private val llm: LlmBackend,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
    /** Keeps readings across launches: a form is read by the model once, until it changes. */
    private val store: Store? = null,
) {
    /** A model reading of a form, by [keyFor]. */
    interface Store {
        fun load(key: String): FormSchema?
        fun save(key: String, form: FormSchema)
    }

    private val cache = mutableMapOf<FormSchema, FormSchema>()

    /** Whether [interpret] can answer without running the model. */
    fun isRead(schema: FormSchema): Boolean =
        schema.fields.isEmpty() || schema in cache || !needsModel(schema) ||
            store?.let { s -> runCatching { s.load(keyFor(schema)) }.getOrNull() } != null

    suspend fun interpret(schema: FormSchema): FormSchema {
        if (schema.fields.isEmpty()) return schema
        cache[schema]?.let { return it }
        val formMarksReporter = schema.fields.any { it.prefill in PREFILL_KINDS }
        if (!needsModel(schema)) return schema
        val saved = store
        val key = saved?.let { keyFor(schema) }
        if (saved != null && key != null) {
            runCatching { saved.load(key) }.getOrNull()?.let { reading ->
                val result = reading.copy(sourceHtml = schema.sourceHtml)
                log("FORM_INTERPRETED cached")
                cache[schema] = result
                return result
            }
        }

        val answer = try {
            withTimeoutOrNull(timeoutMs) { llm.generate(prompt(schema)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("FORM_INTERPRET_FAILED ${e.javaClass.simpleName}")
            null
        } ?: return schema.also { log("FORM_INTERPRET_NONE (no answer)") }

        val reading = parse(answer, schema)
        val reporter = if (formMarksReporter) emptyMap() else reading.reporter
        val result = schema.copy(
            fields = schema.fields.map { f ->
                var out = f
                reporter[f.id]?.let { out = out.copy(prefill = it) }
                if (out.question.isNullOrBlank() && out.prefill == null) {
                    reading.questions[f.id]?.let { out = out.copy(question = it) }
                }
                out
            },
        )
        log(
            "FORM_INTERPRETED questions=${reading.questions.size}/${schema.fields.size} " +
                "reporter=${reporter.entries.joinToString { "${it.value}=${it.key}" }.ifEmpty { "none" }}",
        )
        cache[schema] = result
        if (saved != null && key != null) runCatching { saved.save(key, result) }
        return result
    }

    private fun needsModel(schema: FormSchema): Boolean {
        val formMarksReporter = schema.fields.any { it.prefill in PREFILL_KINDS }
        val needQuestions = schema.fields.any { it.question.isNullOrBlank() && it.prefill == null }
        return !(formMarksReporter && !needQuestions)
    }

    /** Same fields (and same prompt) → same reading; page styling changes don't matter. */
    internal fun keyFor(schema: FormSchema): String {
        val canonical = KEY_JSON.encodeToString(FormSchema.serializer(), schema)
        val digest = MessageDigest.getInstance("SHA-256").digest("$READING_VERSION\n$canonical".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun prompt(schema: FormSchema): String = buildString {
        appendLine("You prepare a voice assistant that fills in the web form below by talking to the person reporting.")
        appendLine()
        appendLine("FORM: ${schema.title.ifBlank { schema.id }}")
        appendLine("FIELDS:")
        schema.fields.forEach { f -> appendLine("  - " + describe(f)) }
        appendLine()
        appendLine("Task 1. Which TEXT field asks for the reporting person's OWN name, OWN email and OWN phone?")
        appendLine("Fields about anyone else (people involved, witnesses, contacts) are not their own. Use none if there is no such field.")
        appendLine("Task 2. For EVERY field, write the short, friendly question the assistant should say aloud to get it.")
        appendLine("One question per field, under 15 words, plain spoken English. For choices, name the choices.")
        appendLine()
        appendLine("Reply with these lines only, nothing else:")
        appendLine("name: <field id or none>")
        appendLine("email: <field id or none>")
        appendLine("phone: <field id or none>")
        append("<field id>: <question>")
    }

    private fun describe(f: FormField): String {
        val parts = mutableListOf("id=${f.id}", "label=\"${f.label}\"", "type=${f.type.name.lowercase()}")
        if (f.required) parts += "required"
        if (f.options.isNotEmpty()) parts += "choices=" + f.options.joinToString(" / ") { it.label.ifBlank { it.value } }
        f.hint?.takeIf { it.isNotBlank() }?.let { parts += "hint=\"$it\"" }
        return parts.joinToString(" | ")
    }

    internal class Reading(val reporter: Map<String, String>, val questions: Map<String, String>)

    internal fun parse(answer: String, schema: FormSchema): Reading {
        val ids = schema.fields.associateBy { it.id }
        val reporter = linkedMapOf<String, String>()
        val questions = linkedMapOf<String, String>()
        for (raw in answer.lineSequence()) {
            val line = raw.trim().trimStart('-', '*', ' ').replace("`", "")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim().trim('"', '\'').trim()
            if (value.isEmpty()) continue
            val kind = REPORTER_KEYS[key.lowercase()]
            if (kind != null && key !in ids) {
                val field = ids[value] ?: continue // "none" or an unknown id
                if (field.type == FieldType.TEXT && field.options.isEmpty() && field.id !in reporter) {
                    reporter[field.id] = kind
                }
                continue
            }
            val field = ids[key] ?: continue
            if (field.id !in questions && value.length in 3..MAX_QUESTION_CHARS) {
                questions[field.id] = value.let { if (it.endsWith("?") || it.endsWith(".")) it else "$it?" }
            }
        }
        return Reading(reporter, questions)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L

        /** Bump when the prompt or parsing changes, so saved readings are redone. */
        private const val READING_VERSION = 1
        private val KEY_JSON = Json { encodeDefaults = true }
        private const val MAX_QUESTION_CHARS = 160
        private val REPORTER_KEYS = mapOf(
            "name" to PREFILL_REPORTER,
            "email" to PREFILL_EMAIL,
            "phone" to PREFILL_PHONE,
        )
    }
}
