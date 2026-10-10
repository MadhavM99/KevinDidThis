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
import com.megathon.incidentassistant.form.FieldOption
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField

private val MinTouch = 56.dp

/** Synthetic options so boolean fields render as a Yes/No choice group. */
private val BOOLEAN_OPTIONS = listOf(
    FieldOption(value = "yes", label = "Yes"),
    FieldOption(value = "no", label = "No"),
)

@Composable
fun ReviewScreen(state: UiState, vm: IncidentViewModel) {
    val missing = state.missingRequiredLabels()
    val summary = if (missing.isEmpty()) {
        stringResource(R.string.review_all_complete)
    } else {
        stringResource(R.string.review_some_missing, missing.joinToString(", "))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
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

        // The form is dynamic and server-delivered: fields, labels, types, options and
        // required flags all come from the definition currently in use.
        items(state.schema.fields, key = { it.id }) { field ->
            if (field.type == FieldType.ENUM || field.type == FieldType.BOOLEAN) {
                OptionsGroup(
                    field = field,
                    options = if (field.type == FieldType.BOOLEAN) BOOLEAN_OPTIONS else field.options,
                    selected = state.value(field.id),
                    onSelect = { vm.onFieldChanged(field.id, it) },
                )
            } else {
                FieldEditor(
                    field = field,
                    value = state.value(field.id),
                    onChange = { vm.onFieldChanged(field.id, it) },
                )
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
private fun FieldEditor(field: FormField, value: String, onChange: (String) -> Unit) {
    val base = field.label.ifBlank { field.id }
    val label = if (field.required) stringResource(R.string.label_required, base) else base
    val showError = field.required && value.isBlank()
    val multiline = field.type == FieldType.TEXTAREA

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
private fun OptionsGroup(
    field: FormField,
    options: List<FieldOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val title = if (field.required) {
        stringResource(R.string.label_required, field.label.ifBlank { field.id })
    } else {
        field.label.ifBlank { field.id }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (field.required && selected.isBlank()) {
            Text(
                stringResource(R.string.field_required_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // selectableGroup + Role.RadioButton => TalkBack says "1 of 4, selected" etc.
        Column(Modifier.selectableGroup()) {
            options.forEach { option ->
                val optionLabel = option.label.ifBlank { option.value }
                val isSelected = option.value.equals(selected, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(option.value) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(optionLabel, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
