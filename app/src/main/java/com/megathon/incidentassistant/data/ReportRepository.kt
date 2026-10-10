package com.megathon.incidentassistant.data

import com.megathon.incidentassistant.domain.IncidentReport
import java.time.ZonedDateTime
import java.util.UUID

data class SubmittedReport(val id: String, val submittedAt: ZonedDateTime, val report: IncidentReport)

interface ReportRepository {
    suspend fun submit(report: IncidentReport): SubmittedReport
}

/**
 * Keeps reports in memory only.
 * TODO: persist locally (Room / SQLite / encrypted file). Still no network.
 */
class InMemoryReportRepository : ReportRepository {
    private val store = mutableListOf<SubmittedReport>()

    override suspend fun submit(report: IncidentReport): SubmittedReport {
        val id = "INC-" + UUID.randomUUID().toString().take(6).uppercase()
        val saved = SubmittedReport(id, ZonedDateTime.now(), report)
        store += saved
        return saved
    }
}
