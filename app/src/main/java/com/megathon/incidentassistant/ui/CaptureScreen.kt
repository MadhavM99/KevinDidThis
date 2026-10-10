package com.megathon.incidentassistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.megathon.incidentassistant.AppConfig
import com.megathon.incidentassistant.R

private val MinTouch = 56.dp // above the 48dp Android minimum

/**
 * Recorder-style capture: Start → speak (live ASR caption) → Stop (or VAD close) →
 * review + fix the transcript → Continue. The ASR text is always visible and editable
 * before it reaches the LLM, so speech-recognition mistakes get caught here, not in the
 * report. Typing remains as a no-mic fallback.
 */
@Composable
fun CaptureScreen(state: UiState, vm: IncidentViewModel, onStartRecording: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusText(state.status, active = state.listening || state.processing || state.loadingForm)

        state.followUpQuestion?.let { FollowUpCard(it, vm) }

        RecorderCard(state, vm, onStartRecording)

        if (!state.listening && !state.transcriptReady) {
            TypedInput(state, vm)
        }

        SpeakPromptsRow(state.speakPrompts, vm::onSpeakPromptsChanged)

        TextButton(
            onClick = vm::onRefreshForm,
            enabled = !state.processing && !state.listening && !state.loadingForm,
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.refresh_form)) }

        if (AppConfig.USING_PLACEHOLDER_MODELS) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.placeholder_banner),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** One of: recording (live caption + Stop) · reviewing transcript · idle (Start). */
@Composable
private fun RecorderCard(state: UiState, vm: IncidentViewModel, onStartRecording: () -> Unit) {
    when {
        state.listening -> ListeningSection(state, vm)
        state.processing -> WorkingSection()
        state.transcriptReady -> TranscriptReviewSection(state, vm)
        else -> StartSection(state, onStartRecording)
    }
}

// ---------- recording ----------

@Composable
private fun ListeningSection(state: UiState, vm: IncidentViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.rec_listening),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            // Live ASR output: the user sees the text being recognised, in real time.
            Text(
                text = state.partial.ifBlank { stringResource(R.string.rec_hear_hint) },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                onClick = vm::onStopRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rec_stop))
            }
        }
    }
}

// ---------- transcript review (fix ASR errors before the LLM sees them) ----------

@Composable
private fun TranscriptReviewSection(state: UiState, vm: IncidentViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.rec_transcript_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = state.transcript,
                onValueChange = vm::onTranscriptChange,
                label = { Text(stringResource(R.string.rec_transcript_label)) },
                supportingText = { Text(stringResource(R.string.rec_transcript_hint)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.transcript.isBlank()) {
                Text(
                    stringResource(R.string.rec_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::onReRecord,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MinTouch),
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rec_rerecord))
                }
                Button(
                    onClick = vm::onUseTranscript,
                    enabled = state.transcript.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MinTouch),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rec_continue))
                }
            }
        }
    }
}

// ---------- idle (Start) / busy (working) ----------

@Composable
private fun StartSection(state: UiState, onStartRecording: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onStartRecording,
            enabled = !state.loadingForm && !state.processing && state.schema.fields.isNotEmpty(),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(136.dp)
                .semantics { stateDescription = stringResource(R.string.mic_state_idle) },
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null, // the label below says it
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            stringResource(R.string.rec_start),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WorkingSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = {},
            enabled = false,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(136.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .clearAndSetSemantics { }, // status text already announces progress
            )
        }
        Text(
            stringResource(R.string.mic_busy_label),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Siri-style status pill with a pulsing dot while the assistant is listening/working.
 * Live region: TalkBack reads this whenever the text changes, without moving focus. */
@Composable
private fun StatusText(status: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pulse = rememberInfiniteTransition(label = "statusPulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "dotAlpha",
            )
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FollowUpCard(question: String, vm: IncidentViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.followup_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(question, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::onRepeatQuestion,
                    modifier = Modifier.heightIn(min = MinTouch),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.followup_repeat))
                }
                TextButton(
                    onClick = vm::onSkipToReview,
                    modifier = Modifier.heightIn(min = MinTouch),
                ) { Text(stringResource(R.string.skip_to_review)) }
            }
        }
    }
}

@Composable
private fun TypedInput(state: UiState, vm: IncidentViewModel) {
    val label = stringResource(
        if (state.followUpQuestion != null) R.string.type_answer_label else R.string.type_label
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.typedText,
            onValueChange = vm::onTypedTextChange,
            label = { Text(label) },
            minLines = 3,
            enabled = !state.processing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { vm.onSendTyped() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = vm::onSendTyped,
            enabled = !state.processing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.send))
        }
    }
}

@Composable
private fun SpeakPromptsRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is one toggle target with Switch semantics (label + state read together).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.speak_prompts), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.speak_prompts_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}