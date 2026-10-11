package com.megathon.incidentassistant.net

import android.content.Context
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Form + report server client (contract: docs/PROXY_API.md).
 *
 *   GET  {base}/api/v1/forms/{id}   → dynamic form definition (an HTML form page, or JSON)
 *   POST {base}/api/v1/reports      → finished report JSON
 *
 * The server is **optional**. The app ships a sample form (assets/forms) that auto-loads
 * with zero configuration, so opening the app works fully offline. When
 * [AppConfig.SERVER_BASE_URL] is set, the server definition is fetched at start / Refresh
 * and the finished report is POSTed back to it; if the server is unreachable, the sample
 * form is used instead. The AI pipeline stays on-device.
 */
interface FormSource {
    /** Fetches the form definition. Throws when nothing is available. */
    suspend fun fetchForm(formId: String): FormSchema
}

/**
 * The form pushed to the device (`<external files>/forms/`, see scripts/push-form.sh): an
 * HTML (or JSON) form document. Whatever form is there is the form — the app never asks
 * which one; with none pushed, [fallback] (the bundled form) is used. Lets a new form be
 * used with "Reload the form" (or a restart) and no rebuild.
 */
class DeviceFormSource(
    private val dir: File?,
    private val fallback: FormSource,
) : FormSource {
    override suspend fun fetchForm(formId: String): FormSchema = withContext(Dispatchers.IO) {
        // The most recently pushed form document (push-form.sh keeps one at a time).
        val page = dir?.listFiles { f -> f.isFile && f.extension.lowercase() in FormDocuments.EXTENSIONS }
            .orEmpty().maxByOrNull { it.lastModified() }
            ?: return@withContext fallback.fetchForm(formId)
        FormDocuments.parse(page.readText(), page.nameWithoutExtension)
    }
}

/**
 * The form shipped with the app (copied from forms/ at build time): `assets/forms/{formId}.html`
 * (an ordinary HTML form, parsed by [com.megathon.incidentassistant.form.HtmlFormParser]), else
 * `{formId}.json`.
 */
class BundledFormSource(
    private val context: Context,
    private val assetFolder: String = "forms",
) : FormSource {
    override suspend fun fetchForm(formId: String): FormSchema = withContext(Dispatchers.IO) {
        val available = context.assets.list(assetFolder).orEmpty()
        val file = FormDocuments.EXTENSIONS.map { "$formId.$it" }.firstOrNull { it in available }
            ?: throw IOException("no bundled form $formId in assets/$assetFolder")
        FormDocuments.parse(read(file), formId)
    }

    private fun read(name: String) =
        context.assets.open("$assetFolder/$name").bufferedReader().use { it.readText() }
}

/**
 * Server path (optional). With a blank [baseUrl] this simply serves the bundled sample;
 * with a URL it fetches the definition from the server and falls back to the bundled
 * sample when the server is unreachable.
 */
class HttpFormSource(
    private val baseUrl: String,
    private val fallback: FormSource,
    /** A form page to load directly (e.g. the published HTML form); wins over [baseUrl]. */
    private val formUrl: String = "",
) : FormSource {
    override suspend fun fetchForm(formId: String): FormSchema = withContext(Dispatchers.IO) {
        val url = when {
            formUrl.isNotBlank() -> formUrl
            baseUrl.isNotBlank() -> "${baseUrl.trimEnd('/')}/api/v1/forms/$formId"
            else -> return@withContext fallback.fetchForm(formId)
        }
        runCatching {
            FormDocuments.parse(httpGet(url), formId)
        }.getOrElse {
            fallback.fetchForm(formId)
        }
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 5000
            setRequestProperty("Accept", "text/html, application/json")
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

/** POSTs the report (with an idempotency key) to the configured server, if any. */
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