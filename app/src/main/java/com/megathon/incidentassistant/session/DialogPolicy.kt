package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormState

/** What the assistant should do next. Decided by app logic only, never by the model. */
sealed interface PolicyAction {
    /** A required field is empty. [attempt] > 0 means the previous question went unanswered. */
    data class AskField(val field: FormField, val attempt: Int) : PolicyAction

    /** A required field holds an unsure (AMBIGUOUS) value — read it back for a yes/no. */
    data class ConfirmValue(val field: FormField, val value: String) : PolicyAction

    /** The user could not answer a required field after a rephrase — offer "not specified". */
    data class OfferUnknown(val field: FormField) : PolicyAction

    /**
     * One combined question for optional `ask_once` fields (§5.2 item 3). [prompt] is the
     * schema's `question_groups[].prompt`, or null for the catch-all of ungrouped fields.
     */
    data class AskOptional(val prompt: String?, val fields: List<FormField>) : PolicyAction

    /** Everything required is present (filled or explicitly unknown): ask to file. */
    data object Confirm : PolicyAction
}

/** Per-draft memory the policy needs on top of the form itself. */
class PolicyMemory {
    /** Unanswered asks per required field — bounds the loop (one rephrase, then offer unknown). */
    val attempts = mutableMapOf<String, Int>()

    /** Optional fields already asked once (answered or not): never asked again. */
    val closedOptional = mutableSetOf<String>()
}

/**
 * The mandatory-field gate and question order (docs/DESIGN.md §3.4, §5.2). Pure function
 * of the schema, the form and [PolicyMemory]: no field ids, counts or orders are
 * hardcoded, so any server-delivered form works.
 */
class DialogPolicy(private val schema: FormSchema) {

    fun next(state: FormState, memory: PolicyMemory): PolicyAction {
        // 1. Required fields, in schema order, one question per turn.
        for (field in schema.requiredFields) {
            val value = state.get(field.id)
            when (value.status) {
                FieldStatus.FILLED, FieldStatus.UNKNOWN -> continue
                FieldStatus.AMBIGUOUS -> return PolicyAction.ConfirmValue(field, value.value)
                FieldStatus.EMPTY -> {
                    val attempt = memory.attempts[field.id] ?: 0
                    val offerUnknown = attempt >= MAX_ATTEMPTS && schema.behavior.allowUnknownRequired
                    return if (offerUnknown) PolicyAction.OfferUnknown(field) else PolicyAction.AskField(field, attempt)
                }
            }
        }

        // 2. Optional ask_once fields, asked at most once each.
        nextOptional(state, memory)?.let { return it }

        // 3. Gate passes. Submission still needs the user's explicit "yes" — even when a
        //    schema sets confirm_before_submit=false, the assistant never files on its own.
        return PolicyAction.Confirm
    }

    private fun nextOptional(state: FormState, memory: PolicyMemory): PolicyAction.AskOptional? {
        if (!schema.behavior.askOptionalOnce) return null
        val open = schema.fields.filter {
            !it.required && it.askOnce && !state.isSatisfied(it.id) && it.id !in memory.closedOptional
        }
        if (open.isEmpty()) return null

        if (schema.behavior.optionalMode == OPTIONAL_MODE_INDIVIDUAL) {
            val field = open.first()
            return PolicyAction.AskOptional(field.question, listOf(field))
        }

        for (group in schema.questionGroups) {
            val members = open.filter { field ->
                if (group.fields.isNotEmpty()) field.id in group.fields else field.group == group.id
            }
            if (members.isNotEmpty()) return PolicyAction.AskOptional(group.prompt, members)
        }
        // Anything not covered by a group goes into one natural catch-all question.
        return PolicyAction.AskOptional(prompt = null, fields = open)
    }

    companion object {
        /** Ask, then one rephrase, then offer "not specified" (§5.2, approved behavior). */
        const val MAX_ATTEMPTS = 2
        const val OPTIONAL_MODE_INDIVIDUAL = "individual"
    }
}
