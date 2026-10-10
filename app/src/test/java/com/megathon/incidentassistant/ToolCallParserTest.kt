package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.form.ToolCallParser
import com.megathon.incidentassistant.form.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun parsesToolCallTag() {
        val raw = """<tool_call>{"tool":"record_fields","arguments":{"updates":[{"field":"location","value":"Conference Room 2","confidence":0.9,"status":"inferred"}]}}</tool_call>"""
        val result = ToolCallParser.parse(raw)
        assertEquals(1, result.calls.size)
        val call = result.calls.first() as FormToolCall.RecordFields
        assertEquals("location", call.updates.first().fieldId)
        assertEquals("Conference Room 2", call.updates.first().value)
        assertEquals(0.9, call.updates.first().confidence, 0.0001)
    }

    @Test
    fun parsesFencedJson() {
        val raw = """
            Here is the result:
            ```json
            {"tool":"ask_user","arguments":{"field":"severity","question":"How severe?"}}
            ```
        """.trimIndent()
        val result = ToolCallParser.parse(raw)
        val call = result.calls.first() as FormToolCall.AskUser
        assertEquals("severity", call.fieldId)
        assertEquals("How severe?", call.question)
    }

    @Test
    fun repairsTrailingCommas() {
        val raw = """{"tool":"record_fields","arguments":{"updates":[{"field":"severity","value":"medium","status":"answered",},],},}"""
        val result = ToolCallParser.parse(raw)
        val call = result.calls.first() as FormToolCall.RecordFields
        assertEquals("severity", call.updates.first().fieldId)
        assertEquals(UpdateStatus.ANSWERED, call.updates.first().status)
    }

    @Test
    fun parsesOpenAiToolCalls() {
        val raw = """
            {"choices":[{"message":{"tool_calls":[
              {"function":{"name":"record_fields","arguments":"{\"updates\":[{\"field\":\"incident_type\",\"value\":\"water_leak\",\"confidence\":0.95}]}"}}
            ]}}]}
        """.trimIndent()
        val result = ToolCallParser.parse(raw)
        val call = result.calls.first() as FormToolCall.RecordFields
        assertEquals("incident_type", call.updates.first().fieldId)
        assertEquals("water_leak", call.updates.first().value)
    }

    @Test
    fun parsesMultipleCallsInOneText() {
        val raw = """
            <tool_call>{"tool":"record_fields","arguments":{"updates":[{"field":"location","value":"Room 1"}]}}</tool_call>
            <tool_call>{"tool":"ask_user","arguments":{"question":"Any witnesses?"}}</tool_call>
        """.trimIndent()
        val result = ToolCallParser.parse(raw)
        assertEquals(2, result.calls.size)
    }

    @Test
    fun unknownToolBecomesWarningNotCrash() {
        val result = ToolCallParser.parse("""{"tool":"launch_missiles","arguments":{}}""")
        assertTrue(result.calls.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun emptyOutputIsHandled() {
        val result = ToolCallParser.parse("")
        assertTrue(result.calls.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
