package com.megathon.incidentassistant.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.megathon.incidentassistant.AppContainer
import com.megathon.incidentassistant.IncidentApp
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.domain.FieldKey
import com.megathon.incidentassistant.domain.IncidentReport
import com.megathon.incidentassistant.domain.IncidentTools
import com.megathon.incidentassistant.domain.Severity
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
    val typedText: String = "",
    val partial: String = "",
    val transcript: String = "",
    val report: IncidentReport = IncidentReport(),
    /** Set while the assistant is waiting for the answer to a follow-up question. */
    val followUpField: FieldKey? = null,
    /** Always-current status sentence; shown in a live region so screen readers announce it. */
    val status: String = "",
    val speakPrompts: Boolean = true,
    val submittedId: String? = null,
)

/**
 * Runs the pipeline:
 * speak -> STT -> LLM extraction (tool calls) -> mandatory-field check
 *   -> (missing? ask follow-up) / (complete? review) -> submit.
 */
class IncidentViewModel(
    private val appContext: Context,
    private val c: AppContainer,
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState(status = appContext.getString(R.string.status_ready)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            c.speechToText.prepare()
            c.extractor.prepare()
        }
        viewModelScope.launch {
            c.speechToText.partialTranscript.collect { p -> _ui.update { it.copy(partial = p) } }
        }
    }

    // ---------- Capture ----------

    fun onMicTapped() {
        if (_ui.value.processing) return
        if (_ui.value.listening) stopAndProcess() else startListening()
    }

    fun onMicPermissionDenied() = setStatus(R.string.status_mic_denied, speak = true)

    private fun startListening() {
        c.synthesizer.stop() // never talk over the user
        _ui.update { it.copy(listening = true, partial = "", status = str(R.string.status_listening)) }
        viewModelScope.launch { c.speechToText.startListening() }
    }

    private fun stopAndProcess() {
        _ui.update { it.copy(listening = false, processing = true, status = str(R.string.status_processing)) }
        viewModelScope.launch {
            try {
                val text = c.speechToText.stopListening()
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
        val s = _ui.value
        if (s.processing) return
        if (s.typedText.isBlank()) {
            setStatus(R.string.status_empty_input, speak = false)
            return
        }
        val text = s.typedText
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

    private suspend fun process(text: String) {
        _ui.update { it.copy(transcript = text, processing = true) }
        val before = _ui.value
        val calls = c.extractor.extract(text, before.report, before.followUpField, ZonedDateTime.now())
        advance(IncidentTools.apply(before.report, calls))
    }

    /** The "Mandatory fields present?" diamond from the diagram. */
    private fun advance(report: IncidentReport) {
        val missing = report.missingMandatory()
        if (missing.isEmpty()) {
            val msg = str(R.string.status_review_ready)
            _ui.update {
                it.copy(report = report, step = Step.REVIEW, followUpField = null, processing = false, status = msg)
            }
            speakPrompt(msg)
        } else {
            val next = missing.first()
            val question = str(next.askRes)
            _ui.update {
                it.copy(report = report, followUpField = next, processing = false, status = question)
            }
            speakPrompt(question)
        }
    }

    fun onRepeatQuestion() {
        _ui.value.followUpField?.let { c.synthesizer.speak(str(it.askRes)) }
    }

    fun onSkipToReview() {
        val msg = str(R.string.status_review_ready)
        _ui.update { it.copy(step = Step.REVIEW, followUpField = null, status = msg) }
    }

    fun onSpeakPromptsChanged(enabled: Boolean) {
        if (!enabled) c.synthesizer.stop()
        _ui.update { it.copy(speakPrompts = enabled) }
    }

    // ---------- Review ----------

    fun onFieldChanged(key: FieldKey, value: String) =
        _ui.update { it.copy(report = it.report.with(key, value)) }

    fun onAddMore() {
        _ui.update { it.copy(step = Step.CAPTURE, followUpField = null, status = str(R.string.status_add_more)) }
    }

    fun onReadReportAloud() {
        val report = _ui.value.report
        val text = FieldKey.entries
            .filter { report.get(it).isNotBlank() }
            .joinToString(" ") { key ->
                str(R.string.field_spoken, str(key.labelRes), displayValue(key, report.get(key)))
            }
        c.synthesizer.speak(text) // explicit user request: always speak
    }

    fun onSubmit() {
        val report = _ui.value.report
        val missing = report.missingMandatory()
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { str(it.labelRes) }
            val msg = str(R.string.status_missing_submit, names)
            _ui.update { it.copy(status = msg) }
            speakPrompt(msg)
            return
        }
        viewModelScope.launch {
            val saved = c.repository.submit(report)
            val msg = str(R.string.status_submitted, saved.id)
            _ui.update { it.copy(step = Step.SUBMITTED, submittedId = saved.id, status = msg) }
            speakPrompt(msg)
        }
    }

    fun onNewReport() {
        c.synthesizer.stop()
        _ui.update {
            UiState(status = str(R.string.status_ready), speakPrompts = it.speakPrompts)
        }
    }

    // ---------- helpers ----------

    fun displayValue(key: FieldKey, value: String): String =
        if (key == FieldKey.SEVERITY) Severity.parse(value)?.let { str(it.labelRes) } ?: value else value

    private fun fail() {
        _ui.update { it.copy(listening = false, processing = false, status = str(R.string.status_error)) }
    }

    private fun setStatus(resId: Int, speak: Boolean) {
        val msg = str(resId)
        _ui.update { it.copy(status = msg) }
        if (speak) speakPrompt(msg)
    }

    /**
     * Speak only if the user wants it AND no screen reader is active.
     * TalkBack already reads the live-region status, and two voices at once is unusable.
     */
    private fun speakPrompt(text: String) {
        if (_ui.value.speakPrompts && !screenReaderActive()) c.synthesizer.speak(text)
    }

    private fun screenReaderActive(): Boolean {
        val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    override fun onCleared() {
        c.synthesizer.stop()
        c.speechToText.release()
    }
}

class IncidentViewModelFactory(private val app: IncidentApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        IncidentViewModel(app.applicationContext, app.container) as T
}
