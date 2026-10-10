package com.megathon.incidentassistant.form

enum class FieldStatus { EMPTY, FILLED, UNKNOWN, AMBIGUOUS }

data class FieldValue(
    val value: String = "",
    val confidence: Double = 0.0,
    val evidence: String = "",
    val status: FieldStatus = FieldStatus.EMPTY,
    val utterance: Int = 0,
)

data class ApplyResult(
    val fieldId: String,
    val accepted: Boolean,
    val reason: String? = null,
)

/** Result of value normalization — enum answers are matched against value/label/aliases. */
sealed interface Normalization {
    data class Ok(val value: String) : Normalization
    data class Rejected(val reason: String, val allowed: List<String> = emptyList()) : Normalization
}

object FieldNormalizer {

    private val WHITESPACE = Regex("\\s+")
    private val YES_WORDS = setOf("yes", "y", "true", "affirmative", "confirmed", "yep", "yeah")
    private val NO_WORDS = setOf("no", "n", "false", "negative", "nope")
    private val NUMBER_TOKEN = Regex("-?\\d+(?:\\.\\d+)?")
    private val LIST_SPLIT = Regex("\\s*(?:,|;|&|\\band\\b|\\bplus\\b)\\s*")

    fun normalize(field: FormField, raw: String): Normalization {
        val value = raw.trim().replace(WHITESPACE, " ")
        if (value.isEmpty()) return Normalization.Rejected("empty value")
        return when (field.type) {
            FieldType.ENUM -> matchOption(field, value)
            FieldType.BOOLEAN -> matchBoolean(value)
            FieldType.NUMBER -> matchNumber(value)
            FieldType.STRING_LIST -> Normalization.Ok(normalizeList(value))
            else -> Normalization.Ok(value)
        }
    }

    private fun matchBoolean(value: String): Normalization {
        val words = value.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return when {
            words.any { it in YES_WORDS } -> Normalization.Ok("yes")
            words.any { it in NO_WORDS } -> Normalization.Ok("no")
            else -> Normalization.Rejected("no yes/no answer in \"$value\"", listOf("yes", "no"))
        }
    }

    private fun matchNumber(value: String): Normalization {
        val number = NUMBER_TOKEN.find(value)?.value
            ?: return Normalization.Rejected("no number found in \"$value\"", listOf("number"))
        return Normalization.Ok(number)
    }

    /** "Alice, Bob and Carol" → "Alice; Bob; Carol" (deduplicated, order kept). */
    private fun normalizeList(value: String): String =
        value.split(LIST_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")

    private fun matchOption(field: FormField, value: String): Normalization {
        // Exact match on value / label / alias (case-insensitive).
        field.options.firstOrNull { option ->
            option.surfaces().any { it.equals(value, ignoreCase = true) }
        }?.let { return Normalization.Ok(it.value) }

        // Then a contained match, e.g. "medium severity" contains "medium".
        field.options.firstOrNull { option ->
            option.surfaces().any { surface -> surface.length >= 4 && value.contains(surface, ignoreCase = true) }
        }?.let { return Normalization.Ok(it.value) }

        return Normalization.Rejected(
            reason = "no option matched \"$value\"",
            allowed = field.options.map { it.value },
        )
    }

    private fun FieldOption.surfaces(): List<String> =
        (listOf(value, label) + aliases).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * The running form plus the deterministic "mandatory fields present?" gate
 * (docs/DESIGN.md §3.3/§3.4). The model never writes values directly — everything goes
 * through [apply]/[markUnknown] with normalization and merge rules.
 */
class FormState(val schema: FormSchema) {

    private val values = LinkedHashMap<String, FieldValue>()
    private var utteranceCounter = 0

    /** Fields the app filled from trusted context ([prefill]); model updates never change them. */
    private val appProvided = mutableSetOf<String>()

    fun isAppProvided(fieldId: String): Boolean = fieldId in appProvided

    fun beginUtterance(): Int {
        utteranceCounter += 1
        return utteranceCounter
    }

    val lastUtterance: Int get() = utteranceCounter

    fun get(fieldId: String): FieldValue = values[fieldId] ?: FieldValue()

    fun text(fieldId: String): String = get(fieldId).value

    fun entries(): Map<String, FieldValue> = values.toMap()

    /** FILLED and UNKNOWN satisfy the gate; EMPTY and AMBIGUOUS do not. */
    fun isSatisfied(fieldId: String): Boolean {
        val status = get(fieldId).status
        return status == FieldStatus.FILLED || status == FieldStatus.UNKNOWN
    }

    fun missingRequired(): List<FormField> = schema.requiredFields.filter { !isSatisfied(it.id) }

    fun gatePasses(): Boolean = missingRequired().isEmpty()

    fun apply(
        fieldId: String,
        rawValue: String,
        confidence: Double,
        evidence: String?,
        status: UpdateStatus,
    ): ApplyResult {
        val field = schema.field(fieldId)
            ?: return ApplyResult(fieldId, accepted = false, reason = "unknown field")
        if (fieldId in appProvided) return ApplyResult(fieldId, accepted = false, reason = "set by the app")
        if (status == UpdateStatus.UNKNOWN) return markUnknown(fieldId, evidence)

        val normalized = when (val result = FieldNormalizer.normalize(field, rawValue)) {
            is Normalization.Rejected -> return ApplyResult(fieldId, accepted = false, reason = result.reason)
            is Normalization.Ok -> result.value
        }
        val clamped = confidence.coerceIn(0.0, 1.0)

        val existing = values[fieldId]
        if (existing != null && existing.status == FieldStatus.FILLED) {
            val same = existing.value.equals(normalized, ignoreCase = true)
            val explicitCorrection = status == UpdateStatus.ANSWERED
            val stronger = clamped >= existing.confidence + 0.05
            if (!same && !explicitCorrection && !stronger) {
                return ApplyResult(
                    fieldId,
                    accepted = false,
                    reason = "keeps existing value \"${existing.value}\"",
                )
            }
        }

        values[fieldId] = FieldValue(
            value = normalized,
            confidence = clamped,
            evidence = evidence ?: existing?.evidence.orEmpty(),
            status = if (status == UpdateStatus.ANSWERED || clamped >= 0.5) {
                FieldStatus.FILLED
            } else {
                FieldStatus.AMBIGUOUS
            },
            utterance = utteranceCounter,
        )
        return ApplyResult(fieldId, accepted = true)
    }

    /** Marks a field as explicitly "not specified" — counts as present (§5.2). */
    fun markUnknown(fieldId: String, evidence: String? = null): ApplyResult {
        val existing = values[fieldId]
        if (existing != null && existing.status == FieldStatus.FILLED) {
            return ApplyResult(fieldId, accepted = false, reason = "already filled")
        }
        values[fieldId] = (existing ?: FieldValue()).copy(
            status = FieldStatus.UNKNOWN,
            evidence = evidence ?: existing?.evidence.orEmpty(),
            utterance = utteranceCounter,
        )
        return ApplyResult(fieldId, accepted = true)
    }

    /**
     * A value the app knows from trusted context (e.g. the signed-in reporter). Validated
     * like any other value; on success the field is filled and locked against model
     * updates. The user can still change it on the Review screen ([setDirect]).
     */
    fun prefill(fieldId: String, value: String, evidence: String): ApplyResult {
        val field = schema.field(fieldId)
            ?: return ApplyResult(fieldId, accepted = false, reason = "unknown field")
        val normalized = when (val result = FieldNormalizer.normalize(field, value)) {
            is Normalization.Rejected -> return ApplyResult(fieldId, accepted = false, reason = result.reason)
            is Normalization.Ok -> result.value
        }
        values[fieldId] = FieldValue(
            value = normalized,
            confidence = 1.0,
            evidence = evidence,
            status = FieldStatus.FILLED,
            utterance = 0,
        )
        appProvided += fieldId
        return ApplyResult(fieldId, accepted = true)
    }

    /** The user confirmed an unsure (AMBIGUOUS) value out loud — it now counts as filled. */
    fun confirm(fieldId: String, evidence: String? = null): ApplyResult {
        val existing = values[fieldId]
        if (existing == null || existing.status != FieldStatus.AMBIGUOUS) {
            return ApplyResult(fieldId, accepted = false, reason = "nothing to confirm")
        }
        values[fieldId] = existing.copy(
            status = FieldStatus.FILLED,
            confidence = maxOf(existing.confidence, CONFIRMED_CONFIDENCE),
            evidence = evidence ?: existing.evidence,
            utterance = utteranceCounter,
        )
        return ApplyResult(fieldId, accepted = true)
    }

    /** The user rejected a value out loud — the field is empty again and will be re-asked. */
    fun clear(fieldId: String): ApplyResult {
        if (schema.field(fieldId) == null) return ApplyResult(fieldId, accepted = false, reason = "unknown field")
        values.remove(fieldId)
        return ApplyResult(fieldId, accepted = true)
    }

    /** Direct edit from the Review screen — the user is authoritative (no merge rules). */
    fun setDirect(fieldId: String, value: String): ApplyResult {
        val field = schema.field(fieldId)
            ?: return ApplyResult(fieldId, accepted = false, reason = "unknown field")
        val normalized = when (val result = FieldNormalizer.normalize(field, value)) {
            is Normalization.Rejected -> value.trim()
            is Normalization.Ok -> result.value
        }
        if (normalized.isEmpty()) {
            values.remove(fieldId)
        } else {
            values[fieldId] = FieldValue(
                value = normalized,
                confidence = 1.0,
                evidence = "edited by user",
                status = FieldStatus.FILLED,
                utterance = utteranceCounter,
            )
        }
        return ApplyResult(fieldId, accepted = true)
    }

    fun reset() {
        values.clear()
        appProvided.clear()
        utteranceCounter = 0
    }

    private companion object {
        const val CONFIRMED_CONFIDENCE = 0.8
    }
}
