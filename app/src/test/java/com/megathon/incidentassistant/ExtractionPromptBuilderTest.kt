package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.ConversationTurn
import com.megathon.incidentassistant.form.ExtractionPromptBuilder
import com.megathon.incidentassistant.form.PendingQuestion
import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.UpdateStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionPromptBuilderTest {

    private val schema = FormSchemaParser.parse(
        """
        {
          "id": "demo",
          "title": "Demo Form",
          "fields": [
            { "id": "severity", "label": "Severity", "type": "enum", "required": true,
              "question": "How severe?",
              "keywords": ["how bad"],
              "options": [ { "value": "low", "label": "Low", "aliases": ["minor"] } ] },
            { "id": "location", "label": "Location", "type": "text" }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun promptAdvertisesTheFormVocabulary() {
        val state = FormState(schema)
        state.apply("location", "Room 2", 0.9, null, UpdateStatus.INFERRED)
        val prompt = ExtractionPromptBuilder.systemPrompt(schema, state, "2026-10-10T10:00:00")
        assertTrue(prompt.contains("id=severity"))
        assertTrue(prompt.contains("REQUIRED"))
        assertTrue(prompt.contains("options=low"))
        assertTrue(prompt.contains("keywords=how bad"))
        assertTrue(prompt.contains("location: Room 2"))
        assertTrue(prompt.contains("severity: (missing)"))
    }

    @Test
    fun userPromptCarriesThePendingQuestion() {
        val prompt = ExtractionPromptBuilder.userPrompt(schema.field("severity"), "medium")
        assertTrue(prompt.contains("expects an answer for field id=severity"))
        assertTrue(prompt.contains("USER SAID: \"medium\""))
    }

    @Test
    fun promptCarriesAliasesQuestionsAndEvidence() {
        val state = FormState(schema)
        state.apply("location", "Room 2", 0.9, "near room 2", UpdateStatus.INFERRED)
        val prompt = ExtractionPromptBuilder.systemPrompt(schema, state, "2026-10-10T10:00:00")
        assertTrue(prompt.contains("low (minor)")) // a label that only repeats the id is left out
        assertTrue(prompt.contains("asked as \"How severe?\""))
        assertTrue(prompt.contains("location: Room 2 (from \"near room 2\")"))
    }

    @Test
    fun groupQuestionAndHistoryReachTheModel() {
        val pending = PendingQuestion("Anything else?", schema.fields)
        val history = listOf(ConversationTurn("assistant", "How severe?"), ConversationTurn("user", "low"))
        val prompt = ExtractionPromptBuilder.userPrompt(pending, "Bob saw it", history)
        assertTrue(prompt.contains("expects answers for field ids=severity,location"))
        assertTrue(prompt.contains("assistant: How severe?"))
        assertTrue(prompt.endsWith("USER SAID: \"Bob saw it\""))
    }
}
