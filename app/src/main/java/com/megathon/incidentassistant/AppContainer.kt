package com.megathon.incidentassistant

import android.app.Application
import android.content.Context
import com.megathon.incidentassistant.ai.AndroidSpeechSynthesizer
import com.megathon.incidentassistant.ai.GemmaGenieXLlm
import com.megathon.incidentassistant.ai.GemmaModels
import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.ai.MeloTtsSpeechSynthesizer
import com.megathon.incidentassistant.ai.PlaceholderLlm
import com.megathon.incidentassistant.ai.PlaceholderSpeechToText
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechToText
import com.megathon.incidentassistant.ai.TtsModels
import com.megathon.incidentassistant.ai.WhisperModelsAvailable
import com.megathon.incidentassistant.ai.WhisperSpeechToText
import com.megathon.incidentassistant.data.InMemoryReportRepository
import com.megathon.incidentassistant.data.ReportRepository
import com.megathon.incidentassistant.net.BundledFormSource
import com.megathon.incidentassistant.net.FormSource
import com.megathon.incidentassistant.net.HttpFormSource
import com.megathon.incidentassistant.net.HttpReportSubmitter
import com.megathon.incidentassistant.net.ReportSubmitter

object AppConfig {
    /**
     * Server that serves form definitions and receives reports (contract:
     * docs/PROXY_API.md). **Optional.**
     *
     * Blank (default): the bundled sample form (`assets/forms/incident_report.json`) is
     * auto-loaded and the app works fully offline — no server needed for the demo.
     *
     * Set it to use a live or mock server, e.g.:
     *   http://127.0.0.1:8787  (device with `adb reverse tcp:8787 tcp:8787`, mock running)
     *   http://10.0.2.2:8787   (emulator)
     * If the server is unreachable, the sample form is used instead.
     */
    const val SERVER_BASE_URL = ""

    const val DEFAULT_FORM_ID = "incident_report"
}

/**
 * Tiny manual dependency injection. THIS is the one place you swap placeholders for real
 * models: Whisper (SpeechToText), Genie/Qwen/Llama (LlmBackend), MeloTTS
 * (SpeechSynthesizer), plus the server endpoints.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Use the real Qualcomm Whisper SDK when its model files are on the device, otherwise
     * keep the placeholder so the app still runs on a phone that has never had models
     * pushed to it (`scripts/push-whisper-models.sh`).
     */
    val speechToText: SpeechToText =
        if (WhisperModelsAvailable.check(appContext)) WhisperSpeechToText(appContext)
        else PlaceholderSpeechToText()

    /**
     * Use on-device Gemma 4 E2B (GenieX llama.cpp runtime) when its GGUF is on the device
     * (`scripts/push-gemma-model.sh`), otherwise the scripted placeholder so the app still
     * runs end to end on a phone without the 3 GB model. See docs/LLM.md.
     */
    val llm: LlmBackend =
        GemmaModels.resolve(appContext)?.let { GemmaGenieXLlm(appContext, it) }
            ?: PlaceholderLlm()

    /**
     * Use the real Qualcomm MeloTTS SDK when the combined model is on the device, otherwise
     * fall back to Android's built-in TTS service.
     */
    val synthesizer: SpeechSynthesizer =
        if (TtsModels.resolve(appContext) != null) MeloTtsSpeechSynthesizer(appContext)
        else AndroidSpeechSynthesizer(appContext)
    val repository: ReportRepository = InMemoryReportRepository()

    /** Bundled sample by default; the server (when configured) replaces it. */
    val formSource: FormSource =
        HttpFormSource(AppConfig.SERVER_BASE_URL, BundledFormSource(appContext))

    val reportSubmitter: ReportSubmitter = HttpReportSubmitter(AppConfig.SERVER_BASE_URL)

    /** For logging / UI: which ASR implementation is actually wired up. */
    val usingRealAsr: Boolean = speechToText is WhisperSpeechToText

    /** Drives the "demo mode" banner: true while extraction is the scripted placeholder. */
    val usingPlaceholderLlm: Boolean = llm is PlaceholderLlm
}

class IncidentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}