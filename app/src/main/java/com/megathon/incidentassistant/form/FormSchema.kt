package com.megathon.incidentassistant.form

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * The dynamic form model (docs/DESIGN.md §3.1).
 *
 * A form is plain JSON: every field carries its own metadata — mandatory flag, answer
 * type, options (with labels + spoken aliases), the question to ask when it is missing,
 * group membership and [FormField.keywords]. The LLM extraction prompt is generated
 * from this at runtime, so the assistant is model- and use-case-agnostic: admins swap
 * the JSON (delivered through the proxy API, §8.1), never code.
 *
 * JSON keys are snake_case on purpose (`ask_once`, `question_groups`, ...) so form
 * definitions stay easy to hand-edit.
 */

@Serializable
enum class FieldType {
    @SerialName("text") TEXT,
    @SerialName("textarea") TEXTAREA,
    @SerialName("enum") ENUM,
    @SerialName("datetime") DATETIME,
    @SerialName("string_list") STRING_LIST,
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
}

@Serializable
data class FieldOption(
    val value: String,
    val label: String = "",
    val aliases: List<String> = emptyList(),
)

@Serializable
data class FormField(
    val id: String,
    val label: String = "",
    val type: FieldType = FieldType.TEXT,
    val required: Boolean = false,
    @SerialName("ask_once") val askOnce: Boolean = false,
    val question: String? = null,
    @SerialName("default") val defaultValue: String? = null,
    val options: List<FieldOption> = emptyList(),
    /** Extra words/phrases that help the LLM recognise this field in free speech. */
    val keywords: List<String> = emptyList(),
    /** Optional `question_groups` membership, used to bundle conversational follow-ups. */
    val group: String? = null,
    /**
     * Filled by the app from a trusted context instead of being asked. Only `"reporter"`
     * is defined: the signed-in user's name (see session/UserIdentity). Unknown → asked.
     */
    val prefill: String? = null,
    /**
     * Short clause used when this field is asked together with others, e.g. "where it
     * happened" → "Could you tell me where it happened and when it happened?". Without it
     * the label is used ("the location").
     */
    @SerialName("ask_phrase") val askPhrase: String? = null,
    /** Never combine this field with others (safety-critical or needs a focused answer). */
    @SerialName("ask_alone") val askAlone: Boolean = false,
    /**
     * Free-text guidance from the form author about what goes in the field (an HTML form's
     * `placeholder` / `aria-describedby` text). Shown to the extraction model, never asked.
     */
    val hint: String? = null,
    /**
     * The HTML input kind the form asks for, when it says more than [type]: `date`, `time`,
     * `datetime-local`, `month`, `week` (all [FieldType.DATETIME]), `number`, `email`, `tel`,
     * `url`. Decides the value format and the editor (date picker, phone keyboard, …).
     */
    val input: String? = null,
) {
    /**
     * Every surface form that can indicate this field in speech: the id, the label,
     * the keywords, and each option's value/label/aliases. This is the vocabulary the
     * extraction prompt advertises to the model.
     */
    fun vocabulary(): List<String> =
        (listOf(id, label) + keywords + options.flatMap { listOf(it.value, it.label) + it.aliases })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

@Serializable
data class QuestionGroup(
    val id: String,
    /** Spoken question for the whole group; null → built from the members' labels. */
    val prompt: String? = null,
    val fields: List<String> = emptyList(),
)

@Serializable
data class FormBehavior(
    @SerialName("ask_optional_once") val askOptionalOnce: Boolean = true,
    /** "combined": one natural question per group; "individual": one field per turn. */
    @SerialName("optional_mode") val optionalMode: String = "combined",
    @SerialName("confirm_before_submit") val confirmBeforeSubmit: Boolean = true,
    @SerialName("allow_unknown_required") val allowUnknownRequired: Boolean = true,
)

@Serializable
data class FormSchema(
    val id: String,
    val title: String = "",
    val version: String = "1.0",
    val fields: List<FormField> = emptyList(),
    @SerialName("question_groups") val questionGroups: List<QuestionGroup> = emptyList(),
    val behavior: FormBehavior = FormBehavior(),
    /** The HTML page this form was read from (null for JSON forms): shown filled in at the end. */
    @Transient val sourceHtml: String? = null,
) {
    fun field(fieldId: String): FormField? = fields.firstOrNull { it.id == fieldId }

    val requiredFields: List<FormField> get() = fields.filter { it.required }

    fun group(groupId: String): QuestionGroup? = questionGroups.firstOrNull { it.id == groupId }
}

object FormSchemaParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): FormSchema = json.decodeFromString(FormSchema.serializer(), text)
}
