package com.megathon.incidentassistant.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentassistant.R
import com.megathon.incidentassistant.form.FieldOption
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField

private val MinTouch = 56.dp

/** Severity colours (used for the enum chip highlight). */
private fun severityColor(value: String): Color? = when (value.lowercase()) {
    "critical" -> Color(0xFFB71C1C)
    "high"     -> Color(0xFFE65100)
    "medium"   -> Color(0xFFF9A825)
    "low"      -> Color(0xFF2E7D32)
    else       -> null
}

/** Synthetic options so boolean fields render as a Yes/No choice group. */
private val BOOLEAN_OPTIONS = listOf(
    FieldOption(value = "yes", label = "Yes"),
    FieldOption(value = "no", label = "No"),
)

@Composable
fun ReviewScreen(state: UiState, vm: IncidentViewModel, onBack: () -> Unit) {
    val missing = state.missingRequiredLabels()
    val requiredFields = state.schema.fields.filter { it.required }
    val optionalFields = state.schema.fields.filter { !it.required }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ── Hero header ──────────────────────────────────────────────────────
        item {
            ReviewHero(
                missing = missing,
                onBack = onBack,
            )
        }

        // ── Required fields section ──────────────────────────────────────────
        item { SectionHeader(title = "Report Details", icon = Icons.Outlined.Article) }

        items(requiredFields, key = { it.id }) { field ->
            ReviewFieldItem(
                field = field,
                value = state.value(field.id),
                displayValue = vm.displayValue(field, state.value(field.id)),
                onChange = { vm.onFieldChanged(field.id, it) },
                options = when (field.type) {
                    FieldType.BOOLEAN -> BOOLEAN_OPTIONS
                    FieldType.ENUM    -> field.options
                    else              -> emptyList()
                },
            )
        }

        // ── Optional fields section ──────────────────────────────────────────
        if (optionalFields.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(title = "Additional Details", icon = Icons.Outlined.Notes)
            }
            items(optionalFields, key = { it.id }) { field ->
                ReviewFieldItem(
                    field = field,
                    value = state.value(field.id),
                    displayValue = vm.displayValue(field, state.value(field.id)),
                    onChange = { vm.onFieldChanged(field.id, it) },
                    options = when (field.type) {
                        FieldType.BOOLEAN -> BOOLEAN_OPTIONS
                        FieldType.ENUM    -> field.options
                        else              -> emptyList()
                    },
                )
            }
        }

        // ── Action buttons ───────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            ActionButtons(vm = vm)
        }
    }
}

// ── Hero banner at top ─────────────────────────────────────────────────────────

@Composable
private fun ReviewHero(missing: List<String>, onBack: () -> Unit) {
    val complete = missing.isEmpty()
    Surface(
        color = if (complete) Color(0xFF0B3D91) else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Back row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (complete) Color.White else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            // Status content
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (complete) "✓  All fields complete" else "⚠  Missing required fields",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
                    color = if (complete) Color(0xFFB4CAFF) else MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Review Report",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (complete) Color.White else MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (!complete) {
                    Text(
                        text = "Still needed: ${missing.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Text(
                        text = "Tap a field to edit it before submitting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xCCB4CAFF),
                    )
                }
            }
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.6.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

// ── Per-field review card ──────────────────────────────────────────────────────

@Composable
private fun ReviewFieldItem(
    field: FormField,
    value: String,
    displayValue: String,
    onChange: (String) -> Unit,
    options: List<FieldOption>,
) {
    val isEnum = field.type == FieldType.ENUM || field.type == FieldType.BOOLEAN
    val isEmpty = value.isBlank()
    val showError = field.required && isEmpty

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (showError)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Label row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (showError) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconForField(field),
                        contentDescription = null,
                        tint = if (showError) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    val labelText = buildString {
                        append(field.label.ifBlank { field.id })
                        if (field.required) append(" *")
                    }
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (showError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showError) {
                        Text(
                            text = "Required",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // Severity chip
                if (field.id == "severity" && value.isNotBlank()) {
                    SeverityChip(value)
                }
            }

            // Content: chips for enum, text field for everything else
            if (isEnum) {
                EnumChips(
                    options = options,
                    selected = value,
                    onSelect = onChange,
                )
            } else {
                val multiline = field.type == FieldType.TEXTAREA
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    placeholder = { Text("Tap to fill in…", style = MaterialTheme.typography.bodyMedium) },
                    isError = showError,
                    minLines = if (multiline) 3 else 1,
                    singleLine = !multiline,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Severity pill ──────────────────────────────────────────────────────────────

@Composable
private fun SeverityChip(value: String) {
    val bg = severityColor(value) ?: MaterialTheme.colorScheme.secondaryContainer
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
    ) {
        Text(
            text = value.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ── Horizontal enum chips ──────────────────────────────────────────────────────

@Composable
private fun EnumChips(options: List<FieldOption>, selected: String, onSelect: (String) -> Unit) {
    // For small option sets (≤4) use horizontal wrap chips; for larger sets fall back to radio list
    if (options.size <= 4) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = option.value.equals(selected, ignoreCase = true)
                val optionLabel = option.label.ifBlank { option.value }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(option.value) },
                        )
                        .heightIn(min = 40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = optionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else {
        // Radio list for many options
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
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Text(optionLabel, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ── Bottom action buttons ──────────────────────────────────────────────────────

@Composable
private fun ActionButtons(vm: IncidentViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = vm::onSubmit,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch),
        ) {
            Text(
                stringResource(R.string.review_submit),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = vm::onReadReportAloud,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.review_read_aloud))
            }

            OutlinedButton(
                onClick = vm::onAddMore,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.review_add_more))
            }
        }
    }
}

// ── Icon picker ────────────────────────────────────────────────────────────────

private fun iconForField(field: FormField): ImageVector = when (field.type) {
    FieldType.DATETIME    -> Icons.Outlined.CalendarToday
    FieldType.ENUM        -> if (field.id == "severity") Icons.Outlined.Warning
                             else Icons.Outlined.Category
    FieldType.BOOLEAN     -> Icons.Outlined.CheckCircle
    FieldType.NUMBER      -> Icons.Outlined.Numbers
    FieldType.STRING_LIST -> Icons.Outlined.List
    FieldType.TEXTAREA    -> Icons.Outlined.Notes
    FieldType.TEXT        -> when {
        field.id.contains("location", ignoreCase = true) -> Icons.Outlined.LocationOn
        field.id.contains("reporter", ignoreCase = true) ||
            field.id.contains("person", ignoreCase = true) -> Icons.Outlined.Person
        else -> Icons.Outlined.Article
    }
}
