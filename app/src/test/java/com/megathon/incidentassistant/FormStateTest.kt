package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormStateTest {

    private val schema = FormSchemaParser.parse(
        """
        {
          "id": "demo",
          "fields": [
            { "id": "severity", "label": "Severity", "type": "enum", "required": true,
              "options": [
                { "value": "low", "label": "Low", "aliases": ["minor"] },
                { "value": "high", "label": "High", "aliases": ["severe"] }
              ] },
            { "id": "notes", "label": "Notes", "type": "textarea", "required": true }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun enumAliasIsNormalizedToCanonicalValue() {
        val state = FormState(schema)
        val result = state.apply("severity", "it was minor", 0.9, "minor", UpdateStatus.INFERRED)
        assertTrue(result.accepted)
        assertEquals("low", state.text("severity"))
        assertEquals(FieldStatus.FILLED, state.get("severity").status)
    }

    @Test
    fun unmatchedEnumIsRejectedSoTheAssistantCanReAsk() {
        val state = FormState(schema)
        val result = state.apply("severity", "banana", 0.9, null, UpdateStatus.INFERRED)
        assertFalse(result.accepted)
        assertTrue(state.missingRequired().any { it.id == "severity" })
    }

    @Test
    fun gateNeedsEveryRequiredField() {
        val state = FormState(schema)
        assertFalse(state.gatePasses())
        state.apply("severity", "high", 0.9, null, UpdateStatus.ANSWERED)
        assertFalse(state.gatePasses())
        state.apply("notes", "Water on the floor", 0.8, null, UpdateStatus.INFERRED)
        assertTrue(state.gatePasses())
    }

    @Test
    fun unknownCountsAsPresentButNeverOverwritesAValue() {
        val state = FormState(schema)
        state.markUnknown("notes")
        assertEquals(FieldStatus.UNKNOWN, state.get("notes").status)
        assertFalse(state.missingRequired().any { it.id == "notes" })

        state.apply("severity", "low", 0.9, null, UpdateStatus.ANSWERED)
        val blocked = state.markUnknown("severity")
        assertFalse(blocked.accepted)
        assertEquals("low", state.text("severity"))
    }

    @Test
    fun lowConfidenceConflictKeepsExistingValue() {
        val state = FormState(schema)
        state.apply("severity", "high", 0.9, null, UpdateStatus.INFERRED)
        val result = state.apply("severity", "low", 0.4, null, UpdateStatus.INFERRED)
        assertFalse(result.accepted)
        assertEquals("high", state.text("severity"))
    }

    @Test
    fun explicitAnswerCorrectsExistingValue() {
        val state = FormState(schema)
        state.apply("severity", "high", 0.9, null, UpdateStatus.INFERRED)
        val result = state.apply("severity", "low", 0.6, "actually low", UpdateStatus.ANSWERED)
        assertTrue(result.accepted)
        assertEquals("low", state.text("severity"))
    }

    @Test
    fun directEditIsAlwaysAccepted() {
        val state = FormState(schema)
        state.setDirect("notes", "  Two people slipped  ")
        assertEquals("Two people slipped", state.text("notes"))
        assertEquals(FieldStatus.FILLED, state.get("notes").status)
    }

    // ---------- realistic demo-form types (workplace_incident) ----------

    private val typedSchema = FormSchemaParser.parse(
        """
        {
          "id": "typed",
          "fields": [
            { "id": "reportable", "label": "Reportable", "type": "boolean" },
            { "id": "days", "label": "Days", "type": "number" },
            { "id": "people", "label": "People", "type": "string_list" }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun booleanAnswersAreNormalized() {
        val state = FormState(typedSchema)
        state.apply("reportable", "yes we did", 0.9, null, UpdateStatus.ANSWERED)
        assertEquals("yes", state.text("reportable"))

        val rejected = state.apply("reportable", "maybe later", 0.9, null, UpdateStatus.ANSWERED)
        assertFalse(rejected.accepted)
        assertEquals("yes", state.text("reportable"))
    }

    @Test
    fun numbersAndListsAreNormalized() {
        val state = FormState(typedSchema)
        state.apply("days", "about 3 days off", 0.8, null, UpdateStatus.INFERRED)
        assertEquals("3", state.text("days"))

        state.apply("people", "Alice, Bob and Carol", 0.8, null, UpdateStatus.INFERRED)
        assertEquals("Alice; Bob; Carol", state.text("people"))
    }
}
