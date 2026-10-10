package com.megathon.incidentassistant.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Pipeline stage 1 — Speech-to-Text. Swap the placeholder for a real on-device ASR
 * (e.g. Whisper on the QNN NPU via the QIDK sample) without touching UI or pipeline code.
 */
interface SpeechToText {
    /** Live captions while the user is speaking. */
    val partialTranscript: StateFlow<String>

    /** Load model files, warm up delegates. Called once at start-up. */
    suspend fun prepare()

    suspend fun startListening()

    /** Stop recording and return the final transcript. */
    suspend fun stopListening(): String

    fun release()
}

/**
 * Pipeline stage 2 — LLM extraction. Deliberately the tiniest possible seam:
 * prompt text in, raw model text out.
 *
 * The prompt is generated from the dynamic form definition
 * ([com.megathon.incidentassistant.form.ExtractionPromptBuilder]) and the raw output is
 * parsed into tool calls ([com.megathon.incidentassistant.form.ToolCallParser]) — so ANY
 * text model (Genie/Qwen/Llama, or a fake) plugs in here with zero pipeline changes.
 */
interface LlmBackend {
    val id: String
    suspend fun generate(prompt: String): String
}

/** Pipeline stage 6 — Text-to-Speech output. */
interface SpeechSynthesizer {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}
