package com.megathon.incidentassistant

import com.megathon.incidentassistant.session.ReplyKind
import com.megathon.incidentassistant.session.SessionPhase
import com.megathon.incidentassistant.session.SessionReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden scenario (docs/DESIGN.md §4.4) end to end on the host: ASR seam → session →
 * scripted LLM → validated tool calls → form → policy → TTS seam → … → explicit "yes" →
 * report saved + POSTed. Drives the same calls in the same order as IncidentViewModel
 * (startListening → stopListening → onUtterance → speak); only the Android UI is absent.
 */
class VoiceLoopEndToEndTest {

    @Test
    fun waterLeakReportIsCompletedConfirmedAndFiled() = runBlocking {
        val asr = ScriptedAsr(listOf(WATER_LEAK, "Medium.", "No.", "No, nothing.", "Yes."))
        val tts = RecordingTts()
        val llm = ScriptedLlm.of(WATER_LEAK_EXTRACTION)
        val f = SessionFixture(llm)

        suspend fun userSpeaks(): SessionReply {
            asr.startListening()
            val transcript = asr.stopListening()
            val reply = f.session.onUtterance(transcript)
            assertFalse("never record while the assistant talks", asr.listening)
            tts.speak(reply.speech)
            return reply
        }

        // 1. The incident description: one model call fills five fields.
        val r1 = userSpeaks()
        assertEquals(1, llm.calls)
        assertEquals(
            listOf("incident_type", "location", "occurred_at", "description", "immediate_actions"),
            r1.changedFieldIds,
        )
        assertEquals(listOf("severity"), r1.pendingFieldIds)
        assertEquals("How severe is it — low, medium, high, or critical?", tts.spoken.last())

        // 2. "Medium." answers the pending question (exact option: no model call).
        val r2 = userSpeaks()
        assertEquals(listOf("severity"), r2.changedFieldIds)
        assertEquals("medium", f.session.form.text("severity"))
        assertTrue(f.session.form.gatePasses())
        assertEquals(SessionPhase.OPTIONAL, r2.phase)

        // 3 + 4. Optional groups, asked once each, declined.
        assertEquals(SessionPhase.OPTIONAL, userSpeaks().phase)
        val r4 = userSpeaks()
        assertEquals(SessionPhase.CONFIRMING, r4.phase)
        assertEquals(ReplyKind.CONFIRM, r4.kind)
        assertEquals(
            "SUMMARY Incident Type: Water leak. Severity: Medium. Location: Conference Room 2. " +
                "Date & Time: 2026-10-10T10:00:00. Description: Water leak near Conference Room 2. " +
                "Immediate Actions Taken: Area isolated by maintenance. File it?",
            tts.spoken.last(),
        )
        assertTrue("nothing filed before the yes", f.repository.saved.isEmpty())

        // 5. Explicit confirmation → filed.
        val r5 = userSpeaks()
        assertEquals(SessionPhase.SUBMITTED, r5.phase)
        assertEquals("RPT-TEST1", r5.reportId)
        assertEquals("Filed RPT-TEST1.", tts.spoken.last())
        assertEquals(5, tts.spoken.size) // one spoken reply per turn, no repeats
        assertEquals(1, llm.calls)

        val (savedId, json) = f.repository.saved.single()
        assertEquals("RPT-TEST1", savedId)
        assertEquals(listOf("RPT-TEST1"), f.submitter.sent) // same id = idempotency key
        val root = Json.parseToJsonElement(json).jsonObject
        val fields = root["fields"]!!.jsonObject
        fun value(id: String) = fields[id]!!.jsonObject["value"]!!.jsonPrimitive.content
        fun status(id: String) = fields[id]!!.jsonObject["status"]!!.jsonPrimitive.content
        assertEquals("water_leak", value("incident_type"))
        assertEquals("medium", value("severity"))
        assertEquals("Conference Room 2", value("location"))
        assertEquals("2026-10-10T10:00:00", value("occurred_at"))
        assertEquals("Area isolated by maintenance", value("immediate_actions"))
        assertEquals("filled", status("severity"))
        assertEquals("unknown", status("witnesses")) // asked once, skipped
        assertTrue(root["missing_information"]!!.jsonArray.isEmpty())
        assertEquals("submitted", root["status"]!!.jsonPrimitive.content)
    }
}
