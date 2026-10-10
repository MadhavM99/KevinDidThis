package com.megathon.incidentassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.domain.FieldKey
import com.megathon.incidentassistant.domain.Severity

private val MinTouch = 56.dp

@Composable
fun ReviewScreen(state: UiState, vm: IncidentViewModel) {
    val missing = state.report.missingMandatory()
    val summary = if (missing.isEmpty()) {
        stringResource(R.string.review_all_complete)
    } else {
        stringResource(
            R.string.review_some_missing,
            missing.map { stringResource(it.labelRes) }.joinToString(", "),
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            // Text, not just colour: completeness is spelled out for everyone.
            Text(
                text = summary,
                style = MaterialTheme.typography.titleMedium,
                color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { heading() },
            )
        }

        items(FieldKey.entries.toList(), key = { it.name }) { key ->
            if (key == FieldKey.SEVERITY) {
                SeverityGroup(
                    selected = Severity.parse(state.report.get(key)),
                    onSelect = { vm.onFieldChanged(key, it.name) },
                )
            } else {
                FieldEditor(key, state.report.get(key)) { vm.onFieldChanged(key, it) }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = vm::onSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch),
                ) { Text(stringResource(R.string.review_submit)) }

                OutlinedButton(
                    onClick = vm::onReadReportAloud,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.review_read_aloud))
                }

                OutlinedButton(
                    onClick = vm::onAddMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.review_add_more))
                }
            }
        }
    }
}

@Composable
private fun FieldEditor(key: FieldKey, value: String, onChange: (String) -> Unit) {
    val base = stringResource(key.labelRes)
    val label = if (key.mandatory) stringResource(R.string.label_required, base) else base
    val showError = key.mandatory && value.isBlank()
    val multiline = key == FieldKey.DESCRIPTION || key == FieldKey.ACTIONS_TAKEN || key == FieldKey.FOLLOW_UP

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = showError,
        // Error is communicated with words (and read by TalkBack), not colour alone.
        supportingText = if (showError) {
            { Text(stringResource(R.string.field_required_error)) }
        } else null,
        minLines = if (multiline) 3 else 1,
        singleLine = !multiline,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SeverityGroup(selected: Severity?, onSelect: (Severity) -> Unit) {
    val title = stringResource(R.string.label_required, stringResource(FieldKey.SEVERITY.labelRes))
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (selected == null) {
            Text(
                stringResource(R.string.field_required_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // selectableGroup + Role.RadioButton => TalkBack says "1 of 4, selected" etc.
        Column(Modifier.selectableGroup()) {
            Severity.entries.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch)
                        .selectable(
                            selected = level == selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(level) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = level == selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(level.labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
