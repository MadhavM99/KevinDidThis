package com.megathon.incidentassistant.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.megathon.incidentassistant.R
import androidx.compose.ui.res.stringResource
import com.megathon.incidentassistant.form.FieldFormats
import com.megathon.incidentassistant.form.FieldFormats.DateKind
import com.megathon.incidentassistant.form.FieldOption
import com.megathon.incidentassistant.form.FormField
import java.time.LocalDate
import java.time.LocalTime

private val MinTouch = 48.dp

/**
 * Free-text editors (text, long text, number, email, phone, web address, free lists).
 *
 * The box keeps its own text while it is being edited: every keystroke is still saved, but
 * the saved value is normalised (trimmed, list separators, numbers) and echoing that back
 * would eat the space or comma just typed. When the box is left — or the value changes from
 * elsewhere, e.g. by voice — it shows the saved value again.
 */
@Composable
fun TextFieldEditor(
    field: FormField,
    value: String,
    onChange: (String) -> Unit,
    isError: Boolean,
    multiline: Boolean,
    keyboard: KeyboardType,
    supporting: String? = null,
) {
    var text by remember(field.id) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        placeholder = { Text(field.hint ?: stringResource(R.string.edit_tap_to_fill), style = MaterialTheme.typography.bodyMedium) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        minLines = if (multiline) 3 else 1,
        singleLine = !multiline,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) text = value
            },
    )
}

/** The keyboard that fits what the form asks for (its HTML input type). */
fun keyboardFor(field: FormField): KeyboardType = when (field.input) {
    "number", "range" -> KeyboardType.Decimal
    "email" -> KeyboardType.Email
    "tel" -> KeyboardType.Phone
    "url" -> KeyboardType.Uri
    else -> if (field.type == com.megathon.incidentassistant.form.FieldType.NUMBER) KeyboardType.Decimal else KeyboardType.Text
}

/**
 * A date / time / date-time / month / week answer, set with the system pickers (never typed),
 * written in the field's own format. An answer the app can't read as a date (e.g. "last
 * night") is still shown, with a note to pick the real one.
 */
@Composable
fun DateTimeEditor(field: FormField, value: String, onChange: (String) -> Unit, isError: Boolean) {
    val context = LocalContext.current
    val kind = FieldFormats.kindOf(field)
    val moment = FieldFormats.parse(value)
    val unreadable = value.isNotBlank() && (moment == null || FieldFormats.toInputValue(kind, moment) == null)

    fun save(date: LocalDate?, time: LocalTime?) {
        val merged = FieldFormats.Moment(date ?: moment?.date ?: LocalDate.now(), time ?: moment?.time)
        FieldFormats.toInputValue(kind, merged)?.let(onChange)
    }
    fun pickTime(after: (LocalTime) -> Unit) {
        val start = moment?.time ?: LocalTime.now().withSecond(0).withNano(0)
        TimePickerDialog(context, { _, h, m -> after(LocalTime.of(h, m)) }, start.hour, start.minute, DateFormat.is24HourFormat(context)).show()
    }
    fun pickDate(after: (LocalDate) -> Unit) {
        val start = moment?.date ?: LocalDate.now()
        DatePickerDialog(context, { _, y, m, d -> after(LocalDate.of(y, m + 1, d)) }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }
    val open: () -> Unit = when (kind) {
        DateKind.TIME -> { { pickTime { save(null, it) } } }
        DateKind.DATETIME -> { { pickDate { d -> pickTime { t -> save(d, t) } } } }
        else -> { { pickDate { save(it, null) } } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit_pick)) { open() },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (kind == DateKind.TIME) Icons.Outlined.Schedule else Icons.Outlined.CalendarToday,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (value.isBlank()) stringResource(R.string.edit_pick) else FieldFormats.display(field, value),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (value.isNotBlank()) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.edit_clear),
                        modifier = Modifier
                            .size(MinTouch)
                            .clickable(role = Role.Button) { onChange("") }
                            .padding(12.dp),
                    )
                }
            }
        }
        if (unreadable) {
            Text(
                stringResource(R.string.edit_not_a_date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (kind == DateKind.DATETIME && moment?.date != null) {
            // Change only one half of an existing date-time.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate { save(it, null) } }) { Text(stringResource(R.string.edit_change_date)) }
                OutlinedButton(onClick = { pickTime { save(null, it) } }) { Text(stringResource(R.string.edit_change_time)) }
            }
        }
    }
}

/**
 * Several choices at once (a checkbox group, `<select multiple>`): one box per option.
 * Saved as "value; value"; answers that are not among the options are kept and listed.
 */
@Composable
fun MultiChoiceEditor(options: List<FieldOption>, value: String, onChange: (String) -> Unit) {
    val items = value.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
    fun matches(option: FieldOption, item: String) =
        option.value.equals(item, ignoreCase = true) || option.label.equals(item, ignoreCase = true)
    val chosen = options.filter { o -> items.any { matches(o, it) } }.map { it.value }.toSet()
    val extra = items.filter { item -> options.none { matches(it, item) } }

    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            val on = option.value in chosen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch)
                    .toggleable(value = on, role = Role.Checkbox) { checked ->
                        val next = options.filter { o -> if (o.value == option.value) checked else o.value in chosen }
                            .map { it.value } + extra
                        onChange(next.joinToString("; "))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Spacer(Modifier.width(10.dp))
                Text(option.label.ifBlank { option.value }, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (extra.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.edit_also, extra.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(chosen.joinToString("; ")) }) { Text(stringResource(R.string.edit_clear)) }
            }
        }
    }
}
