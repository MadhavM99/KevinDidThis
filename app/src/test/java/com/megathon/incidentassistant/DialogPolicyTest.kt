package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.UpdateStatus
import com.megathon.incidentassistant.session.DialogPolicy
import com.megathon.incidentassistant.session.PolicyAction
import com.megathon.incidentassistant.session.PolicyMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogPolicyTest {

    private fun FormState.fill(vararg pairs: Pair<String, String>) =
        pairs.forEach { (id, v) -> apply(id, v, 0.9, null, UpdateStatus.ANSWERED) }

    @Test
    fun requiredFieldsAreAskedInSchemaOrder() {
        val schema = incidentSchema()
        val state = FormState(schema)
        val policy = DialogPolicy(schema)
        state.fill("incident_type" to "fire", "location" to "Lab")
        val action = policy.next(state, PolicyMemory()) as PolicyAction.AskField
        assertEquals("severity", action.field.id)
        assertEquals(0, action.attempt)
    }

    @Test
    fun exhaustedAttemptsOfferNotSpecified() {
        val schema = incidentSchema()
        val memory = PolicyMemory().apply { attempts["incident_type"] = DialogPolicy.MAX_ATTEMPTS }
        val action = DialogPolicy(schema).next(FormState(schema), memory)
        assertEquals("incident_type", (action as PolicyAction.OfferUnknown).field.id)
    }

    @Test
    fun optionalGroupsFollowRequiredFieldsThenConfirm() {
        val schema = incidentSchema()
        val state = FormState(schema)
        val policy = DialogPolicy(schema)
        val memory = PolicyMemory()
        state.fill(
            "incident_type" to "fire", "severity" to "low", "location" to "Lab",
            "occurred_at" to "10:00", "description" to "Small fire", "immediate_actions" to "Extinguished",
        )
        val people = policy.next(state, memory) as PolicyAction.AskOptional
        assertEquals(listOf("persons_involved", "witnesses"), people.fields.map { it.id })
        assertEquals(schema.group("people")!!.prompt, people.prompt)

        memory.closedOptional += people.fields.map { it.id }
        val followup = policy.next(state, memory) as PolicyAction.AskOptional
        assertEquals(listOf("equipment_involved", "recommended_actions", "action_status"), followup.fields.map { it.id })

        memory.closedOptional += followup.fields.map { it.id }
        assertEquals(PolicyAction.Confirm, policy.next(state, memory))
    }

    @Test
    fun optionalAlreadyAnsweredIsNotAskedAgain() {
        val schema = incidentSchema()
        val state = FormState(schema)
        state.fill(
            "incident_type" to "fire", "severity" to "low", "location" to "Lab",
            "occurred_at" to "10:00", "description" to "Small fire", "immediate_actions" to "Extinguished",
            "persons_involved" to "Ann", "witnesses" to "Bob",
        )
        val action = DialogPolicy(schema).next(state, PolicyMemory()) as PolicyAction.AskOptional
        assertTrue(action.fields.none { it.group == "people" })
    }

    @Test
    fun ungroupedOptionalsShareOneCatchAllQuestion() {
        val schema = FormSchemaParser.parse(
            """{"id":"x","fields":[
              {"id":"a","label":"A","required":true},
              {"id":"b","label":"B","ask_once":true},
              {"id":"c","label":"C","ask_once":true},
              {"id":"d","label":"D"}]}"""
        )
        val state = FormState(schema)
        state.fill("a" to "yes")
        val action = DialogPolicy(schema).next(state, PolicyMemory()) as PolicyAction.AskOptional
        assertNull(action.prompt)
        assertEquals(listOf("b", "c"), action.fields.map { it.id }) // "d" is not ask_once: never asked
    }

    @Test
    fun individualModeAsksOneOptionalAtATime() {
        val schema = FormSchemaParser.parse(
            """{"id":"x","fields":[{"id":"b","label":"B","ask_once":true,"question":"B?"},
               {"id":"c","label":"C","ask_once":true}],"behavior":{"optional_mode":"individual"}}"""
        )
        val action = DialogPolicy(schema).next(FormState(schema), PolicyMemory()) as PolicyAction.AskOptional
        assertEquals("B?", action.prompt)
        assertEquals(listOf("b"), action.fields.map { it.id })
    }

    @Test
    fun aDifferentFormNeedsNoCodeChanges() {
        val schema = FormSchemaParser.parse(formFixture("workplace_incident.json").readText())
        val action = DialogPolicy(schema).next(FormState(schema), PolicyMemory()) as PolicyAction.AskField
        assertEquals(schema.requiredFields.first().id, action.field.id)
    }
}
