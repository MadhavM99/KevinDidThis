package com.megathon.incidentassistant.net

import android.content.Context
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormSchemaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server API client (contract: docs/PROXY_API.md).
 *
 *   GET  {base}/api/v1/forms/{id}   → dynamic form definition (JSON)
 *   POST {base}/api/v1/reports      → finished report JSON
 *
 * The app is a pure client: it fetches the form at start/refresh and posts the finished
 * report. Form definitions are managed server-side; the AI pipeline stays on-device.
 * A blank [baseUrl] means "bundled forms only" (offline demo mode).
 * `tools/mock_proxy.py` plays the server's role during development.
 */
interface FormSource {
    /** Returns the form definition, or throws when nothing is available. */
    suspend fun fetchForm(formId: String): FormSchema
}

/** Always-available fallback: form definitions shipped in `assets/forms/`. */
class BundledFormSource(
    private val context: Context,
    private val assetFolder: String = "forms",
) : FormSource {
    override suspend fun fetchForm(formId: String): FormSchema = withContext(Dispatchers.IO) {
        val text = context.assets.open("$assetFolder/$formId.json")
            .bufferedReader()
            .use { it.readText() }
        FormSchemaParser.parse(text)
    }
}

/** Tries the proxy first (admin-updated definitions), falls back to the bundled asset. */
class ProxyFormSource(
    private val baseUrl: String,
    private val fallback: FormSource,
) : FormSource {
    override suspend fun fetchForm(formId: String): FormSchema {
        if (baseUrl.isBlank()) return fallback.fetchForm(formId)
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                FormSchemaParser.parse(httpGet("${baseUrl.trimEnd('/')}/api/v1/forms/$formId"))
            }.getOrNull()
        }
        return fetched ?: fallback.fetchForm(formId)
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
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
                return@withContext SubmitResult(ok = false, error = "no proxy configured")
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
