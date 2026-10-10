package com.megathon.incidentassistant.form

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the report JSON — the §8 output contract, ready to be POSTed to any API
 * (docs/DESIGN.md §8/§8.1). One object per report, with values, provenance and the
 * remaining missing information.
 */
object ReportJson {

    private val pretty = Json { prettyPrint = true }

    fun build(
        schema: FormSchema,
        state: FormState,
        reportId: String,
        nowIso: String,
        status: String = "submitted",
        /** Stage timings (§8 `metrics`), e.g. `llm_ms`. Omitted from the JSON when empty. */
        metrics: Map<String, Long> = emptyMap(),
    ): String {
        val root = buildJsonObject {
            put("report_id", reportId)
            put("schema_version", schema.version)
            put("form_id", schema.id)
            put("status", status)
            put("created_at", nowIso)
            putJsonObject("fields") {
                for (field in schema.fields) {
                    val value = state.get(field.id)
                    putJsonObject(field.id) {
                        put("label", field.label)
                        put("value", value.value.takeIf { it.isNotBlank() })
                        put("status", value.status.name.lowercase())
                        put("confidence", value.confidence)
                        put("evidence", value.evidence.takeIf { it.isNotBlank() })
                        put("utterance", value.utterance)
                    }
                }
            }
            putJsonArray("missing_information") {
                // Required fields still empty, plus those the user explicitly could not
                // answer ("not specified", §5.2) — both are flagged for the recipient.
                for (field in schema.requiredFields) {
                    if (state.get(field.id).status != FieldStatus.FILLED) add(field.id)
                }
            }
            if (metrics.isNotEmpty()) {
                putJsonObject("metrics") {
                    metrics.forEach { (name, value) -> put(name, value) }
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }
}
