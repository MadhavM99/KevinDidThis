package com.megathon.incidentassistant.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PLACEHOLDER - no real audio is recorded and no model is used.
 * It returns the example sentence from the problem statement so the whole flow can be demoed.
 *
 * TODO(real implementation):
 *  - startListening(): open AudioRecord (16 kHz, mono, PCM16) and feed chunks to the ASR model.
 *  - publish interim text to [partial] for live captions.
 *  - stopListening(): flush the model and return the final text.
 */
class PlaceholderSpeechToText : SpeechToText {
    private val partial = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = partial.asStateFlow()

    /** No VAD in the placeholder: the user stops with the mic button (or widget tap). */
    override val utteranceFinished: Flow<Unit> = MutableSharedFlow()

    override suspend fun prepare() {
        // TODO: load ASR model + choose delegate (CPU / GPU / NPU).
    }

    override suspend fun startListening() {
        partial.value = ""
    }

    override suspend fun stopListening(): String {
        delay(600) // pretend to transcribe
        return DEMO_TRANSCRIPT
    }

    override fun release() {
        // TODO: release model + AudioRecord.
    }

    private companion object {
        const val DEMO_TRANSCRIPT =
            "There was a water leak near Conference Room 2 around 10 AM. " +
                "Maintenance has already isolated the area."
    }
}
