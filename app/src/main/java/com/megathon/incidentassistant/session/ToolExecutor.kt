package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.form.FieldUpdate
import com.megathon.incidentassistant.form.FieldValue
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.form.UpdateStatus

/** Why a proposed update was not written to the form (internal; not for logs). */
data class Rejection(val fieldId: String, val reason: String)

data class Execution(
    /** Fields whose stored value or status changed this turn. */
    val changed: List<String>,
    val rejected: List<Rejection>,
    /**
     * Required fields the model marked "unknown". These are NOT applied: the session asks
     * the user to confirm "not specified" out loud first (§5.2).
     */
    val unknownRequested: List<String>,
    /** Tools the model called that the app deliberately does not execute (ask_user, submit_report). */
    val ignoredTools: List<String>,
)

/**
 * Validates and executes the model's tool calls against the form (docs/DESIGN.md §4.2).
 *
 * The model only proposes. Every update goes through field-id, type and enum validation
 * ([FormState.apply] → [com.megathon.incidentassistant.form.FieldNormalizer]) and the merge
 * policy. `ask_user` wording is not used (the [DialogPolicy] owns questions) and
 * `submit_report` is never executed: filing needs the user's explicit confirmation.
 */
class ToolExecutor(private val schema: FormSchema) {

    fun execute(
        calls: List<FormToolCall>,
        state: FormState,
        pendingFieldIds: Set<String>,
    ): Execution {
        val changed = linkedSetOf<String>()
        val rejected = mutableListOf<Rejection>()
        val unknownRequested = linkedSetOf<String>()
        val ignored = mutableListOf<String>()

        for (call in calls) {
            when (call) {
                is FormToolCall.RecordFields -> call.updates.forEach { update ->
                    applyUpdate(update, state, pendingFieldIds, changed, rejected, unknownRequested)
                }
                is FormToolCall.AskUser -> ignored += "ask_user"
                is FormToolCall.SubmitReport -> ignored += "submit_report"
            }
        }
        return Execution(changed.toList(), rejected, unknownRequested.toList(), ignored)
    }

    private fun applyUpdate(
        update: FieldUpdate,
        state: FormState,
        pendingFieldIds: Set<String>,
        changed: MutableSet<String>,
        rejected: MutableList<Rejection>,
        unknownRequested: MutableSet<String>,
    ) {
        val field = schema.field(update.fieldId)
        if (field == null) {
            rejected += Rejection(update.fieldId, "unknown field")
            return
        }
        val evidence = update.evidence?.trim()?.take(MAX_EVIDENCE_CHARS)?.takeIf { it.isNotEmpty() }
        val before = state.get(field.id)

        if (update.status == UpdateStatus.UNKNOWN) {
            if (field.required) {
                unknownRequested += field.id
            } else if (state.markUnknown(field.id, evidence).accepted && differs(before, state.get(field.id))) {
                changed += field.id
            }
            return
        }
        if (update.value.isBlank()) {
            rejected += Rejection(field.id, "empty value")
            return
        }
        if (update.value.length > MAX_VALUE_CHARS) {
            rejected += Rejection(field.id, "value too long")
            return
        }

        // An answer to the question just asked is explicit (§3.3): status answered,
        // confidence floor 0.8. Anything the model guessed about other fields stays inferred.
        val confidence = update.confidence.takeUnless { it.isNaN() } ?: DEFAULT_CONFIDENCE
        val isPendingAnswer = field.id in pendingFieldIds
        val status = if (isPendingAnswer) UpdateStatus.ANSWERED else update.status
        val finalConfidence = if (isPendingAnswer) maxOf(confidence, PENDING_CONFIDENCE_FLOOR) else confidence

        val result = state.apply(field.id, update.value, finalConfidence, evidence, status)
        if (!result.accepted) {
            rejected += Rejection(field.id, result.reason ?: "rejected")
        } else if (differs(before, state.get(field.id))) {
            changed += field.id
        }
    }

    private fun differs(a: FieldValue, b: FieldValue): Boolean = a.value != b.value || a.status != b.status

    private companion object {
        const val MAX_EVIDENCE_CHARS = 200
        const val MAX_VALUE_CHARS = 1_000
        const val DEFAULT_CONFIDENCE = 0.5
        const val PENDING_CONFIDENCE_FLOOR = 0.8
    }
}
