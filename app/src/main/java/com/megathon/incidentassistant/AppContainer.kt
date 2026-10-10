package com.megathon.incidentassistant

import android.app.Application
import android.content.Context
import com.megathon.incidentassistant.ai.AndroidSpeechSynthesizer
import com.megathon.incidentassistant.ai.GemmaGenieXLlm
import com.megathon.incidentassistant.ai.GemmaModels
import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.ai.PiperModels
import com.megathon.incidentassistant.ai.PiperSpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechToText
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
     * Set it to use a live or mock server, e.g.:\
     *   http://127.0.0.1:8787  (device with `adb reverse tcp:8787 tcp:8787`, mock running)
     *   http://10.0.2.2:8787   (emulator)
     * If the server is unreachable, the sample form is used instead.
     */
    const val SERVER_BASE_URL = ""

    const val DEFAULT_FORM_ID = "incident_report"

    /**
     * A published form page (any ordinary HTML `<form>`) to build the conversation from.
     * Blank: the bundled sample `assets/forms/incident_report.html` is used.
     */
    const val FORM_URL = ""
}

/**
 * Tiny manual dependency injection. Real models only — the app must show the actual
 * on-device pipeline, and a missing model is surfaced as a load failure (LlmStatus.Failed,
 * an ASR init error, silence), never as a scripted placeholder:
 *
 *  - ASR: Qualcomm Whisper SDK on the Hexagon HTP (`scripts/push-whisper-models.sh`)
 *  - LLM: Gemma 4 E2B through GenieX llama.cpp on the Hexagon NPU (`scripts/push-gemma-model.sh`)
 *  - TTS: a Piper voice through sherpa-onnx on the CPU (`scripts/fetch-piper-tts.sh`), with
 *    Android's built-in TextToSpeech as the fallback when the voice is not on the device
 *
 * See docs/ASR.md, docs/LLM.md and docs/TTS.md.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val speechToText: SpeechToText = WhisperSpeechToText(appContext)

    /** Weights are side-loaded; a missing GGUF fails the GenieX import and shows in [llm] status. */
    val llm: LlmBackend = GemmaGenieXLlm(
        appContext,
        GemmaModels.appDir(appContext) ?: appContext.filesDir.absolutePath,
    )

    /**
     * Piper (sherpa-onnx, CPU) when its voice is on the device, otherwise Android's system TTS.
     * Not MeloTTS on the NPU: the VoiceAI TTS SDK cannot run the 8 Gen 3 MeloTTS assets and
     * a failed synthesis crashes the CDSP session Whisper and Gemma share (docs/TTS.md).
     */
    val synthesizer: SpeechSynthesizer =
        if (PiperModels.resolve(appContext) != null) PiperSpeechSynthesizer(appContext)
        else AndroidSpeechSynthesizer(appContext)
    val repository: ReportRepository = InMemoryReportRepository()

    /** Bundled sample by default; the server (when configured) replaces it. */
    val formSource: FormSource =
        HttpFormSource(AppConfig.SERVER_BASE_URL, BundledFormSource(appContext), AppConfig.FORM_URL)

    val reportSubmitter: ReportSubmitter = HttpReportSubmitter(AppConfig.SERVER_BASE_URL)
}

class IncidentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
