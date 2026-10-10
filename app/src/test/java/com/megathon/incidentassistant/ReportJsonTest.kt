package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.ReportJson
import com.megathon.incidentassistant.form.UpdateStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportJsonTest {

    private val schema = FormSchemaParser.parse(
        """
        {
          "id": "demo",
          "title": "Demo",
          "version": "3.1",
          "fields": [
            { "id": "severity", "label": "Severity", "type": "enum", "required": true,
              "options": [ { "value": "low", "label": "Low" } ] },
            { "id": "notes", "label": "Notes", "type": "textarea", "required": true }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun reportCarriesValuesProvenanceAndMissingInfo() {
        val state = FormState(schema)
        state.apply("severity", "low", 0.9, "it was minor", UpdateStatus.INFERRED)

        val json = ReportJson.build(schema, state, "RPT-TEST", "2026-10-10T14:00:00")
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals("RPT-TEST", root["report_id"]?.jsonPrimitive?.content)
        assertEquals("3.1", root["schema_version"]?.jsonPrimitive?.content)
        assertEquals("submitted", root["status"]?.jsonPrimitive?.content)

        val severity = root["fields"]?.jsonObject?.get("severity")?.jsonObject
        assertEquals("low", severity?.get("value")?.jsonPrimitive?.content)
        assertEquals("it was minor", severity?.get("evidence")?.jsonPrimitive?.content)

        assertTrue(root["missing_information"].toString().contains("notes"))
    }
}
