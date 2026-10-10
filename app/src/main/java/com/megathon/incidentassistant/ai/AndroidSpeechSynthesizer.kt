package com.megathon.incidentassistant.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Locale

/**
 * Availability fallback for speech output: Android's built-in TextToSpeech service.
 *
 * Used when the Piper model is not on the device (see [AppContainer]). It also keeps the
 * hands-free conversation loop alive: [utteranceFinished] fires on a done/failed reply AND
 * immediately when the engine is unavailable, so a missing TTS never strands the
 * follow-up questions (they continue on text).
 */
class AndroidSpeechSynthesizer(context: Context) : SpeechSynthesizer, TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var ready = false
    private val tts = TextToSpeech(appContext, this)

    private val _utteranceFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val utteranceFinished: Flow<Unit> = _utteranceFinished

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = _utteranceFinished.tryEmit(Unit)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "tts error $errorCode")
                _utteranceFinished.tryEmit(Unit) // don't strand the loop on a failed reply
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = _utteranceFinished.tryEmit(Unit)
        })
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            // No engine available: the reply stays text-only, but the conversation must
            // keep its cadence — the hands-free loop can re-open the mic right away.
            Log.w(TAG, "android tts not ready - reply stays text-only")
            _utteranceFinished.tryEmit(Unit)
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    override fun shutdown() {
        if (ready) tts.shutdown()
    }

    private companion object {
        const val TAG = "AndroidTTS"
        const val UTTERANCE_ID = "incident-assistant"
    }
}