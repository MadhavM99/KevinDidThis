package com.megathon.incidentassistant.session

import com.megathon.incidentassistant.ai.LlmBackend
import com.megathon.incidentassistant.form.ConversationTurn
import com.megathon.incidentassistant.form.ExtractionPromptBuilder
import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.form.PendingQuestion
import com.megathon.incidentassistant.form.ToolCallParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Wall-clock milliseconds spent in each stage of one extraction. */
data class ExtractionTimings(
    val promptMs: Long = 0,
    val llmMs: Long = 0,
    val parseMs: Long = 0,
)

/**
 * Outcome of one utterance → model → tool calls exchange. [failure] is set when the model
 * could not be used at all (runtime error, timeout, unparsable output after the repair
 * retry); the caller then falls back without losing the utterance.
 */
data class Extraction(
    val calls: List<FormToolCall>,
    val warnings: List<String>,
    val failure: String?,
    val llmCalls: Int,
    val timings: ExtractionTimings,
)

/**
 * Prompt construction + LLM call + parsing (docs/DESIGN.md §2.1 step 5). Model-agnostic:
 * any [LlmBackend] works. One bounded repair attempt when the output has no tool call.
 * Nothing here touches the form — [ToolExecutor] validates and applies the calls.
 */
class ExtractionEngine(
    private val llm: LlmBackend,
    private val now: () -> String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxRepairs: Int = 1,
) {

    suspend fun extract(
        schema: FormSchema,
        state: FormState,
        pending: PendingQuestion?,
        utterance: String,
        history: List<ConversationTurn>,
    ): Extraction {
        val promptStart = System.nanoTime()
        val prompt = buildString {
            append(ExtractionPromptBuilder.systemPrompt(schema, state, now()))
            appendLine()
            appendLine()
            append(ExtractionPromptBuilder.userPrompt(pending, utterance, history))
        }
        val promptMs = elapsedMs(promptStart)

        var llmMs = 0L
        var parseMs = 0L
        var llmCalls = 0
        val warnings = mutableListOf<String>()
        var request = prompt

        while (true) {
            val llmStart = System.nanoTime()
            val raw = try {
                llmCalls += 1
                withTimeout(timeoutMs) { llm.generate(request) }
            } catch (e: TimeoutCancellationException) {
                return failed("model timed out after $timeoutMs ms", warnings, llmCalls, promptMs, llmMs + elapsedMs(llmStart), parseMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the exception type: messages can echo prompt text (incident details).
                return failed("model error: ${e.javaClass.simpleName}", warnings, llmCalls, promptMs, llmMs + elapsedMs(llmStart), parseMs)
            }
            llmMs += elapsedMs(llmStart)

            val parseStart = System.nanoTime()
            val parsed = ToolCallParser.parse(raw)
            parseMs += elapsedMs(parseStart)
            warnings += parsed.warnings

            if (parsed.calls.isNotEmpty()) {
                return Extraction(parsed.calls, warnings, null, llmCalls, ExtractionTimings(promptMs, llmMs, parseMs))
            }
            if (llmCalls > maxRepairs) {
                return failed("no tool call in model output", warnings, llmCalls, promptMs, llmMs, parseMs)
            }
            request = prompt + REPAIR_SUFFIX
        }
    }

    private fun failed(
        reason: String,
        warnings: List<String>,
        llmCalls: Int,
        promptMs: Long,
        llmMs: Long,
        parseMs: Long,
    ) = Extraction(emptyList(), warnings, reason, llmCalls, ExtractionTimings(promptMs, llmMs, parseMs))

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        /** Generous: covers a first call that still waits for the model to finish loading. */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        private const val REPAIR_SUFFIX =
            "\n\nYour previous reply could not be read. Reply again with ONLY the " +
                "<tool_call>{...}</tool_call> JSON object, no other text."
    }
}
