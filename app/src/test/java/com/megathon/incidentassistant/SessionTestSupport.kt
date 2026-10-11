package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechToText
import com.megathon.incidentassistant.data.ReportRepository
import com.megathon.incidentassistant.data.SubmittedReport
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.net.ReportSubmitter
import com.megathon.incidentassistant.net.SubmitResult
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.SessionPhrases
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Shared fakes for the host-side session tests (no device, no audio, no model). */

const val NOW = "2026-10-10T14:30:00+05:30"

/** The dev form served by tools/mock_proxy.py — the same definition the app fetches. */
fun incidentSchema(): FormSchema = FormSchemaParser.parse(formFixture("incident_report.json").readText())

fun formFixture(name: String): File {
    val file = listOf(File("../tools/forms/$name"), File("../../tools/forms/$name"), File("tools/forms/$name"))
        .firstOrNull { it.exists() }
    checkNotNull(file) { "form fixture $name not found from ${File(".").absolutePath}" }
    return file
}

/** A test form page from `src/test/resources/forms/` (independent of the live tools/forms page). */
fun testFormPage(name: String): String =
    checkNotNull(SessionFixture::class.java.classLoader!!.getResource("forms/$name")) { "test form $name missing" }.readText()

/** One field update as the model would emit it. */
data class U(
    val field: String,
    val value: String,
    val confidence: Double = 0.9,
    val evidence: String = "",
    val status: String = "inferred",
)

/** Raw model text: `<tool_call>{"tool":"record_fields",...}</tool_call>`, properly escaped. */
fun recordFields(vararg updates: U): String {
    val obj = buildJsonObject {
        put("tool", "record_fields")
        putJsonObject("arguments") {
            putJsonArray("updates") {
                updates.forEach { u ->
                    addJsonObject {
                        put("field", u.field)
                        put("value", u.value)
                        put("confidence", u.confidence)
                        put("evidence", u.evidence)
                        put("status", u.status)
                    }
                }
            }
        }
    }
    return "<tool_call>$obj</tool_call>"
}

/**
 * Deterministic stand-in for Gemma: answers each call from [responder], records every
 * prompt and the highest number of overlapping calls (concurrency check).
 */
class ScriptedLlm(private val responder: suspend (prompt: String, call: Int) -> String) : LlmBackend {
    override val id = "scripted"
    val prompts = mutableListOf<String>()
    private val active = AtomicInteger(0)
    @Volatile var maxConcurrent = 0
        private set

    val calls: Int get() = prompts.size

    override suspend fun generate(prompt: String): String {
        val now = active.incrementAndGet()
        maxConcurrent = maxOf(maxConcurrent, now)
        try {
            val index = synchronized(prompts) { prompts += prompt; prompts.size - 1 }
            return responder(prompt, index)
        } finally {
            active.decrementAndGet()
        }
    }

    companion object {
        /** Replies in order; fails the test if the session calls the model more often. */
        fun of(vararg outputs: String) = ScriptedLlm { _, i ->
            check(i < outputs.size) { "unexpected LLM call #${i + 1}" }
            outputs[i]
        }
    }
}

/** Tagged, assertion-friendly wording. Production wording lives in strings.xml. */
class TestPhrases : SessionPhrases {
    override fun askByLabel(label: String) = "What is the $label?"
    override fun askAgain(label: String, question: String, options: List<String>) =
        if (options.isEmpty()) "AGAIN: $question" else "AGAIN: $label? Say ${orList(options)}."
    override fun askAgainCancelHint() = "(or say cancel)"
    override fun offerUnknown(label: String) = "Mark $label as not specified?"
    override fun confirmValue(label: String, value: String) = "Is $label $value?"
    override fun askOptional(labels: List<String>) = "Anything about ${orList(labels)}?"
    override fun askTogether(phrases: List<String>) = "TELL ME: ${phrases.joinToString(" | ")}?"
    override fun summaryLine(label: String, value: String) = "$label: $value."
    override fun notSpecified() = "not specified"
    override fun confirmReport(summary: String) = "SUMMARY $summary File it?"
    override fun whatToChange() = "What should I change?"
    override fun formReady() = "FORM READY"
    override fun noChangeHeard() = "No change heard."
    override fun didNotUnderstand() = "Did not understand."
    override fun didNotHear() = "Did not hear."
    override fun redirectToIncident() = "REDIRECT: describe the incident."
    override fun redirectBeforeQuestion() = "REDIRECT."
    override fun notSureItsAnIncident() = "UNSURE."
    override fun offTopicPaused() = "PAUSED."
    override fun confirmCancel() = "Discard?"
    override fun cancelled() = "Discarded."
    override fun missingBeforeSubmit(labels: List<String>) = "Missing: ${labels.joinToString(", ")}."
    override fun submitted(reportId: String) = "Filed $reportId."
    override fun savedNotDelivered(reportId: String) = "Saved $reportId, not delivered."
    override fun saveFailed() = "Save failed."
    override fun alreadyFiled(reportId: String) = "Already filed $reportId."
    override fun sessionClosed() = "Closed."
    override fun orList(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") + ", or " + items.last()
    }
}

class RecordingRepository(private val failWith: Exception? = null) : ReportRepository {
    val saved = mutableListOf<Pair<String, String>>()
    override suspend fun submit(reportId: String, reportJson: String): SubmittedReport {
        failWith?.let { throw it }
        saved += reportId to reportJson
        return SubmittedReport(reportId, ZonedDateTime.now(), reportJson)
    }
}

class RecordingSubmitter(private val ok: Boolean = true) : ReportSubmitter {
    val sent = mutableListOf<String>()
    override suspend fun submit(reportJson: String, reportId: String): SubmitResult {
        sent += reportId
        return SubmitResult(ok = ok, reportId = reportId, error = if (ok) null else "HTTP 503")
    }
}

/** Kevin's ASR seam, scripted: each listen/stop cycle yields the next transcript. */
class ScriptedAsr(private val transcripts: List<String>) : SpeechToText {
    private var next = 0
    var listening = false
        private set
    override val partialTranscript: StateFlow<String> = MutableStateFlow("")
    override val utteranceFinished: Flow<Unit> = MutableSharedFlow()
    override suspend fun prepare() = Unit
    override suspend fun startListening() {
        check(!listening) { "overlapping recordings" }
        listening = true
    }
    override suspend fun stopListening(): String {
        check(listening) { "stop without start" }
        listening = false
        return transcripts[next++]
    }
    override fun release() = Unit
}

/** Kevin's TTS seam, recorded. */
class RecordingTts : SpeechSynthesizer {
    val spoken = mutableListOf<String>()
    override fun speak(text: String) {
        spoken += text
    }
    override fun stop() = Unit
    override fun shutdown() = Unit
}

class SessionFixture(
    val llm: LlmBackend,
    val schema: FormSchema = incidentSchema(),
    val repository: RecordingRepository = RecordingRepository(),
    val submitter: RecordingSubmitter = RecordingSubmitter(),
    timeoutMs: Long = ExtractionEngine.DEFAULT_TIMEOUT_MS,
) {
    private var ids = 0
    val session = IncidentSession(
        schema = schema,
        extraction = ExtractionEngine(llm, now = { NOW }, timeoutMs = timeoutMs),
        phrases = TestPhrases(),
        repository = repository,
        submitter = submitter,
        now = { NOW },
        newReportId = { "RPT-TEST${++ids}" },
    )
}

/** The golden first utterance (docs/DESIGN.md §4.4) and the output a good model gives for it. */
const val WATER_LEAK = "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."

val WATER_LEAK_EXTRACTION = recordFields(
    U("incident_type", "water_leak", 0.95, "water leak"),
    U("location", "Conference Room 2", 0.9, "near Conference Room 2"),
    U("occurred_at", "2026-10-10T10:00:00", 0.7, "around 10 AM"),
    U("description", "Water leak near Conference Room 2.", 0.8, WATER_LEAK),
    U("immediate_actions", "Area isolated by maintenance", 0.9, "Maintenance has already isolated the area"),
)
