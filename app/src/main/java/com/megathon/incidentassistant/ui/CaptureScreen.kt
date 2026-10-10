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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField
import kotlinx.coroutines.delay

private val MinTouch = 56.dp // above the 48dp Android minimum

/** Dark voice-assistant card, like the reference: a calm green conversation surface. */
private val VoiceCardColor = Color(0xFF0B3B2E)
private val VoiceBubbleColor = Color(0xFF14523F)
private val VoiceOnCard = Color.White
private val VoiceOnCardMuted = Color(0xCCFFFFFF)

/**
 * Step 1 — the conversation. One screen, like Siri/Gemini with a report behind it:
 *
 *  - a header with the step and the on-device badge,
 *  - the voice card: what the assistant is asking, the waveform while listening, the live
 *    transcript, and the single start/stop control,
 *  - the fields extracted so far, and a "Review report" button to step 2.
 *
 * Typing stays as a no-mic fallback under the extracted fields.
 */
@Composable
fun CaptureScreen(state: UiState, vm: IncidentViewModel, onStartRecording: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(state)
        VoiceCard(state, vm, onStartRecording)
        ExtractedDetails(state, vm)
        Button(
            onClick = vm::onSkipToReview,
            enabled = !state.loadingForm,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch),
        ) {
            Text(stringResource(R.string.review_report), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
        SecondaryControls(state, vm)
    }
}

// ---------- header ----------

@Composable
private fun Header(state: UiState) {
    val stepNumber = when (state.step) {
        Step.CAPTURE -> 1
        Step.REVIEW -> 2
        Step.SUBMITTED -> 3
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.report_new),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.report_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    stringResource(R.string.step_chip, stepNumber),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            OnDeviceBadge(state)
        }
    }
}

/** "ON-DEVICE AI" with a status dot: green ready, amber warming up, red failed. */
@Composable
private fun OnDeviceBadge(state: UiState) {
    val dot = when {
        state.llmFailed -> MaterialTheme.colorScheme.error
        state.llmReady -> Color(0xFF1B7F5A)
        else -> Color(0xFFB07A1E)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.on_device_ai),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- the voice card ----------

@Composable
private fun VoiceCard(state: UiState, vm: IncidentViewModel, onStartRecording: () -> Unit) {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(state.listening) {
        if (!state.listening) {
            seconds = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(1_000)
            seconds++
        }
    }

    Surface(shape = RoundedCornerShape(24.dp), color = VoiceCardColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✦ " + stringResource(R.string.voice_assistant),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    color = VoiceOnCardMuted,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (state.listening) {
                    Text(
                        "%02d:%02d".format(seconds / 60, seconds % 60),
                        style = MaterialTheme.typography.labelMedium,
                        color = VoiceOnCardMuted,
                    )
                }
            }

            Text(
                text = when {
                    state.followUpQuestion != null -> state.followUpQuestion
                    state.listening -> stringResource(R.string.voice_card_listening)
                    state.processing -> stringResource(R.string.voice_card_thinking)
                    state.status.isNotBlank() -> state.status
                    else -> stringResource(R.string.voice_card_idle)
                },
                style = MaterialTheme.typography.titleLarge,
                color = VoiceOnCard,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            if (state.listening) WaveformBars(color = VoiceOnCard)

            val heard = when {
                state.listening -> state.partial
                state.processing || state.transcriptReady -> state.transcript
                else -> state.transcript
            }
            if (heard.isNotBlank()) {
                Surface(shape = RoundedCornerShape(16.dp), color = VoiceBubbleColor) {
                    Text(
                        "“$heard”",
                        style = MaterialTheme.typography.bodyLarge,
                        color = VoiceOnCard,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            if (state.transcriptReady && heard.isBlank()) {
                Text(
                    stringResource(R.string.rec_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFB4AB),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoicePill(
                    onClick = when {
                        state.listening -> vm::onStopRecording
                        state.transcriptReady -> vm::onReRecord
                        else -> onStartRecording
                    },
                    icon = when {
                        state.listening -> Icons.Filled.Stop
                        state.transcriptReady -> Icons.Filled.Replay
                        else -> Icons.Filled.Mic
                    },
                    label = when {
                        state.listening -> stringResource(R.string.rec_stop)
                        state.transcriptReady -> stringResource(R.string.rec_rerecord)
                        else -> stringResource(R.string.voice_start)
                    },
                    enabled = state.listening || state.transcriptReady ||
                        (!state.loadingForm && !state.processing && state.schema.fields.isNotEmpty()),
                )
                if (state.followUpQuestion != null && !state.listening) {
                    TextButton(
                        onClick = vm::onRepeatQuestion,
                        colors = ButtonDefaults.textButtonColors(contentColor = VoiceOnCard),
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.followup_repeat))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePill(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VoiceOnCard,
            contentColor = VoiceCardColor,
            disabledContainerColor = VoiceOnCard.copy(alpha = 0.35f),
            disabledContentColor = VoiceCardColor.copy(alpha = 0.6f),
        ),
        modifier = Modifier.heightIn(min = MinTouch),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** Siri-style equalizer: nine bars breathing at slightly different rates. */
@Composable
private fun WaveformBars(color: Color) {
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
                    .background(color),
            )
        }
    }
}

// ---------- extracted fields ----------

@Composable
private fun ExtractedDetails(state: UiState, vm: IncidentViewModel) {
    val filled = state.schema.fields.filter { state.values[it.id]?.value?.isNotBlank() == true }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.extracted_heading),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    stringResource(R.string.inferred_count, filled.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            stringResource(R.string.extracted_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.extracted_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filled.isEmpty()) {
            Text(
                stringResource(R.string.extracted_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (field in filled) {
                FieldCard(
                    field = field,
                    value = vm.displayValue(field, state.values.getValue(field.id).value),
                    onEdit = vm::onSkipToReview,
                )
            }
        }
    }
}

@Composable
private fun FieldCard(field: FormField, value: String, onEdit: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(field),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    field.label.ifBlank { field.id },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(MinTouch)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.review_report),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun iconFor(field: FormField): ImageVector = when (field.type) {
    FieldType.DATETIME -> Icons.Outlined.CalendarToday
    FieldType.ENUM -> Icons.Outlined.Category
    FieldType.BOOLEAN -> Icons.Outlined.CheckCircle
    FieldType.NUMBER -> Icons.Outlined.Numbers
    FieldType.STRING_LIST -> Icons.Outlined.List
    FieldType.TEXTAREA -> Icons.Outlined.Notes
    FieldType.TEXT -> when {
        field.id.contains("location", ignoreCase = true) -> Icons.Outlined.LocationOn
        field.id.contains("reporter", ignoreCase = true) ||
            field.id.contains("person", ignoreCase = true) -> Icons.Outlined.Person
        else -> Icons.Outlined.Article
    }
}

// ---------- secondary controls (typing fallback, toggles, refresh) ----------

@Composable
private fun SecondaryControls(state: UiState, vm: IncidentViewModel) {
    var typingOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
