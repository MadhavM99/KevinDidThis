package com.megathon.incidentassistant.ai

import com.megathon.incidentassistant.domain.FieldKey
import com.megathon.incidentassistant.domain.IncidentReport
import com.megathon.incidentassistant.domain.ToolCall
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/**
 * PLACEHOLDER - no LLM involved.
 *  - After a follow-up question: the answer is copied straight into the asked field.
 *  - Otherwise: returns the example output from the problem statement
 *    (type = Water leak, location = Conference Room 2, time = 10 AM, action = isolated).
 *
 * Severity is intentionally NOT filled, so you can see the "mandatory field missing" loop.
 *
 * TODO(real implementation):
 *  1. Build a prompt: system instructions + the tool schema (update_incident_form) +
 *     the current form values + [now] + the transcript.
 *  2. Run the on-device LLM, parse its tool-call JSON.
 *  3. Return it as ToolCall.UpdateFields(...).
 */
class PlaceholderIncidentExtractor : IncidentExtractor {
    override suspend fun prepare() {
        // TODO: load LLM + choose delegate.
    }

    override suspend fun extract(
        transcript: String,
        current: IncidentReport,
        expectedField: FieldKey?,
        now: ZonedDateTime,
    ): List<ToolCall> {
        delay(800) // pretend to think
        val fields: Map<FieldKey, String> = if (expectedField != null) {
            mapOf(expectedField to transcript.trim())
        } else {
            mapOf(
                FieldKey.INCIDENT_TYPE to "Water leak",
                FieldKey.LOCATION to "Conference Room 2",
                FieldKey.DATE_TIME to "Today, 10:00 AM",
                FieldKey.DESCRIPTION to transcript.trim(),
                FieldKey.ACTIONS_TAKEN to "Area isolated by maintenance",
            )
        }
        return listOf(ToolCall.UpdateFields(fields))
    }
}
