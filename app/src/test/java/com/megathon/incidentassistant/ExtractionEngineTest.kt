package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.LlmException
import com.megathon.incidentassistant.form.FormState
import com.megathon.incidentassistant.form.FormToolCall
import com.megathon.incidentassistant.session.ExtractionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LLM backend contract as the pipeline sees it: any [com.megathon.incidentassistant.ai.LlmBackend]
 * (Gemma on device, a fake here) — success, malformed/empty/truncated output, runtime
 * failure, timeout and cancellation.
 */
class ExtractionEngineTest {

    private val schema = incidentSchema()

    private suspend fun extract(llm: ScriptedLlm, timeoutMs: Long = 5_000) =
        ExtractionEngine(llm, now = { NOW }, timeoutMs = timeoutMs)
            .extract(schema, FormState(schema), pending = null, utterance = WATER_LEAK, history = emptyList())

    @Test
    fun structuredResponseIsParsed() = runBlocking {
        val result = extract(ScriptedLlm.of(WATER_LEAK_EXTRACTION))
        assertNull(result.failure)
        assertEquals(1, result.llmCalls)
        val updates = (result.calls.single() as FormToolCall.RecordFields).updates
        assertEquals(5, updates.size)
    }

    @Test
    fun promptIsBuiltFromTheSchemaAndTheUtterance() = runBlocking {
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION)
        extract(llm)
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("id=severity"))
        assertTrue(prompt.contains("water_leak (Water leak, leak, flood, water damage, pipe burst)"))
        assertTrue(prompt.contains("CURRENT TIME: $NOW"))
        assertTrue(prompt.contains("USER SAID: \"$WATER_LEAK\""))
    }

    @Test
    fun proseAroundTheJsonIsTolerated() = runBlocking {
        val result = extract(ScriptedLlm.of("Here you go:\n```json\n${WATER_LEAK_EXTRACTION.removePrefix("<tool_call>").removeSuffix("</tool_call>")}\n```"))
        assertNull(result.failure)
        assertEquals(1, result.calls.size)
    }

    @Test
    fun emptyResponseGetsOneRepairThenFails() = runBlocking {
        val llm = ScriptedLlm.of("", "")
        val result = extract(llm)
        assertEquals("no tool call in model output", result.failure)
        assertEquals(2, result.llmCalls)
        assertTrue(llm.prompts[1].endsWith("no other text."))
    }

    @Test
    fun truncatedOutputAtTheTokenLimitIsNotExecuted() = runBlocking {
        // What a max-tokens cut-off looks like: an unterminated JSON object.
        val cut = WATER_LEAK_EXTRACTION.take(WATER_LEAK_EXTRACTION.length / 2)
        val result = extract(ScriptedLlm.of(cut, cut))
        assertNotNull(result.failure)
        assertTrue(result.calls.isEmpty())
    }

    @Test
    fun runtimeFailureIsReportedWithoutItsMessage() = runBlocking {
        val result = extract(ScriptedLlm { _, _ -> throw LlmException("prompt said: secret incident text") })
        assertEquals("model error: LlmException", result.failure)
        assertFalse(result.failure!!.contains("secret"))
        assertEquals(1, result.llmCalls) // a runtime error is not retried with the same prompt
    }

    @Test
    fun initializationFailureSurfacesAsAFailedTurn() = runBlocking {
        val result = extract(ScriptedLlm { _, _ -> throw LlmException("Gemma not loaded: FileNotFoundException") })
        assertNotNull(result.failure)
    }

    @Test
    fun slowModelTimesOut() = runBlocking {
        val result = extract(ScriptedLlm { _, _ -> delay(10_000); WATER_LEAK_EXTRACTION }, timeoutMs = 100)
        assertEquals("model timed out after 100 ms", result.failure)
    }

    @Test
    fun cancellingTheTurnCancelsTheModelCallAndIsNotSwallowed() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        val llm = ScriptedLlm { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(true)
                throw e
            }
        }
        var finished = false
        val job = launch {
            extract(llm)
            finished = true
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(cancelled.await())
        assertFalse(finished)
    }
}
