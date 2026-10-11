package com.megathon.incidentassistant.form

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Dates and times in form values. A [FieldType.DATETIME] field is really one of five HTML
 * kinds ([FormField.input]): a date, a time, a date-time, a month or a week. Values arrive in
 * many shapes (the model writes "2026-10-11T10:00:00+05:30", a date picker "2026-10-11", a
 * month input "2026-10"), so they are read leniently and written back in the field's own
 * format — the one its HTML input accepts.
 */
object FieldFormats {

    enum class DateKind { DATE, TIME, DATETIME, MONTH, WEEK }

    /** A value read as a date and/or a time of day (either may be missing). */
    data class Moment(val date: LocalDate?, val time: LocalTime?)

    fun kindOf(field: FormField): DateKind = when (field.input) {
        "date" -> DateKind.DATE
        "time" -> DateKind.TIME
        "month" -> DateKind.MONTH
        "week" -> DateKind.WEEK
        else -> DateKind.DATETIME
    }

    fun parse(raw: String): Moment? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()?.let { return Moment(it.toLocalDate(), it.toLocalTime()) }
        runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull()?.let { return Moment(it.toLocalDate(), it.toLocalTime()) }
        runCatching { LocalDate.parse(value) }.getOrNull()?.let { return Moment(it, null) }
        runCatching { LocalTime.parse(value) }.getOrNull()?.let { return Moment(null, it) }
        runCatching { YearMonth.parse(value) }.getOrNull()?.let { return Moment(it.atDay(1), null) }
        WEEK.matchEntire(value)?.let { m ->
            val (year, week) = m.destructured
            val date = LocalDate.of(year.toInt(), 6, 1)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return Moment(date, null)
        }
        return null
    }

    /** The value as the field's HTML input writes it, or null when [moment] lacks a part it needs. */
    fun toInputValue(kind: DateKind, moment: Moment): String? = when (kind) {
        DateKind.DATE -> moment.date?.toString()
        DateKind.TIME -> moment.time?.format(HH_MM)
        DateKind.DATETIME -> moment.date?.let { d -> "$d" + "T" + (moment.time ?: LocalTime.MIDNIGHT).format(HH_MM) }
        DateKind.MONTH -> moment.date?.let { YearMonth.from(it).toString() }
        DateKind.WEEK -> moment.date?.let {
            "%d-W%02d".format(it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }
    }

    /** [raw] in the field's own format; unreadable values are returned unchanged. */
    fun normalize(field: FormField, raw: String): String =
        parse(raw)?.let { toInputValue(kindOf(field), it) } ?: raw

    /** For people: "Sun, 11 Oct 2026 · 10:00", "October 2026", "Week 41, 2026"… */
    fun display(field: FormField, raw: String): String {
        val moment = parse(raw) ?: return raw
        return when (kindOf(field)) {
            DateKind.DATE -> moment.date?.format(DAY) ?: raw
            DateKind.TIME -> moment.time?.format(HH_MM) ?: raw
            DateKind.MONTH -> moment.date?.format(MONTH) ?: raw
            DateKind.WEEK -> moment.date?.let {
                "Week ${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}, ${it.get(IsoFields.WEEK_BASED_YEAR)}"
            } ?: raw
            DateKind.DATETIME -> listOfNotNull(moment.date?.format(DAY), moment.time?.format(HH_MM))
                .joinToString(" · ").ifEmpty { raw }
        }
    }

    private val WEEK = Regex("(\\d{4})-W(\\d{1,2})")
    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
}
