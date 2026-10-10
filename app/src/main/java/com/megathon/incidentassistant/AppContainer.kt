package com.megathon.incidentassistant

import android.app.Application
import android.content.Context
import com.megathon.incidentassistant.ai.AndroidSpeechSynthesizer
import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.ai.PlaceholderLlm
import com.megathon.incidentassistant.ai.PlaceholderSpeechToText
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechToText
import com.megathon.incidentassistant.data.InMemoryReportRepository
import com.megathon.incidentassistant.data.ReportRepository
import com.megathon.incidentassistant.net.BundledFormSource
import com.megathon.incidentassistant.net.FormSource
import com.megathon.incidentassistant.net.HttpReportSubmitter
import com.megathon.incidentassistant.net.ProxyFormSource
import com.megathon.incidentassistant.net.ReportSubmitter

object AppConfig {
    /** Flip to false once the real on-device models are plugged in; hides the demo banner. */
    const val USING_PLACEHOLDER_MODELS = true

    /**
     * Base URL of the server that serves form definitions and receives reports
     * (contract: docs/PROXY_API.md). The app is a pure client: GET the form,
     * POST the report. Blank = bundled forms only, no network at all. Examples:
     *   10.0.2.2:8787 (emulator) · 192.168.x.x:8787 (LAN) · 127.0.0.1:8787 with
     *   `adb reverse tcp:8787 tcp:8787` (USB-connected device).
     */
    const val PROXY_BASE_URL = ""

    const val DEFAULT_FORM_ID = "incident_report"
}

/**
 * Tiny manual dependency injection. THIS is the one place you swap placeholders for real
 * models: Whisper (SpeechToText), Genie/Qwen/Llama (LlmBackend), MeloTTS
 * (SpeechSynthesizer), plus the proxy endpoints.
 */
class AppContainer(context: Context) {
    val speechToText: SpeechToText = PlaceholderSpeechToText()          // TODO: on-device ASR
    val llm: LlmBackend = PlaceholderLlm()                              // TODO: on-device LLM
    val synthesizer: SpeechSynthesizer = AndroidSpeechSynthesizer(context)
    val repository: ReportRepository = InMemoryReportRepository()
    val formSource: FormSource =
        ProxyFormSource(AppConfig.PROXY_BASE_URL, BundledFormSource(context.applicationContext))
    val reportSubmitter: ReportSubmitter = HttpReportSubmitter(AppConfig.PROXY_BASE_URL)
}

class IncidentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
