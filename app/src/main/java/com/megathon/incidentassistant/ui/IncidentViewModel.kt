package com.megathon.incidentassistant.ui

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.megathon.incidentassistant.AppConfig
import com.megathon.incidentassistant.AppContainer
import com.megathon.incidentassistant.IncidentApp
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.ai.LlmStatus
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FieldValue
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.SessionReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class Step { CAPTURE, REVIEW, SUBMITTED }

data class UiState(
    val step: Step = Step.CAPTURE,
    /** Microphone is live; live captions stream into [partial]. */
    val listening: Boolean = false,
    /** A recording was stopped (or the VAD closed it) and the transcript is ready for review. */
    val transcriptReady: Boolean = false,
    val processing: Boolean = false,
    val loadingForm: Boolean = true,
    val typedText: String = "",
    /** Live ASR caption while [listening]. */
    val partial: String = "",
    /** The final (editable) transcript the user reviews before continuing. */
    val transcript: String = "",
    /** The dynamic form definition currently in use (fetched from the server). */
    val schema: FormSchema = FormSchema(id = ""),
    val values: Map<String, FieldValue> = emptyMap(),
    val followUpFieldId: String? = null,
    val followUpQuestion: String? = null,
    /** The pending prompt is a yes/no confirmation (file the report, "not specified", ...). */
    val followUpIsConfirmation: Boolean = false,
    val status: String = "",
    val speakPrompts: Boolean = true,
    val submittedId: String? = null,
    /** On-device LLM load state, shown under the controls. */
    val llmStatus: String = "",
) {
    fun field(fieldId: String): FormField? = schema.field(fieldId)

    fun value(fieldId: String): String = values[fieldId]?.value.orEmpty()

    fun statusOf(fieldId: String): FieldStatus = values[fieldId]?.status ?: FieldStatus.EMPTY

    fun isSatisfied(fieldId: String): Boolean =
        statusOf(fieldId) == FieldStatus.FILLED || statusOf(fieldId) == FieldStatus.UNKNOWN

    fun missingRequiredLabels(): List<String> =
        schema.requiredFields.filter { !isSatisfied(it.id) }.map { it.label.ifBlank { it.id } }
}

/**
 * Runs the pipeline on top of a dynamic form definition, recorder-style:
 *
 *   Start → speak (live ASR caption on screen) → Stop (or VAD closes) → review + edit the
 *   transcript → Continue → [IncidentSession] (fast intents → LLM extraction → validated
 *   tool calls → mandatory-field gate → next question) → TTS speaks the reply → the user
 *   records the answer → … → spoken summary → "yes" → local save + server POST.
 *
 * The transcript is ALWAYS shown and editable before it reaches the LLM, so transcription
 * mistakes are caught by the human instead of being extracted into the report.
 *
 * This class owns the Android side (ASR/TTS lifecycle, UI state, widget); the
 * conversation logic is the pure-Kotlin [IncidentSession], one per report draft.
 */
class IncidentViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState(status = appContext.getString(R.string.status_loading_form)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var schema: FormSchema = FormSchema(id = "")
    private val phrases = ResourcePhrases(appContext)
    private val extraction = ExtractionEngine(container.llm, now = { ZonedDateTime.now().toString() })
    private var session: IncidentSession = newSession(schema)

    /** Watches the ASR backend's end-of-speech signal (VAD closed the utterance). */
    private var autoStopJob: Job? = null

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    init {
        viewModelScope.launch {
            // TTS first: its SDK sets the DSP thread parameters (128 KB stack) before any
            // other FastRPC user creates the process threads — see MeloTtsSpeechSynthesizer.
            // Then ASR (Whisper.init() registers its model dir on the DSP library search
            // path, where Gemma's Hexagon backend finds libggml-htp-vXX.so), then the LLM,
            // once each, off the main thread inside the backends.
            container.synthesizer.prepare()
            container.speechToText.prepare()
            container.llm.prepare()
        }
        viewModelScope.launch {
            container.llm.status.collect { status -> _ui.update { it.copy(llmStatus = describe(status)) } }
        }
        viewModelScope.launch {
            // Hands-free loop: the assistant finished speaking its reply → re-open the mic.
            container.synthesizer.utteranceFinished.collect { maybeAutoListen() }
        }
        viewModelScope.launch {
            container.speechToText.partialTranscript.collect { partial ->
                _ui.update { it.copy(partial = partial) }
            }
        }
        viewModelScope.launch { loadForm() }
    }

    private suspend fun loadForm() {
        _ui.update { it.copy(loadingForm = true, status = str(R.string.status_loading_form)) }
        val loaded = runCatching { container.formSource.fetchForm(AppConfig.DEFAULT_FORM_ID) }.getOrNull()
        if (loaded == null) {
            _ui.update { it.copy(loadingForm = false, status = str(R.string.status_form_error)) }
            return
        }
        schema = loaded
        session = newSession(loaded)
        _ui.update {
            it.copy(
                loadingForm = false,
                schema = loaded,
                values = session.form.entries(),
                followUpFieldId = null,
                followUpQuestion = null,
                followUpIsConfirmation = false,
                transcript = "",
                transcriptReady = false,
                listening = false,
                status = str(R.string.status_ready),
            )
        }
        pushWidget(str(R.string.widget_status_ready))
    }

    /** Re-fetch the form definition from the server (starts a fresh draft). */
    fun onRefreshForm() {
        val state = _ui.value
        if (state.processing || state.listening) return
        viewModelScope.launch { loadForm() }
    }

    // ---------- Recorder flow: Start / Stop / Re-record / Continue ----------

    /** Called when the mic-permission dialog was granted (from Start or the widget). */
    fun onMicPermissionGranted() = onStartRecording()

    fun onMicPermissionDenied() = setStatus(R.string.status_mic_denied, speak = true)

    /** Start capturing: live captions stream into `partial` until Stop or VAD close. */
    fun onStartRecording() {
        val state = _ui.value
        if (state.processing || state.listening || state.loadingForm || schema.fields.isEmpty()) return
        container.synthesizer.stop() // never talk over the user
        _ui.update { it.copy(listening = true, transcriptReady = false, partial = "", status = str(R.string.status_listening)) }
        pushWidget(str(R.string.widget_status_listening))
        viewModelScope.launch {
            try {
                container.speechToText.startListening()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail() // e.g. ASR model files missing: surface it instead of crashing
            }
        }
        // VAD backends (Whisper SDK) close the utterance themselves → move to review.
        autoStopJob?.cancel()
        autoStopJob = viewModelScope.launch {
            container.speechToText.utteranceFinished.collect { finalizeTranscript() }
        }
    }

    /** Manual stop (the Stop button). */
    fun onStopRecording() {
        val state = _ui.value
        if (!state.listening) return
        finalizeTranscript()
    }

    /** Brought the transcript into the review state, ready to edit or re-record. */
    private fun finalizeTranscript() {
        val state = _ui.value
        if (!state.listening) return
        autoStopJob?.cancel()
        autoStopJob = null
        _ui.update { it.copy(listening = false) }
        viewModelScope.launch {
            try {
                val trimmed = container.speechToText.stopListening().trim()
                if (trimmed.isEmpty()) {
                    _ui.update {
                        it.copy(transcript = "", transcriptReady = true, status = str(R.string.rec_empty))
                    }
                    pushWidget(str(R.string.widget_status_ready))
                } else {
                    // Just-in-time: the transcript goes straight into the extraction pipeline.
                    // It stays on screen while the model works, and the reply comes back as
                    // text + speech.
                    _ui.update {
                        it.copy(
                            transcript = trimmed,
                            transcriptReady = false,
                            status = str(R.string.status_processing),
                        )
                    }
                    pushWidget(str(R.string.widget_status_working))
                    process(trimmed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail()
            }
        }
    }

    /** Throw the transcript away and record the same utterance again. */
    fun onReRecord() {
        val state = _ui.value
        if (state.processing || state.listening) return
        _ui.update { it.copy(transcript = "", transcriptReady = false, partial = "") }
        onStartRecording()
    }

    /** The user is fixing the ASR text before it reaches the LLM. */
    fun onTranscriptChange(text: String) = _ui.update { it.copy(transcript = text) }

    /** Send the reviewed transcript into the extraction pipeline. */
    fun onUseTranscript() {
        val state = _ui.value
        if (state.processing || state.listening) return
        val text = state.transcript.trim()
        if (text.isEmpty()) {
            setStatus(R.string.status_empty_input, speak = false)
            return
        }
        _ui.update { it.copy(transcriptReady = false, processing = true, status = str(R.string.status_processing)) }
        pushWidget(str(R.string.widget_status_working))
        viewModelScope.launch {
            try {
                process(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail()
            }
        }
    }

    fun onTypedTextChange(text: String) = _ui.update { it.copy(typedText = text) }

    /** Fallback entry without the microphone: type the whole utterance. */
    fun onSendTyped() {
        val state = _ui.value
        if (state.processing || state.listening || state.loadingForm) return
        if (state.typedText.isBlank()) {
            setStatus(R.string.status_empty_input, speak = false)
            return
        }
        val text = state.typedText
        _ui.update { it.copy(typedText = "", processing = true, status = str(R.string.status_processing)) }
        pushWidget(str(R.string.widget_status_working))
        viewModelScope.launch {
            try {
                process(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail()
            }
        }
    }

    /** One full turn through the session; stale replies (draft reset meanwhile) are dropped. */
    private suspend fun process(text: String) {
        _ui.update { it.copy(transcript = text, processing = true) }
        val current = session
        val reply = current.onUtterance(text)
        if (current !== session) return
        applyReply(reply)
    }

    /** Shows and speaks what the session decided — the single place a turn reaches UI + TTS. */
    private fun applyReply(reply: SessionReply) {
        reply.metrics?.let { m ->
            // Timings only: never transcripts or field values in logs.
            Log.d(
                TAG,
                "turn total=${m.totalMs}ms llm=${m.llmMs}ms(${m.llmCalls}) prompt=${m.promptMs}ms " +
                    "parse=${m.parseMs}ms apply=${m.applyMs}ms policy=${m.policyMs}ms " +
                    "fast=${m.fastPath} failure=${m.llmFailure} phase=${reply.phase}",
            )
        }
        val open = reply.phase != SessionPhase.SUBMITTED && reply.phase != SessionPhase.CANCELLED
        _ui.update {
            it.copy(
                values = session.form.entries(),
                processing = false,
                status = reply.speech,
                followUpFieldId = reply.pendingFieldIds.firstOrNull(),
                followUpQuestion = reply.speech.takeIf { open },
                followUpIsConfirmation = reply.kind == ReplyKind.CONFIRM,
                step = if (reply.phase == SessionPhase.SUBMITTED) Step.SUBMITTED else it.step,
                submittedId = reply.reportId ?: it.submittedId,
            )
        }
        speakPrompt(reply.speech)
        when (reply.phase) {
            SessionPhase.SUBMITTED -> pushWidget(
                str(R.string.widget_status_done) + " " + ZonedDateTime.now().format(clockFormat),
            )
            SessionPhase.CANCELLED -> resetDraft(stopSpeech = false) // let "discarded" finish
            else -> pushWidget(str(R.string.widget_status_ready))
        }
    }

    fun onRepeatQuestion() {
        _ui.value.followUpQuestion?.let { container.synthesizer.speak(it) }
    }

    fun onSkipToReview() {
        val message = str(R.string.status_review_ready)
        _ui.update { it.copy(step = Step.REVIEW, status = message) }
    }

    fun onSpeakPromptsChanged(enabled: Boolean) {
        if (!enabled) container.synthesizer.stop()
        _ui.update { it.copy(speakPrompts = enabled) }
    }

    // ---------- Review ----------

    fun onFieldChanged(fieldId: String, value: String) {
        session.form.setDirect(fieldId, value)
        _ui.update { it.copy(values = session.form.entries()) }
    }

    fun onAddMore() {
        _ui.update {
            it.copy(
                step = Step.CAPTURE,
                followUpFieldId = null,
                followUpQuestion = null,
                transcript = "",
                transcriptReady = false,
                listening = false,
                partial = "",
                status = str(R.string.status_add_more),
            )
        }
    }

    fun onReadReportAloud() {
        val form = session.form
        val parts = schema.fields
            .filter { form.text(it.id).isNotBlank() }
            .map { field ->
                str(
                    R.string.field_spoken,
                    field.label.ifBlank { field.id },
                    displayValue(field, form.text(field.id)),
                )
            }
        if (parts.isNotEmpty()) container.synthesizer.speak(parts.joinToString(" "))
    }

    /** Review-screen Submit: the tap is the user's explicit confirmation; the gate still applies. */
    fun onSubmit() {
        if (_ui.value.processing) return
        _ui.update { it.copy(processing = true) }
        viewModelScope.launch {
            val current = session
            try {
                val reply = current.submitConfirmedByUser()
                if (current === session) applyReply(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail()
            }
        }
    }

    fun onNewReport() = resetDraft(stopSpeech = true)

    private fun resetDraft(stopSpeech: Boolean) {
        if (stopSpeech) container.synthesizer.stop()
        autoStopJob?.cancel()
        autoStopJob = null
        session = newSession(schema) // an in-flight turn of the old draft is dropped in process()
        _ui.update {
            UiState(
                schema = schema,
                values = session.form.entries(),
                status = str(R.string.status_ready),
                speakPrompts = it.speakPrompts,
                loadingForm = false,
                llmStatus = it.llmStatus,
            )
        }
        pushWidget(str(R.string.widget_status_ready))
    }

    // ---------- helpers ----------

    fun displayValue(field: FormField, value: String): String {
        if (field.type == FieldType.ENUM) {
            val option = field.options.firstOrNull { it.value.equals(value, ignoreCase = true) }
            return option?.label?.ifBlank { value } ?: value
        }
        if (field.type == FieldType.BOOLEAN) {
            return value.replaceFirstChar { it.uppercase() }
        }
        return value
    }

    private fun newSession(form: FormSchema) = IncidentSession(
        schema = form,
        extraction = extraction,
        phrases = phrases,
        repository = container.repository,
        submitter = container.reportSubmitter,
        now = { ZonedDateTime.now().toString() },
    )

    private fun describe(status: LlmStatus): String = when (status) {
        LlmStatus.NotLoaded -> ""
        LlmStatus.Loading -> str(R.string.llm_loading)
        is LlmStatus.Ready -> str(R.string.llm_ready, status.detail)
        is LlmStatus.Failed -> str(R.string.model_unavailable, status.reason)
    }

    private fun fail() {
        _ui.update {
            it.copy(listening = false, processing = false, status = str(R.string.status_error))
        }
    }

    private fun setStatus(resId: Int, speak: Boolean) {
        val message = str(resId)
        _ui.update { it.copy(status = message) }
        if (speak) speakPrompt(message)
    }

    /**
     * Speak only if the user wants it AND no screen reader is active.
     * TalkBack already reads the live-region status, and two voices at once is unusable.
     */
    private fun speakPrompt(text: String) {
        if (_ui.value.speakPrompts && !screenReaderActive()) container.synthesizer.speak(text)
    }

    /** Mirrors the assistant state on the home-screen widget. */
    private fun pushWidget(status: String) = IncidentWidget.pushStatus(appContext, status)

    /**
     * Hands-free loop: after the assistant's reply finished playing, listen again for the
     * answer — but only while a question is pending (the final "filed" message stops the
     * loop). The settle delay keeps the speaker tail out of the microphone.
     */
    private fun maybeAutoListen() {
        val state = _ui.value
        if (state.step != Step.CAPTURE || state.loadingForm || state.processing ||
            state.listening || state.followUpQuestion == null
        ) {
            return
        }
        viewModelScope.launch {
            delay(AUTO_LISTEN_SETTLE_MS)
            val now = _ui.value
            if (now.step == Step.CAPTURE && !now.loadingForm && !now.processing && !now.listening) {
                onStartRecording()
            }
        }
    }

    private fun screenReaderActive(): Boolean {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    override fun onCleared() {
        autoStopJob?.cancel()
        container.synthesizer.stop()
        container.speechToText.release()
        container.llm.release()
    }

    private companion object {
        const val TAG = "IncidentVM"

        /** Speaker drain time before the hands-free loop re-opens the mic. */
        const val AUTO_LISTEN_SETTLE_MS = 400L
    }
}

class IncidentViewModelFactory(private val app: IncidentApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        IncidentViewModel(app.applicationContext, app.container) as T
}