package com.megathon.incidentassistant.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Uses Android's built-in TextToSpeech service (no model bundled in this app).
 * Replace with an on-device neural TTS later if required; the interface stays the same.
 * Tip: pick an offline voice in system settings if you want to guarantee no network use.
 */
class AndroidSpeechSynthesizer(context: Context) : SpeechSynthesizer, TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }

    override fun speak(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "incident-assistant")
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
