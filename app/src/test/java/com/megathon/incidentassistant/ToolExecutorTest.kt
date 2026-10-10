package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FieldUpdate
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.form.UpdateStatus
import com.megathon.incidentassistant.session.ToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    private val schema = incidentSchema()
    private val executor = ToolExecutor(schema)

    private fun record(vararg updates: FieldUpdate) = listOf(FormToolCall.RecordFields(updates.toList()))

    @Test
    fun unknownFieldIdsAreRejected() {
        val state = FormState(schema)
        val result = executor.execute(record(FieldUpdate("insurance_number", "4471")), state, emptySet())
        assertEquals("unknown field", result.rejected.single().reason)
        assertTrue(state.entries().isEmpty())
    }

    @Test
    fun invalidEnumValuesAreRejected() {
        val state = FormState(schema)
        val result = executor.execute(record(FieldUpdate("severity", "catastrophic")), state, emptySet())
        assertEquals("severity", result.rejected.single().fieldId)
        assertEquals(FieldStatus.EMPTY, state.get("severity").status)
    }

    @Test
    fun aliasesAreMappedToCanonicalValues() {
        val state = FormState(schema)
        executor.execute(record(FieldUpdate("incident_type", "pipe burst", 0.8)), state, emptySet())
        assertEquals("water_leak", state.text("incident_type"))
    }

    @Test
    fun pendingAnswersBecomeExplicit() {
        val state = FormState(schema)
        executor.execute(record(FieldUpdate("severity", "low", 0.3)), state, setOf("severity"))
        assertEquals(FieldStatus.FILLED, state.get("severity").status)
        assertEquals(0.8, state.get("severity").confidence, 1e-9)
    }

    @Test
    fun guessesAboutOtherFieldsStayUnsure() {
        val state = FormState(schema)
        executor.execute(record(FieldUpdate("location", "lobby?", 0.3)), state, setOf("severity"))
        assertEquals(FieldStatus.AMBIGUOUS, state.get("location").status)
    }

    @Test
    fun requiredUnknownIsReportedNotApplied() {
        val state = FormState(schema)
        val result = executor.execute(
            record(FieldUpdate("severity", "", status = UpdateStatus.UNKNOWN)), state, emptySet(),
        )
        assertEquals(listOf("severity"), result.unknownRequested)
        assertEquals(FieldStatus.EMPTY, state.get("severity").status)
    }

    @Test
    fun optionalUnknownIsApplied() {
        val state = FormState(schema)
        val result = executor.execute(
            record(FieldUpdate("witnesses", "", evidence = "skip the witness details", status = UpdateStatus.UNKNOWN)),
            state,
            emptySet(),
        )
        assertEquals(listOf("witnesses"), result.changed)
        assertEquals(FieldStatus.UNKNOWN, state.get("witnesses").status)
    }

    @Test
    fun askUserAndSubmitReportAreNeverExecuted() {
        val state = FormState(schema)
        val result = executor.execute(
            listOf(FormToolCall.AskUser("severity", "How bad?"), FormToolCall.SubmitReport("done")),
            state,
            emptySet(),
        )
        assertEquals(listOf("ask_user", "submit_report"), result.ignoredTools)
        assertTrue(result.changed.isEmpty())
    }

    @Test
    fun badConfidenceAndOversizedValuesAreHandled() {
        val state = FormState(schema)
        val result = executor.execute(
            record(
                FieldUpdate("location", "Room 2", confidence = Double.NaN),
                FieldUpdate("description", "x".repeat(5_000)),
                FieldUpdate("immediate_actions", "   "),
            ),
            state,
            emptySet(),
        )
        assertEquals("Room 2", state.text("location"))
        assertEquals(setOf("description", "immediate_actions"), result.rejected.map { it.fieldId }.toSet())
    }

    @Test
    fun aDifferentLowConfidenceValueNeverOverwritesAnExistingOne() {
        val state = FormState(schema)
        executor.execute(record(FieldUpdate("location", "Conference Room 2", 0.9)), state, emptySet())
        val result = executor.execute(record(FieldUpdate("location", "Room 7", 0.5)), state, emptySet())
        assertEquals("Conference Room 2", state.text("location"))
        assertEquals("location", result.rejected.single().fieldId)
    }
}
