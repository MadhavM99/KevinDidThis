package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormSchemaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FormSchemaTest {

    private val demoJson = """
        {
          "id": "demo",
          "title": "Demo",
          "version": "2.0",
          "fields": [
            {
              "id": "severity", "label": "Severity", "type": "enum", "required": true,
              "question": "How severe?",
              "keywords": ["how bad"],
              "options": [
                { "value": "low", "label": "Low", "aliases": ["minor"] },
                { "value": "high", "label": "High", "aliases": ["severe"] }
              ]
            },
            { "id": "notes", "label": "Notes", "type": "textarea", "ask_once": true }
          ],
          "question_groups": [ { "id": "extra", "prompt": "Anything else?" } ],
          "behavior": { "optional_mode": "combined" }
        }
    """.trimIndent()

    @Test
    fun parsesDynamicFormMetadata() {
        val schema = FormSchemaParser.parse(demoJson)
        assertEquals("demo", schema.id)
        assertEquals(2, schema.fields.size)
        assertEquals(FieldType.ENUM, schema.field("severity")?.type)
        assertTrue(schema.field("severity")?.required == true)
        assertEquals(listOf("minor"), schema.field("severity")?.options?.first()?.aliases)
        assertTrue(schema.field("notes")?.askOnce == true)
        assertEquals(1, schema.questionGroups.size)
        assertEquals(listOf("severity"), schema.requiredFields.map { it.id })
    }

    @Test
    fun vocabularyCollectsEverythingTheModelMayHear() {
        val schema = FormSchemaParser.parse(demoJson)
        val vocabulary = schema.field("severity")!!.vocabulary()
        assertTrue(
            vocabulary.containsAll(
                listOf("severity", "Severity", "how bad", "low", "Low", "minor", "severe")
            )
        )
    }

    @Test
    fun bundledIncidentFormIsValid() {
        val file = File("src/main/assets/forms/incident_report.json")
        assumeTrue("bundled asset not found (test working dir is not the app module)", file.exists())
        val schema = FormSchemaParser.parse(file.readText())
        assertEquals("incident_report", schema.id)
        assertEquals(11, schema.fields.size)
        assertEquals(6, schema.requiredFields.size)
        assertEquals("open", schema.field("action_status")?.defaultValue)
        assertEquals(2, schema.questionGroups.size)
    }

    @Test
    fun bundledWorkplaceFormIsValid() {
        val file = File("src/main/assets/forms/workplace_incident.json")
        assumeTrue("bundled asset not found (test working dir is not the app module)", file.exists())
        val schema = FormSchemaParser.parse(file.readText())
        assertEquals("workplace_incident", schema.id)
        assertEquals(18, schema.fields.size)
        assertEquals(8, schema.requiredFields.size)
        assertEquals(3, schema.questionGroups.size)
        assertEquals(FieldType.BOOLEAN, schema.field("reportable_to_authority")?.type)
        assertEquals(FieldType.NUMBER, schema.field("lost_time_days")?.type)
    }
}
