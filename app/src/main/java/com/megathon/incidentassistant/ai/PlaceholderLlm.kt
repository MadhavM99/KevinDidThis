package com.megathon.incidentassistant.ai

import kotlinx.coroutines.delay

/**
 * PLACEHOLDER LLM — no model is loaded. It emulates what a real extraction model would
 * return so the whole pipeline (form fill → mandatory-field loop → report) can be built
 * and demoed before the on-device LLM is wired in.
 *
 *  - If the prompt says the assistant just asked about a field, the user's answer is
 *    echoed as an "answered" update for that field, so the follow-up loop works.
 *  - Otherwise it returns the problem statement's example (water leak, Conference
 *    Room 2, ~10 AM, area isolated) — with severity deliberately missing.
 *
 * The output is raw `<tool_call>` text, exactly like a real model would emit, so the
 * parser/normalizer/policy code paths are exercised for real.
 */
class PlaceholderLlm : LlmBackend {

    override val id: String = "placeholder-echo"

    override suspend fun generate(prompt: String): String {
        delay(500)
        val pendingField = PENDING_FIELD.find(prompt)?.groupValues?.get(1)
        val utterance = UTTERANCE.find(prompt)?.groupValues?.get(1).orEmpty()

        val updates = if (pendingField != null) {
            """[{"field":"$pendingField","value":"${escape(utterance)}","confidence":0.9,"evidence":"answer to follow-up question","status":"answered"}]"""
        } else {
            """[
              {"field":"incident_type","value":"water_leak","confidence":0.95,"evidence":"there was a water leak","status":"inferred"},
              {"field":"location","value":"Conference Room 2","confidence":0.9,"evidence":"near conference room 2","status":"inferred"},
              {"field":"occurred_at","value":"Today, 10:00 AM","confidence":0.7,"evidence":"around 10am","status":"inferred"},
              {"field":"description","value":"${escape(utterance)}","confidence":0.8,"evidence":"user report","status":"inferred"},
              {"field":"immediate_actions","value":"Area isolated by maintenance","confidence":0.9,"evidence":"maintenance has already isolated the area","status":"inferred"}
            ]"""
        }

        return "<tool_call>{\"tool\":\"record_fields\",\"arguments\":{\"updates\":$updates}}</tool_call>"
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        val PENDING_FIELD = Regex("expects an answer for field id=([a-z_]+)")
        val UTTERANCE = Regex("USER SAID: \"([^\"]*)\"")
    }
}
