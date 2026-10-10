package com.megathon.incidentassistant.form

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses the raw output of any LLM into [FormToolCall]s (docs/DESIGN.md §4.2).
 *
 * Accepted shapes, in priority order:
 *  1. OpenAI `tool_calls`: {"choices":[{"message":{"tool_calls":[{"function":{...}}]}}]}
 *  2. `<tool_call>{"tool":"...","arguments":{...}}</tool_call>` (Qwen / GenieX style)
 *  3. fenced ```json blocks or bare JSON objects anywhere in the text
 *
 * Multiple objects are all parsed. Malformed pieces become warnings — never crashes —
 * so the app can retry or fall back to storing the raw utterance.
 */
object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class Result(
        val calls: List<FormToolCall>,
        val warnings: List<String> = emptyList(),
    )

    fun parse(raw: String): Result {
        val warnings = mutableListOf<String>()
        if (raw.isBlank()) return Result(emptyList(), listOf("empty model output"))

        parseOpenAiToolCalls(raw, warnings)?.let { calls ->
            if (calls.isNotEmpty()) return Result(calls, warnings)
        }

        val calls = mutableListOf<FormToolCall>()
        for (candidate in extractJsonObjects(stripFences(raw))) {
            val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject
            if (element == null) {
                warnings += "not a JSON object: ${candidate.take(80)}"
                continue
            }
            parseToolObject(element, warnings)?.let { calls += it }
        }
        if (calls.isEmpty() && warnings.isEmpty()) warnings += "no tool call found in model output"
        return Result(calls, warnings)
    }

    // ---------- shape 1: OpenAI tool_calls ----------

    private fun parseOpenAiToolCalls(raw: String, warnings: MutableList<String>): List<FormToolCall>? {
        if (!raw.contains("\"tool_calls\"")) return null
        val root = runCatching { json.parseToJsonElement(raw.trim()) as? JsonObject }.getOrNull() ?: return null
        val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject ?: root
        val toolCalls = message["tool_calls"] as? JsonArray ?: return null
        val out = mutableListOf<FormToolCall>()
        for (element in toolCalls) {
            val function = (element as? JsonObject)?.get("function") as? JsonObject ?: continue
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull ?: continue
            val arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
            val argsObject = runCatching { json.parseToJsonElement(arguments) }.getOrNull() as? JsonObject
                ?: JsonObject(emptyMap())
            toolCall(name, argsObject, warnings)?.let { out += it }
        }
        return out
    }

    // ---------- shapes 2 + 3: JSON in text ----------

    private fun parseToolObject(obj: JsonObject, warnings: MutableList<String>): FormToolCall? {
        val name = (obj["tool"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        val arguments = obj["arguments"] as? JsonObject
            ?: obj["parameters"] as? JsonObject
            ?: JsonObject(emptyMap())
        return toolCall(name, arguments, warnings)
    }

    private fun toolCall(name: String, arguments: JsonObject, warnings: MutableList<String>): FormToolCall? =
        when (name) {
            "record_fields", "update_fields", "update_incident_form" ->
                FormToolCall.RecordFields(parseUpdates(arguments, warnings))

            "ask_user" -> FormToolCall.AskUser(
                fieldId = (arguments["field"] as? JsonPrimitive)?.contentOrNull,
                question = (arguments["question"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )

            "submit_report" -> FormToolCall.SubmitReport(
                (arguments["summary"] as? JsonPrimitive)?.contentOrNull,
            )

            else -> {
                warnings += "unknown tool \"$name\""
                null
            }
        }

    private fun parseUpdates(arguments: JsonObject, warnings: MutableList<String>): List<FieldUpdate> {
        val array = arguments["updates"] as? JsonArray ?: return emptyList()
        val updates = mutableListOf<FieldUpdate>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val fieldId = (obj["field"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["field_id"] as? JsonPrimitive)?.contentOrNull
            val value = (obj["value"] as? JsonPrimitive)?.contentOrNull
            if (fieldId.isNullOrBlank() || value == null) {
                warnings += "update without field/value: ${obj.toString().take(80)}"
                continue
            }
            val confidence = (obj["confidence"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.5
            val evidence = (obj["evidence"] as? JsonPrimitive)?.contentOrNull
            val status = when ((obj["status"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "answered" -> UpdateStatus.ANSWERED
                "unknown" -> UpdateStatus.UNKNOWN
                else -> UpdateStatus.INFERRED
            }
            updates += FieldUpdate(fieldId, value, confidence.coerceIn(0.0, 1.0), evidence, status)
        }
        return updates
    }

    // ---------- helpers ----------

    /** Removes markdown fences so the JSON scanner sees clean text. */
    private fun stripFences(raw: String): String =
        raw.replace("```json", "\n").replace("```", "\n")

    /** Bracket-balanced JSON object scanner (string- and escape-aware). */
    private fun extractJsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '{') {
                index += 1
                continue
            }
            val start = index
            var depth = 0
            var inString = false
            var escaped = false
            var cursor = index
            var end = -1
            while (cursor < text.length) {
                val ch = text[cursor]
                when {
                    inString -> when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }

                    ch == '"' -> inString = true
                    ch == '{' -> depth += 1
                    ch == '}' -> {
                        depth -= 1
                        if (depth == 0) {
                            end = cursor
                            break
                        }
                    }
                }
                cursor += 1
            }
            if (end == -1) {
                index = start + 1
                continue
            }
            objects += repair(text.substring(start, end + 1))
            index = end + 1
        }
        return objects
    }

    /** Minimal repairs for common small-model JSON slips (trailing commas). */
    private fun repair(candidate: String): String =
        candidate.replace(Regex(",\\s*([}\\]])"), "$1")
}
