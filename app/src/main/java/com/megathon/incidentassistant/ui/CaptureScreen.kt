package com.megathon.incidentassistant.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.megathon.incidentassistant.AppConfig
import com.megathon.incidentassistant.R

private val MinTouch = 56.dp // above the 48dp Android minimum

@Composable
fun CaptureScreen(state: UiState, vm: IncidentViewModel, onMic: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusText(state.status)

        state.followUpField?.let { FollowUpCard(it, vm) }

        MicButton(listening = state.listening, busy = state.processing, onClick = onMic)

        TranscriptCard(state)

        TypedInput(state, vm)

        SpeakPromptsRow(state.speakPrompts, vm::onSpeakPromptsChanged)

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

/** Live region: TalkBack reads this whenever the text changes, without moving focus. */
@Composable
private fun StatusText(status: String) {
    Text(
        text = status,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun MicButton(listening: Boolean, busy: Boolean, onClick: () -> Unit) {
    val desc = stringResource(if (listening) R.string.mic_stop_desc else R.string.mic_start_desc)
    val stateDesc = stringResource(if (listening) R.string.mic_state_listening else R.string.mic_state_idle)
    val label = stringResource(
        when {
            busy -> R.string.mic_busy_label
            listening -> R.string.mic_stop_label
            else -> R.string.mic_start_label
        }
    )
    // Tap-to-start / tap-to-stop (not press-and-hold) so it works with TalkBack,
    // Switch Access, voice access and limited motor control.
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = if (listening) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else ButtonDefaults.buttonColors(),
        modifier = Modifier
            .size(136.dp)
            .semantics {
                contentDescription = desc
                stateDescription = stateDesc
            },
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .clearAndSetSemantics { }, // status text already announces progress
            )
        } else {
            Icon(
                imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null, // button carries the description
                modifier = Modifier.size(64.dp),
            )
        }
    }
    // Visible text label: state is never conveyed by colour or icon alone.
    Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

@Composable
private fun TranscriptCard(state: UiState) {
    val shown = if (state.listening) state.partial else state.transcript
    val empty = stringResource(R.string.heard_empty)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.heard_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(shown.ifBlank { empty }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FollowUpCard(field: com.megathon.incidentassistant.domain.FieldKey, vm: IncidentViewModel) {
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
            Text(stringResource(field.askRes), style = MaterialTheme.typography.bodyLarge)
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
        if (state.followUpField != null) R.string.type_answer_label else R.string.type_label
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
