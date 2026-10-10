package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.data.ReportIds
import com.megathon.incidentassistant.data.ReportRepository
import com.megathon.incidentassistant.form.ConversationTurn
import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.form.FieldType
import com.megathon.incidentassistant.form.FormField
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.PendingQuestion
import com.megathon.incidentassistant.form.ReportJson
import com.megathon.incidentassistant.form.UpdateStatus
import com.megathon.incidentassistant.net.ReportSubmitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the conversation is (docs/DESIGN.md §5.1, from the dialog's point of view). */
enum class SessionPhase {
    /** Asking for a missing required field (or nothing asked yet). */
    COLLECTING,
    /** "I'll mark X as not specified — okay?" */
    CONFIRM_UNKNOWN,
    /** "Just to check, X is Y?" for an unsure value. */
    CONFIRM_VALUE,
    /** One combined question for optional fields. */
    OPTIONAL,
    /** Summary read back; waiting for "yes" to file. */
    CONFIRMING,
    /** "Discard this report?" */
    CONFIRM_CANCEL,
    SUBMITTING,
    SUBMITTED,
    CANCELLED,
}

enum class ReplyKind { ASK, CONFIRM, INFO, ERROR, DONE }

/** Stage timings of one turn in ms (§11.3). Contains no user content, safe to log. */
data class TurnMetrics(
    val promptMs: Long = 0,
    val llmMs: Long = 0,
    val parseMs: Long = 0,
    val applyMs: Long = 0,
    val policyMs: Long = 0,
    val totalMs: Long = 0,
    val llmCalls: Int = 0,
    /** Answered without the LLM (fast intent or exact enum answer). */
    val fastPath: Boolean = false,
    /** Set when the model could not be used this turn (error / timeout / unparsable). */
    val llmFailure: String? = null,
)

/** What the assistant says after a turn, plus what the UI needs to render it. */
data class SessionReply(
    val speech: String,
    val kind: ReplyKind,
    val phase: SessionPhase,
    /** Fields the assistant now expects an answer for. */
    val pendingFieldIds: List<String> = emptyList(),
    val changedFieldIds: List<String> = emptyList(),
    val reportId: String? = null,
    /** True only when the report server accepted the report. */
    val delivered: Boolean = false,
    val metrics: TurnMetrics? = null,
)

/**
 * The conversation engine: one instance per report draft (docs/DESIGN.md §2.1).
 *
 *   final transcript → fast intents → [ExtractionEngine] (LLM) → [ToolExecutor]
 *   (validate + merge into [FormState]) → [DialogPolicy] (mandatory-field gate, next
 *   question) → [SessionReply] the UI shows and speaks.
 *
 * Pure Kotlin and host-testable. It knows nothing about audio: the caller feeds it the
 * ASR transcript and speaks the reply with the TTS backend. Turns are serialised by a
 * mutex, so overlapping utterances can never interleave their form updates. The app —
 * never the model — decides when the gate passes and when a report is filed: only
 * [SessionReply] after an explicit user "yes" (or the Submit button) files.
 */
class IncidentSession(
    val schema: FormSchema,
    private val extraction: ExtractionEngine,
    private val phrases: SessionPhrases,
    private val repository: ReportRepository,
    private val submitter: ReportSubmitter,
    private val now: () -> String,
    private val newReportId: () -> String = ReportIds::newReportId,
) {
    val form = FormState(schema)

    var phase: SessionPhase = SessionPhase.COLLECTING
        private set

    /** Utterances heard so far, kept even when extraction failed (nothing is lost). */
    val utterances: List<String> get() = heard.toList()

    private val policy = DialogPolicy(schema)
    private val executor = ToolExecutor(schema)
    private val memory = PolicyMemory()
    private val mutex = Mutex()

    private val heard = mutableListOf<String>()
    private val history = ArrayDeque<ConversationTurn>()
    private var pending: PendingQuestion? = null
    private var phaseBeforeCancel: SessionPhase? = null
    private var lastAsk: SessionReply? = null
    private var reportId: String? = null

    private var firstTurnStartNanos: Long? = null
    private var reviewReadyMs: Long? = null
    private val totals = mutableMapOf<String, Long>()

    /** One user utterance (final ASR transcript, after the user reviewed it). */
    suspend fun onUtterance(transcript: String): SessionReply = mutex.withLock {
        val turn = Turn(System.nanoTime())
        if (firstTurnStartNanos == null) firstTurnStartNanos = turn.startNanos
        val reply = handle(transcript.trim(), turn)
        reply.copy(metrics = turn.finish())
    }

    /** The Review screen's Submit button: a tap is an explicit confirmation by the user. */
    suspend fun submitConfirmedByUser(): SessionReply = mutex.withLock {
        when (phase) {
            SessionPhase.SUBMITTED -> info(phrases.alreadyFiled(reportId.orEmpty()), ReplyKind.DONE)
            SessionPhase.CANCELLED -> info(phrases.sessionClosed(), ReplyKind.INFO)
            else -> submit()
        }
    }

    /** The last question, for "repeat" without a turn (e.g. a button). */
    fun lastQuestion(): SessionReply? = lastAsk

    /** Stage timing totals for the report JSON `metrics` object. */
    fun metrics(): Map<String, Long> = buildMap {
        putAll(totals)
        reviewReadyMs?.let { put("utterance_to_review_ms", it) }
    }

    // ---------- turn handling ----------

    private suspend fun handle(text: String, turn: Turn): SessionReply {
        when (phase) {
            SessionPhase.SUBMITTED -> return info(phrases.alreadyFiled(reportId.orEmpty()), ReplyKind.DONE)
            SessionPhase.CANCELLED -> return info(phrases.sessionClosed(), ReplyKind.INFO)
            SessionPhase.SUBMITTING -> return info(phrases.sessionClosed(), ReplyKind.INFO)
            else -> Unit
        }
        if (text.isEmpty()) return reAsk(prefix = phrases.didNotHear())

        val context = history.toList()
        form.beginUtterance()
        heard += text
        remember("user", text)

        val intent = FastIntents.classify(text)
        if (intent != null) turn.fastPath = true

        if (intent == FastIntent.REPEAT) return lastAsk?.copy(changedFieldIds = emptyList()) ?: advance(turn)
        if ((intent == FastIntent.CANCEL || intent == FastIntent.START_OVER) && phase != SessionPhase.CONFIRM_CANCEL) {
            phaseBeforeCancel = phase
            phase = SessionPhase.CONFIRM_CANCEL
            return say(phrases.confirmCancel(), ReplyKind.CONFIRM)
        }
        if (phase == SessionPhase.CONFIRM_CANCEL) {
            val resume = phaseBeforeCancel ?: SessionPhase.COLLECTING
            phaseBeforeCancel = null
            when (intent) {
                FastIntent.YES -> {
                    phase = SessionPhase.CANCELLED
                    pending = null
                    return say(phrases.cancelled(), ReplyKind.DONE)
                }
                FastIntent.NO -> {
                    phase = resume
                    return lastAsk ?: advance(turn)
                }
                // Anything else: the user moved on — carry on where we were with this utterance.
                else -> phase = resume
            }
        }

        return when (phase) {
            SessionPhase.CONFIRM_UNKNOWN -> onConfirmUnknown(text, intent, context, turn)
            SessionPhase.CONFIRM_VALUE -> onConfirmValue(text, intent, context, turn)
            SessionPhase.CONFIRMING -> onConfirming(text, intent, context, turn)
            SessionPhase.OPTIONAL -> onOptional(text, intent, context, turn)
            else -> onCollecting(text, intent, context, turn)
        }
    }

    private suspend fun onCollecting(
        text: String,
        intent: FastIntent?,
        context: List<ConversationTurn>,
        turn: Turn,
    ): SessionReply {
        val asked = pending?.fields?.singleOrNull()
        if (asked != null && (intent == FastIntent.UNKNOWN || intent == FastIntent.SKIP)) {
            return cannotAnswer(asked, turn)
        }
        if (asked != null && asked.type == FieldType.ENUM) {
            exactOption(asked, text)?.let { option ->
                // "Medium." for a severity question: unambiguous, no model call needed.
                turn.fastPath = true
                val applied = turn.timed("apply") {
                    form.apply(asked.id, option, EXACT_ANSWER_CONFIDENCE, text, UpdateStatus.ANSWERED)
                }
                if (applied.accepted) {
                    memory.attempts.remove(asked.id)
                    return advance(turn, changed = listOf(asked.id))
                }
            }
        }

        turn.fastPath = false
        val outcome = extractAndApply(text, context, turn)
        if (asked != null && asked.id in outcome.unknownRequested && !form.isSatisfied(asked.id)) {
            return cannotAnswer(asked, turn, changed = outcome.changed)
        }
        if (asked != null && form.isSatisfied(asked.id)) {
            memory.attempts.remove(asked.id)
        } else if (asked != null && outcome.changed.isEmpty()) {
            // Nothing usable at all. (A correction to another field is not a failed answer.)
            memory.attempts[asked.id] = (memory.attempts[asked.id] ?: 0) + 1
        }
        // With a pending question the rephrased re-ask already says "sorry, I didn't catch…".
        val prefix = if (outcome.failed && outcome.changed.isEmpty() && asked == null) phrases.didNotUnderstand() else null
        return advance(turn, changed = outcome.changed, prefix = prefix)
    }

    private suspend fun onConfirmUnknown(
        text: String,
        intent: FastIntent?,
        context: List<ConversationTurn>,
        turn: Turn,
    ): SessionReply {
        val field = pending?.fields?.singleOrNull() ?: return advance(turn)
        return when (intent) {
            FastIntent.YES -> {
                turn.timed("apply") { form.markUnknown(field.id, UNKNOWN_EVIDENCE) }
                memory.attempts.remove(field.id)
                advance(turn, changed = listOf(field.id))
            }
            FastIntent.NO -> {
                memory.attempts[field.id] = 0
                askField(field, attempt = 0)
            }
            else -> {
                // Probably an answer after all ("oh wait, it was medium").
                turn.fastPath = false
                val outcome = extractAndApply(text, context, turn)
                advance(turn, changed = outcome.changed)
            }
        }
    }

    private suspend fun onConfirmValue(
        text: String,
        intent: FastIntent?,
        context: List<ConversationTurn>,
        turn: Turn,
    ): SessionReply {
        val field = pending?.fields?.singleOrNull() ?: return advance(turn)
        return when (intent) {
            FastIntent.YES -> {
                turn.timed("apply") { form.confirm(field.id) }
                advance(turn, changed = listOf(field.id))
            }
            FastIntent.NO -> {
                turn.timed("apply") { form.clear(field.id) }
                memory.attempts[field.id] = 0
                advance(turn, changed = listOf(field.id))
            }
            else -> {
                turn.fastPath = false
                val outcome = extractAndApply(text, context, turn)
                advance(turn, changed = outcome.changed)
            }
        }
    }

    private suspend fun onOptional(
        text: String,
        intent: FastIntent?,
        context: List<ConversationTurn>,
        turn: Turn,
    ): SessionReply {
        val asked = pending?.fields.orEmpty()
        val declined = intent == FastIntent.NO || intent == FastIntent.SKIP ||
            intent == FastIntent.NONE || intent == FastIntent.UNKNOWN
        if (declined) {
            val skipped = closeOptional(asked)
            return advance(turn, changed = skipped)
        }
        turn.fastPath = false
        val outcome = extractAndApply(text, context, turn)
        // Asked once: whatever the answer did not cover is skipped and never re-asked.
        val skipped = closeOptional(asked)
        return advance(turn, changed = (outcome.changed + skipped).distinct())
    }

    private suspend fun onConfirming(
        text: String,
        intent: FastIntent?,
        context: List<ConversationTurn>,
        turn: Turn,
    ): SessionReply = when (intent) {
        FastIntent.YES -> submit()
        FastIntent.NO -> say(phrases.whatToChange(), ReplyKind.ASK)
        FastIntent.UNKNOWN, FastIntent.SKIP, FastIntent.NONE -> lastAsk ?: advance(turn)
        else -> {
            // A correction or extra detail: extract it, then read the updated summary back.
            turn.fastPath = false
            val outcome = extractAndApply(text, context, turn)
            val prefix = when {
                outcome.changed.isNotEmpty() -> null
                outcome.failed -> phrases.didNotUnderstand()
                else -> phrases.noChangeHeard()
            }
            advance(turn, changed = outcome.changed, prefix = prefix)
        }
    }

    // ---------- extraction ----------

    private class Outcome(val changed: List<String>, val unknownRequested: List<String>, val failed: Boolean)

    private suspend fun extractAndApply(text: String, context: List<ConversationTurn>, turn: Turn): Outcome {
        val asked = pending
        val result = extraction.extract(schema, form, asked, text, context.takeLast(HISTORY_IN_PROMPT))
        turn.record(result)

        val pendingIds = asked?.fields.orEmpty().map { it.id }.toSet()
        if (result.failure != null) {
            // No usable model output. Never lose the answer to a free-text question: it IS
            // the answer to what was just asked. Anything else is kept in the transcript
            // only, and the user is asked again.
            val field = asked?.fields?.singleOrNull()
            if (field != null && field.type in FREE_TEXT_TYPES && !form.isSatisfied(field.id)) {
                val applied = turn.timed("apply") {
                    form.apply(field.id, text, FALLBACK_CONFIDENCE, text, UpdateStatus.ANSWERED)
                }
                if (applied.accepted) return Outcome(listOf(field.id), emptyList(), failed = true)
            }
            return Outcome(emptyList(), emptyList(), failed = true)
        }
        val execution = turn.timed("apply") { executor.execute(result.calls, form, pendingIds) }
        return Outcome(execution.changed, execution.unknownRequested, failed = false)
    }

    // ---------- policy → reply ----------

    private fun advance(turn: Turn, changed: List<String> = emptyList(), prefix: String? = null): SessionReply {
        val action = turn.timed("policy") { policy.next(form, memory) }
        val reply = when (action) {
            is PolicyAction.AskField -> askField(action.field, action.attempt)
            is PolicyAction.ConfirmValue -> {
                phase = SessionPhase.CONFIRM_VALUE
                pending = PendingQuestion(FieldText.label(action.field), listOf(action.field))
                say(
                    phrases.confirmValue(FieldText.label(action.field), FieldText.display(action.field, action.value)),
                    ReplyKind.CONFIRM,
                )
            }
            is PolicyAction.OfferUnknown -> offerUnknown(action.field)
            is PolicyAction.AskOptional -> {
                val question = action.prompt ?: phrases.askOptional(action.fields.map(FieldText::label))
                phase = SessionPhase.OPTIONAL
                pending = PendingQuestion(question, action.fields)
                say(question, ReplyKind.ASK)
            }
            PolicyAction.Confirm -> {
                phase = SessionPhase.CONFIRMING
                pending = null
                if (reviewReadyMs == null) {
                    firstTurnStartNanos?.let { reviewReadyMs = (System.nanoTime() - it) / 1_000_000 }
                }
                say(phrases.confirmReport(summary()), ReplyKind.CONFIRM)
            }
        }
        val speech = listOfNotNull(prefix, reply.speech).joinToString(" ")
        return reply.copy(speech = speech, changedFieldIds = changed).also { lastAsk = it }
    }

    private fun askField(field: FormField, attempt: Int): SessionReply {
        val label = FieldText.label(field)
        val question = field.question?.takeIf { it.isNotBlank() } ?: phrases.askByLabel(label)
        val text = if (attempt == 0) {
            question
        } else {
            val again = phrases.askAgain(label, question, FieldText.optionLabels(field))
            val stuck = attempt >= DialogPolicy.MAX_ATTEMPTS && !schema.behavior.allowUnknownRequired
            if (stuck) "$again ${phrases.askAgainCancelHint()}" else again
        }
        phase = SessionPhase.COLLECTING
        pending = PendingQuestion(question, listOf(field))
        return say(text, ReplyKind.ASK).also { lastAsk = it }
    }

    private fun offerUnknown(field: FormField): SessionReply {
        phase = SessionPhase.CONFIRM_UNKNOWN
        pending = PendingQuestion(field.question ?: FieldText.label(field), listOf(field))
        return say(phrases.offerUnknown(FieldText.label(field)), ReplyKind.CONFIRM).also { lastAsk = it }
    }

    /** "I don't know" / "skip" for the field just asked (§5.2). */
    private fun cannotAnswer(field: FormField, turn: Turn, changed: List<String> = emptyList()): SessionReply {
        if (field.required && schema.behavior.allowUnknownRequired) {
            return offerUnknown(field).copy(changedFieldIds = changed)
        }
        if (!field.required) {
            return advance(turn, changed = changed + closeOptional(listOf(field)))
        }
        memory.attempts[field.id] = (memory.attempts[field.id] ?: 0) + 1
        return advance(turn, changed = changed)
    }

    /** Marks asked optional fields the user did not fill as skipped ("unknown" in the report). */
    private fun closeOptional(fields: List<FormField>): List<String> {
        val skipped = mutableListOf<String>()
        for (field in fields) {
            memory.closedOptional += field.id
            if (!form.isSatisfied(field.id) && form.markUnknown(field.id, SKIPPED_EVIDENCE).accepted) {
                skipped += field.id
            }
        }
        return skipped
    }

    private fun summary(): String = schema.fields.mapNotNull { field ->
        val value = form.get(field.id)
        when {
            value.status == FieldStatus.FILLED ->
                phrases.summaryLine(FieldText.label(field), FieldText.display(field, value.value).trimEnd('.', ' '))
            value.status == FieldStatus.UNKNOWN && field.required ->
                phrases.summaryLine(FieldText.label(field), phrases.notSpecified())
            else -> null
        }
    }.joinToString(" ")

    private fun reAsk(prefix: String): SessionReply {
        val last = lastAsk ?: return say(prefix, ReplyKind.ASK)
        return last.copy(speech = "$prefix ${last.speech}", changedFieldIds = emptyList())
    }

    // ---------- submission ----------

    private suspend fun submit(): SessionReply {
        if (!form.gatePasses()) {
            val missing = form.missingRequired().map(FieldText::label)
            return advance(Turn(System.nanoTime()), prefix = phrases.missingBeforeSubmit(missing))
        }
        val previous = phase
        phase = SessionPhase.SUBMITTING
        val id = reportId ?: newReportId().also { reportId = it } // stable: retries never duplicate
        val json = ReportJson.build(schema, form, id, now(), status = "submitted", metrics = metrics())

        try {
            repository.submit(id, json) // local copy first: the source of truth
        } catch (e: CancellationException) {
            phase = previous
            throw e
        } catch (e: Exception) {
            phase = previous
            return say(phrases.saveFailed(), ReplyKind.ERROR)
        }

        val delivered = try {
            submitter.submit(json, id).ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        phase = SessionPhase.SUBMITTED
        pending = null
        val message = if (delivered) phrases.submitted(id) else phrases.savedNotDelivered(id)
        return say(message, ReplyKind.DONE).copy(reportId = id, delivered = delivered)
    }

    // ---------- helpers ----------

    private fun say(text: String, kind: ReplyKind): SessionReply {
        remember("assistant", text)
        return SessionReply(
            speech = text,
            kind = kind,
            phase = phase,
            pendingFieldIds = pending?.fields.orEmpty().map { it.id },
        )
    }

    private fun info(text: String, kind: ReplyKind) = SessionReply(text, kind, phase)

    private fun remember(role: String, text: String) {
        history.addLast(ConversationTurn(role, text))
        while (history.size > HISTORY_KEPT) history.removeFirst()
    }

    /** Whole answer equals an option's value, label or alias ("Medium.", "moderate"). */
    private fun exactOption(field: FormField, text: String): String? {
        val said = FastIntents.normalize(text)
        if (said.isEmpty()) return null
        return field.options.firstOrNull { option ->
            (listOf(option.value, option.label) + option.aliases).any { FastIntents.normalize(it) == said }
        }?.value
    }

    /** Per-turn stage timer; folds into the session totals when the turn ends. */
    private inner class Turn(val startNanos: Long) {
        var fastPath = false
        private var promptMs = 0L
        private var llmMs = 0L
        private var parseMs = 0L
        private var applyMs = 0L
        private var policyMs = 0L
        private var llmCalls = 0
        private var llmFailure: String? = null

        fun record(extraction: Extraction) {
            promptMs += extraction.timings.promptMs
            llmMs += extraction.timings.llmMs
            parseMs += extraction.timings.parseMs
            llmCalls += extraction.llmCalls
            llmFailure = extraction.failure
        }

        fun <T> timed(stage: String, block: () -> T): T {
            val start = System.nanoTime()
            try {
                return block()
            } finally {
                val ms = (System.nanoTime() - start) / 1_000_000
                if (stage == "apply") applyMs += ms else policyMs += ms
            }
        }

        fun finish(): TurnMetrics {
            val total = (System.nanoTime() - startNanos) / 1_000_000
            totals.merge("turns", 1, Long::plus)
            totals.merge("llm_calls", llmCalls.toLong(), Long::plus)
            totals.merge("llm_ms", llmMs, Long::plus)
            totals.merge("turn_total_ms", total, Long::plus)
            totals.merge("max_turn_ms", total) { a, b -> maxOf(a, b) }
            return TurnMetrics(promptMs, llmMs, parseMs, applyMs, policyMs, total, llmCalls, fastPath, llmFailure)
        }
    }

    private companion object {
        val FREE_TEXT_TYPES = setOf(FieldType.TEXT, FieldType.TEXTAREA, FieldType.STRING_LIST, FieldType.DATETIME)
        const val EXACT_ANSWER_CONFIDENCE = 0.95
        const val FALLBACK_CONFIDENCE = 0.8
        const val HISTORY_KEPT = 8
        const val HISTORY_IN_PROMPT = 4
        const val UNKNOWN_EVIDENCE = "user could not say"
        const val SKIPPED_EVIDENCE = "skipped"
    }
}
