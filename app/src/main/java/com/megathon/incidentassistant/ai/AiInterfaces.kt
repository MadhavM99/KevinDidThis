package com.megathon.incidentassistant.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pipeline stage 1 — Speech-to-Text. Swap the placeholder for a real on-device ASR
 * (e.g. Whisper on the QNN NPU via the QIDK sample) without touching UI or pipeline code.
 */
interface SpeechToText {
    /** Live captions while the user is speaking. */
    val partialTranscript: StateFlow<String>

    /**
     * Emits when the backend itself detected end-of-speech (e.g. Whisper SDK VAD) and has
     * finished flushing. The pipeline uses this for single-shot mode: stop + process with
     * no extra tap. Backends without VAD never emit (tap-to-stop still works).
     */
    val utteranceFinished: Flow<Unit>

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

    /** Load / warm up the model once. Idempotent; backends with nothing to load keep the default. */
    suspend fun prepare() = Unit

    /**
     * Prompt in, raw model text out. Throws [LlmException] when the runtime fails; callers
     * treat that as a recoverable turn error, never a crash.
     */
    suspend fun generate(prompt: String): String

    /** Free native resources. A later [prepare]/[generate] loads the model again. */
    fun release() = Unit

    /** Load state for the UI (banner / backend chip). */
    val status: StateFlow<LlmStatus> get() = LlmStatus.ALWAYS_READY
}

sealed interface LlmStatus {
    data object NotLoaded : LlmStatus
    data object Loading : LlmStatus
    /** [detail] names the model + runtime + compute unit actually in use. */
    data class Ready(val detail: String, val loadMs: Long) : LlmStatus
    data class Failed(val reason: String) : LlmStatus

    companion object {
        /** For backends that have nothing to load (placeholder, fakes). */
        val ALWAYS_READY: StateFlow<LlmStatus> = MutableStateFlow(Ready(detail = "no model", loadMs = 0))
    }
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Pipeline stage 6 — Text-to-Speech output. */
interface SpeechSynthesizer {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}
