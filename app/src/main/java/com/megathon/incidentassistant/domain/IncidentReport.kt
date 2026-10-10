package com.megathon.incidentassistant.domain

import androidx.annotation.StringRes
import com.megathon.incidentassistant.R

/**
 * Every field on the incident form (from the problem statement).
 * [mandatory] fields drive the "Mandatory fields present?" decision in the pipeline.
 * [askRes] is the follow-up question asked when a mandatory field is missing.
 */
enum class FieldKey(
    val mandatory: Boolean,
    @StringRes val labelRes: Int,
    @StringRes val askRes: Int,
) {
    INCIDENT_TYPE(true, R.string.field_incident_type, R.string.ask_incident_type),
    SEVERITY(true, R.string.field_severity, R.string.ask_severity),
    LOCATION(true, R.string.field_location, R.string.ask_location),
    DATE_TIME(true, R.string.field_date_time, R.string.ask_date_time),
    PERSONS_INVOLVED(false, R.string.field_persons, R.string.ask_persons),
    EQUIPMENT(false, R.string.field_equipment, R.string.ask_equipment),
    DESCRIPTION(true, R.string.field_description, R.string.ask_description),
    ACTIONS_TAKEN(false, R.string.field_actions, R.string.ask_actions),
    WITNESSES(false, R.string.field_witnesses, R.string.ask_witnesses),
    FOLLOW_UP(false, R.string.field_follow_up, R.string.ask_follow_up),
}

enum class Severity(@StringRes val labelRes: Int) {
    LOW(R.string.severity_low),
    MEDIUM(R.string.severity_medium),
    HIGH(R.string.severity_high),
    CRITICAL(R.string.severity_critical);

    companion object {
        /** Forgiving parser so "it was pretty serious" or "high" both work. */
        fun parse(text: String): Severity? {
            val t = text.lowercase()
            return when {
                "critical" in t || "severe" in t || "emergency" in t -> CRITICAL
                "high" in t || "serious" in t -> HIGH
                "medium" in t || "moderate" in t -> MEDIUM
                "low" in t || "minor" in t -> LOW
                else -> null
            }
        }
    }
}

/** Immutable form state. Severity is stored as [Severity.name]; everything else is free text. */
data class IncidentReport(val values: Map<FieldKey, String> = emptyMap()) {
    fun get(key: FieldKey): String = values[key].orEmpty()
    fun with(key: FieldKey, value: String): IncidentReport = copy(values = values + (key to value))
    fun missingMandatory(): List<FieldKey> =
        FieldKey.entries.filter { it.mandatory && get(it).isBlank() }
}
