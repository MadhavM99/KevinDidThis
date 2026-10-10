package com.megathon.incidentassistant.ai

import com.megathon.incidentassistant.domain.FieldKey
import com.megathon.incidentassistant.domain.IncidentReport
import com.megathon.incidentassistant.domain.ToolCall
import kotlinx.coroutines.flow.StateFlow
import java.time.ZonedDateTime

/**
 * Pipeline stage 1: Speech-to-Text.
 * Swap [PlaceholderSpeechToText] for a real on-device ASR (e.g. Whisper / Zipformer running on
 * the CPU, GPU or NPU) without touching any UI or ViewModel code.
 */
interface SpeechToText {
    /** Live captions while the user is speaking. Great for deaf / hard-of-hearing users. */
    val partialTranscript: StateFlow<String>

    /** Load model files, warm up delegates. Called once at start-up. */
    suspend fun prepare()

    suspend fun startListening()

    /** Stop recording and return the final transcript. */
    suspend fun stopListening(): String

    fun release()
}

/**
 * Pipeline stage 2 + 4: LLM information extraction that answers with tool calls.
 *
 * @param expectedField set when the assistant just asked a follow-up question, so a short
 *   answer such as "high" can be mapped to the right field.
 * @param now lets the model resolve phrases like "around 10 AM" or "yesterday".
 */
interface IncidentExtractor {
    suspend fun prepare()

    suspend fun extract(
        transcript: String,
        current: IncidentReport,
        expectedField: FieldKey?,
        now: ZonedDateTime,
    ): List<ToolCall>
}

/** Pipeline stage 6: Text-to-Speech output. */
interface SpeechSynthesizer {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}
