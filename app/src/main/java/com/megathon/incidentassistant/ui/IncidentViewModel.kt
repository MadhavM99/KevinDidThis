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
import com.megathon.incidentassistant.ai.AsrStatus
import com.megathon.incidentassistant.ai.LlmStatus
import com.megathon.incidentassistant.ai.NoSpeechException
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FieldValue
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.data.ManagedConfigIdentityProvider
import com.megathon.incidentassistant.session.ExtractionEngine
import com.megathon.incidentassistant.session.IdentityProvider
import com.megathon.incidentassistant.session.IncidentSession
import com.megathon.incidentassistant.session.ListenAfterReply
import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.SessionReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** LLM warm-up result for the "ON-DEVICE AI" badge. */
    val llmReady: Boolean = false,
    val llmFailed: Boolean = false,
    /** ASR (Whisper) load state, shown in the model status header. */
    val asrStatus: String = "",
    val asrReady: Boolean = false,
    val asrFailed: Boolean = false,
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
    /** Signed-in reporter from device management; read per draft so a change applies to the next report. */
    private val identity: IdentityProvider = ManagedConfigIdentityProvider(appContext)
    private var session: IncidentSession = newSession(schema)

    /** Watches the ASR backend's end-of-speech signal (VAD closed the utterance). */
    private var autoStopJob: Job? = null

    /** Consecutive turns where nothing was heard; reset by a successful turn. */
    private var noSpeechStreak = 0

    /** Widget tap: start listening as soon as the form is ready (Siri-style entry). */
    @Volatile private var pendingAutoListen = false

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** Correlates one listen → transcribe → extract → reply → speak cycle in the logs. */
    private var turnId = 0

    /**
     * Hands-free: reopen the mic after a spoken reply — on the TTS "finished" signal, or a
     * bounded watchdog if that signal never comes (see [ListenAfterReply]).
     */
    private val handsFree = ListenAfterReply(
        scope = viewModelScope,
        ttsFinished = container.synthesizer.utteranceFinished,
        log = { turnLog(it) },
    )

    init {
        viewModelScope.launch {
            // ASR first: Whisper.init() registers its model dir on the DSP library search
            // path, where Gemma's Hexagon backend finds libggml-htp-vXX.so. Then the LLM.
            // Each stage is isolated: a failure must never block the rest of the pipeline.
            safePrepare("asr") { container.speechToText.prepare() }
            safePrepare("llm") { container.llm.prepare() }
            maybeStartPendingAutoListen()
        }
        viewModelScope.launch {
            container.llm.status.collect { status ->
                _ui.update {
                    it.copy(
                        llmStatus = describe(status),
                        llmReady = status is LlmStatus.Ready,
                        llmFailed = status is LlmStatus.Failed,
                    )
                }
            }
        }
        viewModelScope.launch {
            (container.speechToText as? com.megathon.incidentassistant.ai.WhisperSpeechToText)
                ?.status?.collect { status ->
                    _ui.update {
                        it.copy(
                            asrStatus = describeAsr(status),
                            asrReady = status is AsrStatus.Ready,
                            asrFailed = status is AsrStatus.Failed,
                        )
                    }
                }
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
        maybeStartPendingAutoListen()
    }

    /**
     * Home-screen widget entry: the user tapped "speak". Listen immediately once the form
     * is in — no extra tap, like Siri/Gemini.
     */
    fun onWidgetLaunch() {
        pendingAutoListen = true
        maybeStartPendingAutoListen()
    }

    private fun maybeStartPendingAutoListen() {
        if (!pendingAutoListen) return
        if (_ui.value.loadingForm) return // retried when the form finishes loading
        pendingAutoListen = false
        val state = _ui.value
        if (state.step == Step.CAPTURE && !state.processing && !state.listening) {
            onStartRecording()
        }
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
        handsFree.cancel() // a tap while a reopen is pending must not start a second recording
        turnId += 1
        turnLog("MICROPHONE_START")
        container.synthesizer.stop() // never talk over the user
        _ui.update { it.copy(listening = true, transcriptReady = false, partial = "", status = str(R.string.status_listening)) }
        pushWidget(str(R.string.widget_status_listening))
        viewModelScope.launch {
            try {
                container.speechToText.startListening()
                turnLog("MICROPHONE_READY")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                turnLog("MICROPHONE_FAILURE ${e.javaClass.simpleName}")
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
                turnLog(if (trimmed.isEmpty()) "ASR_EMPTY" else "ASR_SUCCESS chars=${trimmed.length}")
                if (trimmed.isEmpty()) {
                    reprompt()
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
            } catch (e: NoSpeechException) {
                reprompt()
            } catch (e: Exception) {
                turnLog("ASR_FAILURE ${e.javaClass.simpleName}")
                fail()
            }
        }
    }

    /**
     * Nothing was heard. If a question is pending, speak it again and keep the hands-free
     * loop alive (its TTS completion re-opens the mic); after a couple of misses, stop and
     * let the user tap Start. A successful turn resets the counter.
     */
    private fun reprompt() {
        val question = _ui.value.followUpQuestion
        if (question != null && noSpeechStreak < MAX_NO_SPEECH_REPROMPTS) {
            noSpeechStreak++
            _ui.update { it.copy(transcript = "", transcriptReady = false, status = question) }
            turnLog("REPROMPT no_speech streak=$noSpeechStreak")
            // Same hand-back as a normal reply: re-ask, then reopen the mic once it is said.
            handsFree.afterReply(
                text = question,
                listenAfter = true,
                speak = { speakPrompt(question) },
                onReady = ::maybeAutoListen,
            )
        } else {
            noSpeechStreak = 0
            _ui.update {
                it.copy(transcript = "", transcriptReady = true, status = str(R.string.rec_empty))
            }
            pushWidget(str(R.string.widget_status_ready))
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
        noSpeechStreak = 0
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
                // INFO = nothing to answer (e.g. repeated off-topic): no follow-up prompt, so
                // the hands-free loop waits for a tap instead of re-opening the mic.
                followUpQuestion = reply.speech.takeIf { open && reply.kind != ReplyKind.INFO },
                followUpIsConfirmation = reply.kind == ReplyKind.CONFIRM,
                step = if (reply.phase == SessionPhase.SUBMITTED) Step.SUBMITTED else it.step,
                submittedId = reply.reportId ?: it.submittedId,
            )
        }
        turnLog("FOLLOWUP_READY phase=${reply.phase} kind=${reply.kind} pending=${reply.pendingFieldIds.size}")
        // Speak, then reopen the mic only if the reply expects an answer.
        handsFree.afterReply(
            text = reply.speech,
            listenAfter = open && reply.kind != ReplyKind.INFO,
            speak = { speakPrompt(reply.speech) },
            onReady = ::maybeAutoListen,
        )
        when (reply.phase) {
            SessionPhase.SUBMITTED -> pushWidget(
                str(R.string.widget_status_done) + " " + ZonedDateTime.now().format(clockFormat),
            )
            SessionPhase.CANCELLED -> resetDraft(stopSpeech = false) // let "discarded" finish
            else -> pushWidget(str(R.string.widget_status_ready))
        }
    }

    fun onRepeatQuestion() {
        _ui.value.followUpQuestion?.let { safeSpeak(it) }
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
        if (parts.isNotEmpty()) safeSpeak(parts.joinToString(" "))
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
        handsFree.cancel()
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
                llmReady = it.llmReady,
                llmFailed = it.llmFailed,
                asrStatus = it.asrStatus,
                asrReady = it.asrReady,
                asrFailed = it.asrFailed,
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
        reporter = identity.currentUser(),
    )

    private fun describe(status: LlmStatus): String = when (status) {
        LlmStatus.NotLoaded -> ""
        LlmStatus.Loading -> str(R.string.llm_loading)
        is LlmStatus.Ready -> str(R.string.llm_ready, status.detail)
        is LlmStatus.Failed -> str(R.string.model_unavailable, status.reason)
    }

    private fun describeAsr(status: AsrStatus): String = when (status) {
        AsrStatus.NotLoaded -> ""
        AsrStatus.Loading -> str(R.string.llm_loading) // reuses "Loading…" string
        is AsrStatus.Ready -> status.detail
        is AsrStatus.Failed -> str(R.string.model_unavailable, status.reason)
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
    private fun speakPrompt(text: String): Boolean =
        _ui.value.speakPrompts && !screenReaderActive() && safeSpeak(text)

    /** TTS is complementary output: a speaking failure must never break the flow. */
    private fun safeSpeak(text: String): Boolean =
        runCatching { container.synthesizer.speak(text) }
            .onFailure { turnLog("TTS_FAILURE ${it.javaClass.simpleName}") }
            .isSuccess

    /** Isolates one warm-up stage so a failure never blocks the rest of the pipeline. */
    private suspend fun safePrepare(stage: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$stage warm-up failed: ${e.message}")
        }
    }

    /** Mirrors the assistant state on the home-screen widget. */
    private fun pushWidget(status: String) = IncidentWidget.pushStatus(appContext, status)

    /**
     * Hands-free loop: called by [handsFree] once the reply has been spoken (and the speaker
     * has settled) — listen again for the answer, but only while a question is still
     * pending and nothing else has taken over the screen meanwhile.
     */
    private fun maybeAutoListen() {
        val state = _ui.value
        if (state.step != Step.CAPTURE || state.loadingForm || state.processing ||
            state.listening || state.followUpQuestion == null
        ) {
            turnLog("UNEXPECTED_STATE no_reopen step=${state.step} processing=${state.processing} listening=${state.listening}")
            return
        }
        onStartRecording()
    }

    /** Turn-correlated lifecycle events; never transcripts or field values. */
    private fun turnLog(event: String) = Log.i(TURN_TAG, "turn=$turnId $event")

    private fun screenReaderActive(): Boolean {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    override fun onCleared() {
        handsFree.cancel()
        autoStopJob?.cancel()
        container.synthesizer.stop()
        container.speechToText.release()
        container.llm.release()
    }

    private companion object {
        const val TAG = "IncidentVM"
        const val TURN_TAG = "IncidentTurn"

        /** Re-asks for the pending question at most this many times before going quiet. */
        const val MAX_NO_SPEECH_REPROMPTS = 2
    }
}

class IncidentViewModelFactory(private val app: IncidentApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        IncidentViewModel(app.applicationContext, app.container) as T
}