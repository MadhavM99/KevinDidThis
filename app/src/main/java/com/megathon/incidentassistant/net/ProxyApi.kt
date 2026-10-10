package com.megathon.incidentassistant.net

import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormSchemaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server API client (contract: docs/PROXY_API.md).
 *
 *   GET  {base}/api/v1/forms/{id}   → dynamic form definition (JSON)
 *   POST {base}/api/v1/reports      → finished report JSON
 *
 * The app is a pure client and ships **no form definitions**: the server is the only
 * source of forms, fetched at start/refresh; a failed fetch surfaces an error for the
 * user to retry. The AI pipeline stays on-device. `tools/mock_proxy.py` plays the
 * server's role during development (fixtures in `tools/forms/`).
 */
interface FormSource {
    /** Fetches the form definition. Throws when the server is unreachable or the payload invalid. */
    suspend fun fetchForm(formId: String): FormSchema
}

/** Fetches definitions over HTTP from [baseUrl]. No local fallback by design (§3.5). */
class HttpFormSource(private val baseUrl: String) : FormSource {
    override suspend fun fetchForm(formId: String): FormSchema = withContext(Dispatchers.IO) {
        val text = httpGet("${baseUrl.trimEnd('/')}/api/v1/forms/$formId")
        FormSchemaParser.parse(text)
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("form server HTTP $code: ${body.take(200)}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

data class SubmitResult(
    val ok: Boolean,
    val reportId: String? = null,
    val error: String? = null,
)

interface ReportSubmitter {
    suspend fun submit(reportJson: String, reportId: String): SubmitResult
}

/** POSTs the report with an idempotency key so retries never duplicate a report. */
class HttpReportSubmitter(private val baseUrl: String) : ReportSubmitter {
    override suspend fun submit(reportJson: String, reportId: String): SubmitResult =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext SubmitResult(ok = false, error = "no server configured")
            }
            runCatching {
                val connection =
                    (URL("${baseUrl.trimEnd('/')}/api/v1/reports").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 3000
                        readTimeout = 8000
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Idempotency-Key", reportId)
                    }
                connection.outputStream.use { it.write(reportJson.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                SubmitResult(
                    ok = code in 200..299,
                    reportId = reportId,
                    error = if (code in 200..299) null else "HTTP $code: ${body.take(200)}",
                )
            }.getOrElse { throwable ->
                SubmitResult(ok = false, error = throwable.message ?: "network error")
            }
        }
}