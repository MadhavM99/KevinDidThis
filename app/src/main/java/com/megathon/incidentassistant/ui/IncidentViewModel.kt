package com.megathon.incidentassistant.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.megathon.incidentassistant.AppConfig
import com.megathon.incidentassistant.AppContainer
import com.megathon.incidentassistant.IncidentApp
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.data.ReportIds
import com.megathon.incidentassistant.form.ExtractionPromptBuilder
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FieldValue
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.form.ReportJson
import com.megathon.incidentassistant.form.ToolCallParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

enum class Step { CAPTURE, REVIEW, SUBMITTED }

data class UiState(
    val step: Step = Step.CAPTURE,
    val listening: Boolean = false,
    val processing: Boolean = false,
    val loadingForm: Boolean = true,
    val typedText: String = "",
    val partial: String = "",
    val transcript: String = "",
    /** The dynamic form definition currently in use (from proxy or bundled asset). */
    val schema: FormSchema = FormSchema(id = ""),
    val values: Map<String, FieldValue> = emptyMap(),
    val followUpFieldId: String? = null,
    val followUpQuestion: String? = null,
    val status: String = "",
    val speakPrompts: Boolean = true,
    val submittedId: String? = null,
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
 * Runs the pipeline on top of a dynamic form definition:
 * speak -> STT -> LLM extraction (tool calls) -> normalize + merge -> mandatory-field
 * gate -> (missing? ask the field's question) / (complete? review) -> submit + proxy POST.
 *
 * The AI stages are behind the ai/ seams; everything here is deterministic.
 */
class IncidentViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState(status = appContext.getString(R.string.status_loading_form)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var schema: FormSchema = FormSchema(id = "")
    private var formState: FormState = FormState(schema)
    private var followUpField: FormField? = null

    init {
        viewModelScope.launch { container.speechToText.prepare() }
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
        formState = FormState(loaded)
        followUpField = null
        _ui.update {
            it.copy(
                loadingForm = false,
                schema = loaded,
                values = formState.entries(),
                status = str(R.string.status_ready),
            )
        }
    }

    /** Re-fetch the form definition from the server (starts a fresh draft). */
    fun onRefreshForm() {
        val state = _ui.value
        if (state.processing || state.listening) return
        viewModelScope.launch { loadForm() }
    }

    // ---------- Capture ----------

    fun onMicTapped() {
        val state = _ui.value
        if (state.processing || state.loadingForm || schema.fields.isEmpty()) return
        if (state.listening) stopAndProcess() else startListening()
    }

    fun onMicPermissionDenied() = setStatus(R.string.status_mic_denied, speak = true)

    private fun startListening() {
        container.synthesizer.stop() // never talk over the user
        _ui.update { it.copy(listening = true, partial = "", status = str(R.string.status_listening)) }
        viewModelScope.launch { container.speechToText.startListening() }
    }

    private fun stopAndProcess() {
        _ui.update { it.copy(listening = false, processing = true, status = str(R.string.status_processing)) }
        viewModelScope.launch {
            try {
                val text = container.speechToText.stopListening()
                process(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail()
            }
        }
    }

    fun onTypedTextChange(text: String) = _ui.update { it.copy(typedText = text) }

    fun onSendTyped() {
        val state = _ui.value
        if (state.processing || state.loadingForm) return
        if (state.typedText.isBlank()) {
            setStatus(R.string.status_empty_input, speak = false)
            return
        }
        val text = state.typedText
        _ui.update { it.copy(typedText = "", processing = true, status = str(R.string.status_processing)) }
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

    /** One full turn: prompt -> LLM -> tool calls -> apply -> gate/policy. */
    private suspend fun process(text: String) {
        _ui.update { it.copy(transcript = text, processing = true) }
        formState.beginUtterance()

        val prompt = buildString {
            append(ExtractionPromptBuilder.systemPrompt(schema, formState, ZonedDateTime.now().toString()))
            appendLine()
            appendLine()
            append(ExtractionPromptBuilder.userPrompt(followUpField, text))
        }
        val raw = container.llm.generate(prompt)
        val parsed = ToolCallParser.parse(raw)

        for (call in parsed.calls) {
            when (call) {
                is FormToolCall.RecordFields -> call.updates.forEach { update ->
                    formState.apply(
                        fieldId = update.fieldId,
                        rawValue = update.value,
                        confidence = update.confidence,
                        evidence = update.evidence,
                        status = update.status,
                    )
                }
                // The skeleton asks its own questions (deterministic policy below);
                // submission is app-owned, never model-owned.
                is FormToolCall.AskUser -> Unit
                is FormToolCall.SubmitReport -> Unit
            }
        }
        advance()
    }

    /** The "mandatory fields present?" gate + next question. */
    private fun advance() {
        val missing = formState.missingRequired()
        if (missing.isEmpty()) {
            followUpField = null
            val message = str(R.string.status_review_ready)
            _ui.update {
                it.copy(
                    values = formState.entries(),
                    step = Step.REVIEW,
                    followUpFieldId = null,
                    followUpQuestion = null,
                    processing = false,
                    status = message,
                )
            }
            speakPrompt(message)
        } else {
            val next = missing.first()
            followUpField = next
            val question = next.question ?: str(R.string.ask_default, next.label.ifBlank { next.id })
            _ui.update {
                it.copy(
                    values = formState.entries(),
                    followUpFieldId = next.id,
                    followUpQuestion = question,
                    processing = false,
                    status = question,
                )
            }
            speakPrompt(question)
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
        formState.setDirect(fieldId, value)
        _ui.update { it.copy(values = formState.entries()) }
    }

    fun onAddMore() {
        _ui.update {
            it.copy(
                step = Step.CAPTURE,
                followUpFieldId = null,
                followUpQuestion = null,
                status = str(R.string.status_add_more),
            )
        }
    }

    fun onReadReportAloud() {
        val parts = schema.fields
            .filter { formState.text(it.id).isNotBlank() }
            .map { field ->
                str(
                    R.string.field_spoken,
                    field.label.ifBlank { field.id },
                    displayValue(field, formState.text(field.id)),
                )
            }
        if (parts.isNotEmpty()) container.synthesizer.speak(parts.joinToString(" "))
    }

    fun onSubmit() {
        val missing = formState.missingRequired()
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { it.label.ifBlank { it.id } }
            val message = str(R.string.status_missing_submit, names)
            _ui.update { it.copy(status = message) }
            speakPrompt(message)
            return
        }
        viewModelScope.launch {
            val reportId = ReportIds.newReportId()
            val json = ReportJson.build(schema, formState, reportId, ZonedDateTime.now().toString())
            val saved = container.repository.submit(reportId, json)   // local first: never lose it
            val delivery = container.reportSubmitter.submit(json, reportId)
            val message = if (delivery.ok) {
                str(R.string.status_submitted, saved.id)
            } else {
                str(R.string.status_submitted_local, saved.id)
            }
            _ui.update { it.copy(step = Step.SUBMITTED, submittedId = saved.id, status = message) }
            speakPrompt(message)
        }
    }

    fun onNewReport() {
        container.synthesizer.stop()
        formState.reset()
        followUpField = null
        _ui.update {
            UiState(
                schema = schema,
                values = formState.entries(),
                status = str(R.string.status_ready),
                speakPrompts = it.speakPrompts,
                loadingForm = false,
            )
        }
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

    private fun fail() {
        _ui.update { it.copy(listening = false, processing = false, status = str(R.string.status_error)) }
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

    private fun screenReaderActive(): Boolean {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    override fun onCleared() {
        container.synthesizer.stop()
        container.speechToText.release()
    }
}

class IncidentViewModelFactory(private val app: IncidentApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        IncidentViewModel(app.applicationContext, app.container) as T
}
