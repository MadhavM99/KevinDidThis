package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField

/**
 * Everything the assistant says that is not taken verbatim from the form definition.
 * The app implements this with string resources (`ui/ResourcePhrases`); tests use a fake.
 * Field-specific wording always comes from the schema: `question`, `label`, option labels.
 */
interface SessionPhrases {
    /** "What is the Location?" — used when a field has no `question` in the schema. */
    fun askByLabel(label: String): String

    /** Rephrase for an unanswered field. [options] is non-empty for enum fields. */
    fun askAgain(label: String, question: String, options: List<String>): String

    /** Appended to a question asked again after the rephrase when "not specified" is not allowed. */
    fun askAgainCancelHint(): String

    fun offerUnknown(label: String): String
    fun confirmValue(label: String, value: String): String

    /** One question for several fields: "Could you tell me where it happened and when?" */
    fun askTogether(phrases: List<String>): String

    /** Catch-all optional question listing the open optional fields. */
    fun askOptional(labels: List<String>): String

    /** "Location: Conference Room 2." — one line of the spoken summary. */
    fun summaryLine(label: String, value: String): String
    fun notSpecified(): String
    fun confirmReport(summary: String): String
    fun whatToChange(): String
    fun noChangeHeard(): String

    fun didNotUnderstand(): String
    fun didNotHear(): String

    /** Off-topic utterance with nothing asked yet: the full "what to tell me" redirection. */
    fun redirectToIncident(): String
    /** Off-topic utterance while a question is pending: short lead-in, the question follows. */
    fun redirectBeforeQuestion(): String
    /** The model could not tell whether this is about an incident. */
    fun notSureItsAnIncident(): String
    /** Several off-topic turns in a row: stop the hands-free loop and wait for the user. */
    fun offTopicPaused(): String

    fun confirmCancel(): String
    fun cancelled(): String
    fun missingBeforeSubmit(labels: List<String>): String

    fun submitted(reportId: String): String
    fun savedNotDelivered(reportId: String): String
    fun saveFailed(): String
    fun alreadyFiled(reportId: String): String
    fun sessionClosed(): String

    /** Joins option labels for speech: "low, medium, high, or critical". */
    fun orList(items: List<String>): String
}

/** Labels and display values derived from the schema, shared by session and summary. */
internal object FieldText {
    fun label(field: FormField): String = field.label.ifBlank { field.id.replace('_', ' ') }

    /** Clause for a combined question: the schema's `ask_phrase`, else "the <label>". */
    fun askPhrase(field: FormField): String =
        field.askPhrase?.trim()?.takeIf { it.isNotEmpty() } ?: "the ${label(field).lowercase()}"

    fun optionLabels(field: FormField): List<String> =
        field.options.map { it.label.ifBlank { it.value } }

    /** Enum ids become their spoken label ("in_progress" → "In progress"). */
    fun display(field: FormField, value: String): String = when (field.type) {
        FieldType.ENUM -> field.options.firstOrNull { it.value.equals(value, ignoreCase = true) }
            ?.let { it.label.ifBlank { it.value } } ?: value
        else -> value
    }
}
