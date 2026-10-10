package com.megathon.incidentassistant

import android.app.Application
import android.content.Context
import com.megathon.incidentassistant.ai.AndroidSpeechSynthesizer
import com.megathon.incidentassistant.ai.IncidentExtractor
import com.megathon.incidentassistant.ai.PlaceholderIncidentExtractor
import com.megathon.incidentassistant.ai.PlaceholderSpeechToText
import com.megathon.incidentassistant.ai.SpeechSynthesizer
import com.megathon.incidentassistant.ai.SpeechToText
import com.megathon.incidentassistant.data.InMemoryReportRepository
import com.megathon.incidentassistant.data.ReportRepository

object AppConfig {
    /** Flip to false once real models are plugged in; hides the demo banner. */
    const val USING_PLACEHOLDER_MODELS = true
}

/** Tiny manual dependency injection. THIS is the one place you swap placeholders for real models. */
class AppContainer(context: Context) {
    val speechToText: SpeechToText = PlaceholderSpeechToText()        // TODO: real ASR
    val extractor: IncidentExtractor = PlaceholderIncidentExtractor() // TODO: real LLM
    val synthesizer: SpeechSynthesizer = AndroidSpeechSynthesizer(context)
    val repository: ReportRepository = InMemoryReportRepository()
}

class IncidentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
