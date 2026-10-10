package com.megathon.incidentassistant.domain

/**
 * "Function / tool calling" step of the pipeline.
 * The on-device LLM is expected to emit calls like these (e.g. as JSON that you parse);
 * the app then executes them with [IncidentTools.apply].
 */
sealed interface ToolCall {
    /** update_incident_form(fields) - fill or overwrite form fields. */
    data class UpdateFields(val fields: Map<FieldKey, String>) : ToolCall
}

object IncidentTools {
    fun apply(report: IncidentReport, calls: List<ToolCall>): IncidentReport {
        var result = report
        for (call in calls) {
            when (call) {
                is ToolCall.UpdateFields -> for ((key, raw) in call.fields) {
                    val value = raw.trim()
                    if (value.isEmpty()) continue
                    result = if (key == FieldKey.SEVERITY) {
                        // Normalise to a known level; ignore values we cannot understand
                        // so the assistant asks again instead of storing garbage.
                        val level = Severity.parse(value) ?: continue
                        result.with(key, level.name)
                    } else {
                        result.with(key, value)
                    }
                }
            }
        }
        return result
    }
}
