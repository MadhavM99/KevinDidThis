package com.megathon.incidentassistant

import android.app.Application
import android.content.Context
import com.megathon.incidentassistant.ai.AndroidSpeechSynthesizer
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
import com.megathon.incidentassistant.net.FormSource
import com.megathon.incidentassistant.net.HttpFormSource
import com.megathon.incidentassistant.net.HttpReportSubmitter
import com.megathon.incidentassistant.net.ReportSubmitter

object AppConfig {
    /**
     * Hides the "demo mode" banner while the LLM stage is still a placeholder. ASR is
     * already real (Qualcomm Whisper SDK) as soon as the model files exist on the device —
     * see `ai/WhisperModels` and `scripts/push-whisper-models.sh`.
     */
    const val USING_PLACEHOLDER_MODELS = true

    /**
     * Base URL of the server that serves form definitions and receives reports
     * (contract: docs/PROXY_API.md). The app is a pure client and bundles **no forms**:
     * it fetches the definition at start/refresh, so this must point at a reachable
     * server (run `tools/mock_proxy.py` while developing). Examples:
     *   10.0.2.2:8787 (emulator) · 192.168.x.x:8787 (LAN) · 127.0.0.1:8787 with
     *   `adb reverse tcp:8787 tcp:8787` (USB-connected device).
     * Blank → the form cannot load; the UI shows an error until a refresh succeeds.
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

    val llm: LlmBackend = PlaceholderLlm()                              // TODO: on-device LLM

    /**
     * Use the real Qualcomm MeloTTS SDK when the combined model is on the device, otherwise
     * fall back to Android's built-in TTS service.
     */
    val synthesizer: SpeechSynthesizer =
        if (TtsModels.resolve(appContext) != null) MeloTtsSpeechSynthesizer(appContext)
        else AndroidSpeechSynthesizer(appContext)
    val repository: ReportRepository = InMemoryReportRepository()
    val formSource: FormSource = HttpFormSource(AppConfig.SERVER_BASE_URL)
    val reportSubmitter: ReportSubmitter = HttpReportSubmitter(AppConfig.SERVER_BASE_URL)

    /** For logging / UI: which ASR implementation is actually wired up. */
    val usingRealAsr: Boolean = speechToText is WhisperSpeechToText
}

class IncidentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}