package com.megathon.incidentassistant.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Locale

/**
 * Uses Android's built-in TextToSpeech service (no model bundled in this app).
 * Replace with an on-device neural TTS later if required; the interface stays the same.
 * Tip: pick an offline voice in system settings if you want to guarantee no network use.
 *
 * Emits [utteranceFinished] after each spoken reply so the hands-free loop can
 * automatically re-open the mic without any extra tap.
 */
class AndroidSpeechSynthesizer(context: Context) : SpeechSynthesizer, TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    private val _utteranceFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val utteranceFinished: Flow<Unit> = _utteranceFinished

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    _utteranceFinished.tryEmit(Unit)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    _utteranceFinished.tryEmit(Unit) // don't strand the hands-free loop
                }
            })
        }
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
