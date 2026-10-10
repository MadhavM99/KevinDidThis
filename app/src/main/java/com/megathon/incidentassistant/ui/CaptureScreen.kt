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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.megathon.incidentassistant.R

private val MinTouch = 56.dp // above the 48dp Android minimum

/**
 * The assistant stage — immersive, Siri-style.
 *
 * Voice-first: a big mic to start, an animated waveform while listening with the live
 * ASR caption large on screen, and pill controls. The transcript is always shown and
 * editable before it reaches the LLM, so speech-recognition mistakes are caught by the
 * human, not extracted into the report. Typing remains as a no-mic fallback.
 */
@Composable
fun CaptureScreen(state: UiState, vm: IncidentViewModel, onStartRecording: () -> Unit) {
    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        )
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(gradient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusText(state.status, active = state.listening || state.processing || state.loadingForm)

            state.followUpQuestion?.let { FollowUpCard(it, state.followUpIsConfirmation, vm) }

            Spacer(Modifier.height(8.dp))

            // The stage: idle mic (tap to start) / animated waveform + live caption.
            WaveformStage(
                state = state,
                onStartRecording = onStartRecording,
            )

            CaptionArea(state, vm)

            Spacer(Modifier.height(8.dp))

            RecorderPills(state, vm)

            LowerControls(state, vm)
        }
    }
}

// ---------- the Siri stage ----------

@Composable
private fun WaveformStage(state: UiState, onStartRecording: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(210.dp)) {
        when {
            state.listening -> {
                val pulse = rememberInfiniteTransition(label = "ring")
                val ringScale by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "ringScale",
                )
                Box(
                    Modifier
                        .size(190.dp)
                        .scale(ringScale)
                        .alpha(0.25f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                WaveformBars()
            }

            state.processing -> CircularProgressIndicator(Modifier.size(72.dp))

            else -> {
                // Resolved here: stringResource is @Composable, the semantics lambda is not.
                val idleDescription = stringResource(R.string.mic_state_idle)
                val pulse = rememberInfiniteTransition(label = "idle")
                val glow by pulse.animateFloat(
                    initialValue = 0.10f,
                    targetValue = 0.28f,
                    animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
                    label = "glow",
                )
                Box(
                    Modifier
                        .size(160.dp)
                        .alpha(glow)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Button(
                    onClick = onStartRecording,
                    enabled = !state.loadingForm && !state.processing && state.schema.fields.isNotEmpty(),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(120.dp)
                        .semantics { stateDescription = idleDescription },
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null, // the caption below says it
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
        }
    }
}

/** Siri-style equalizer: nine bars breathing at slightly different rates. */
@Composable
private fun WaveformBars() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until 9) {
            val transition = rememberInfiniteTransition(label = "bar$i")
            val barHeight by transition.animateFloat(
                initialValue = 10f,
                targetValue = 46f,
                animationSpec = infiniteRepeatable(tween(420 + i * 55), RepeatMode.Reverse),
                label = "height",
            )
            Box(
                Modifier
                    .width(7.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// ---------- caption / transcript ----------

@Composable
private fun CaptionArea(state: UiState, vm: IncidentViewModel) {
    when {
        state.listening -> Text(
            text = state.partial.ifBlank { stringResource(R.string.rec_hear_hint) },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        state.transcriptReady -> TranscriptReview(state, vm)

        state.llmStatus.isNotBlank() -> Text(
            text = state.llmStatus,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        else -> Text(
            text = stringResource(R.string.rec_intro),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Review + fix the ASR text before it reaches the LLM. */
@Composable
private fun TranscriptReview(state: UiState, vm: IncidentViewModel) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
    }
}

// ---------- voice-first pills ----------

@Composable
private fun RecorderPills(state: UiState, vm: IncidentViewModel) {
    when {
        state.listening -> PillButton(
            onClick = vm::onStopRecording,
            icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
            label = stringResource(R.string.rec_stop),
            destructive = true,
        )

        state.transcriptReady -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                onClick = vm::onReRecord,
                icon = { Icon(Icons.Filled.Replay, contentDescription = null) },
                label = stringResource(R.string.rec_rerecord),
                outlined = true,
            )
            PillButton(
                onClick = vm::onUseTranscript,
                icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                label = stringResource(R.string.rec_continue),
                enabled = state.transcript.isNotBlank(),
            )
        }
    }
}

@Composable
private fun PillButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean = true,
    outlined: Boolean = false,
    destructive: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            modifier = Modifier.heightIn(min = MinTouch),
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else ButtonDefaults.buttonColors(),
            modifier = Modifier.heightIn(min = MinTouch),
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

// ---------- secondary controls (typing fallback, toggles, refresh) ----------

@Composable
private fun LowerControls(state: UiState, vm: IncidentViewModel) {
    var typingOpen by remember { mutableStateOf(false) }

    if (!state.listening && !state.transcriptReady) {
        TextButton(
            onClick = { typingOpen = !typingOpen },
            enabled = !state.processing,
            modifier = Modifier.heightIn(min = MinTouch),
        ) {
            Text(
                stringResource(
                    if (typingOpen) R.string.type_toggle_hide else R.string.type_toggle_show
                )
            )
        }
        if (typingOpen) TypedInput(state, vm)
    }

    SpeakPromptsRow(state.speakPrompts, vm::onSpeakPromptsChanged)

    TextButton(
        onClick = vm::onRefreshForm,
        enabled = !state.processing && !state.listening && !state.loadingForm,
        modifier = Modifier.heightIn(min = MinTouch),
    ) { Text(stringResource(R.string.refresh_form)) }

    if (state.usingPlaceholderLlm) {
        Text(
            text = stringResource(R.string.placeholder_banner),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
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
private fun FollowUpCard(question: String, isConfirmation: Boolean, vm: IncidentViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (isConfirmation) R.string.confirm_heading else R.string.followup_heading),
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