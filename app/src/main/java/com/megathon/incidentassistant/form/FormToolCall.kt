package com.megathon.incidentassistant.form

/** Values the extraction LLM can propose for a field (docs/DESIGN.md §4.2). */
enum class UpdateStatus { ANSWERED, INFERRED, UNKNOWN }

/** One field update inside a `record_fields` tool call. */
data class FieldUpdate(
    val fieldId: String,
    val value: String,
    val confidence: Double = 0.5,
    val evidence: String? = null,
    val status: UpdateStatus = UpdateStatus.INFERRED,
)

/**
 * The three tools the assistant exposes. The model proposes calls; the app validates and
 * executes them (function calling without trusting the model with state).
 */
sealed interface FormToolCall {
    data class RecordFields(val updates: List<FieldUpdate>) : FormToolCall
    data class AskUser(val fieldId: String?, val question: String) : FormToolCall
    data class SubmitReport(val summary: String?) : FormToolCall

    /**
     * The utterance is not about an incident or this form (greeting, weather, a joke).
     * [unsure] = the model cannot tell. Never changes the form; the session redirects.
     */
    data class OffTopic(val unsure: Boolean) : FormToolCall
}
