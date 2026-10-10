package com.megathon.incidentassistant

import com.megathon.incidentassistant.domain.FieldKey
import com.megathon.incidentassistant.domain.IncidentReport
import com.megathon.incidentassistant.domain.IncidentTools
import com.megathon.incidentassistant.domain.Severity
import com.megathon.incidentassistant.domain.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentLogicTest {

    @Test
    fun emptyReportMissesAllMandatoryFields() {
        val missing = IncidentReport().missingMandatory()
        assertEquals(
            listOf(FieldKey.INCIDENT_TYPE, FieldKey.SEVERITY, FieldKey.LOCATION, FieldKey.DATE_TIME, FieldKey.DESCRIPTION),
            missing,
        )
    }

    @Test
    fun toolCallFillsFieldsAndNormalisesSeverity() {
        val report = IncidentTools.apply(
            IncidentReport(),
            listOf(ToolCall.UpdateFields(mapOf(FieldKey.LOCATION to " Conference Room 2 ", FieldKey.SEVERITY to "pretty serious"))),
        )
        assertEquals("Conference Room 2", report.get(FieldKey.LOCATION))
        assertEquals(Severity.HIGH.name, report.get(FieldKey.SEVERITY))
    }

    @Test
    fun unknownSeverityIsIgnoredSoAssistantAsksAgain() {
        val report = IncidentTools.apply(
            IncidentReport(),
            listOf(ToolCall.UpdateFields(mapOf(FieldKey.SEVERITY to "banana"))),
        )
        assertTrue(FieldKey.SEVERITY in report.missingMandatory())
    }
}
