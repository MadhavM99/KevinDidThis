package com.megathon.incidentassistant.data

import java.time.ZonedDateTime
import java.util.UUID

data class SubmittedReport(
    val id: String,
    val submittedAt: ZonedDateTime,
    val reportJson: String,
)

interface ReportRepository {
    /**
     * Saves the report locally. The local copy is the source of truth; delivery to the
     * proxy happens separately (net/ReportSubmitter) and can be retried later (§8.1).
     */
    suspend fun submit(reportId: String, reportJson: String): SubmittedReport
}

/** In-memory for now; swap for Room + an outbox later without touching UI code. */
class InMemoryReportRepository : ReportRepository {
    private val store = mutableListOf<SubmittedReport>()

    override suspend fun submit(reportId: String, reportJson: String): SubmittedReport {
        val saved = SubmittedReport(reportId, ZonedDateTime.now(), reportJson)
        store += saved
        return saved
    }
}

object ReportIds {
    fun newReportId(): String = "RPT-" + UUID.randomUUID().toString().take(8).uppercase()
}
