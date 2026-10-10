package com.megathon.incidentassistant

import com.megathon.incidentassistant.form.FieldStatus
import com.megathon.incidentassistant.session.FastIntents
import com.megathon.incidentassistant.session.SessionPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ten acceptance utterances against the real incident form, with the model replaced
 * by the output a correct extraction would produce (sometimes with the small slips real
 * models make: raw aliases instead of option ids, unknown field ids). These pin down what
 * the APP does with model output — normalization, validation, no invention, corrections.
 * They say nothing about Gemma's actual accuracy; that is measured on the device
 * (docs/LLM.md, on-device acceptance).
 */
class ExtractionScenariosTest {

    private fun fixture(vararg outputs: String) = SessionFixture(ScriptedLlm.of(*outputs))

    @Test
    fun s1_goldenUtteranceFillsFiveFieldsAndInventsNothing() = runBlocking {
        val f = fixture(WATER_LEAK_EXTRACTION)
        f.session.onUtterance(WATER_LEAK)
        val filled = f.session.form.entries().filterValues { it.status == FieldStatus.FILLED }.keys
        assertEquals(setOf("incident_type", "location", "occurred_at", "description", "immediate_actions"), filled)
    }

    @Test
    fun s2_waterThroughTheCeilingIsAnInferredLeak() = runBlocking {
        val f = fixture(
            recordFields(
                U("incident_type", "water_leak", 0.8, "water is coming through the ceiling"),
                U("description", "Water is coming through the ceiling.", 0.9, "water is coming through the ceiling"),
            ),
        )
        f.session.onUtterance("Water is coming through the ceiling.")
        assertEquals("water_leak", f.session.form.text("incident_type"))
        assertEquals(FieldStatus.EMPTY, f.session.form.get("location").status)
    }

    @Test
    fun s3_pipeBurstAliasIsNormalizedAndLocationKept() = runBlocking {
        // The model returned the spoken alias, not the option id: the normalizer maps it.
        val f = fixture(
            recordFields(
                U("incident_type", "pipe burst", 0.9, "a pipe burst"),
                U("location", "Near the electrical room", 0.9, "near the electrical room"),
            ),
        )
        f.session.onUtterance("A pipe burst near the electrical room.")
        assertEquals("water_leak", f.session.form.text("incident_type"))
        assertEquals("Near the electrical room", f.session.form.text("location"))
    }

    @Test
    fun s4_machineStoppedThenFixedMapsTypeAndStatus() = runBlocking {
        val f = fixture(
            recordFields(
                U("incident_type", "equipment_failure", 0.8, "the machine stopped"),
                U("action_status", "fixed", 0.8, "we got it working again"),
            ),
        )
        f.session.onUtterance("The machine stopped, but we got it working again.")
        assertEquals("equipment_failure", f.session.form.text("incident_type"))
        assertEquals("resolved", f.session.form.text("action_status"))
    }

    @Test
    fun s5_notSureHowSeriousTriggersAConfirmationNotAValue() = runBlocking {
        val f = fixture(WATER_LEAK_EXTRACTION, recordFields(U("severity", "", 0.9, "not sure how serious", "unknown")))
        f.session.onUtterance(WATER_LEAK)
        val reply = f.session.onUtterance("I'm not sure how serious it is.")
        assertEquals(SessionPhase.CONFIRM_UNKNOWN, reply.phase)
        assertEquals(FieldStatus.EMPTY, f.session.form.get("severity").status)
    }

    @Test
    fun s6_timeCorrectionReplacesTheEarlierValue() = runBlocking {
        val f = fixture(
            WATER_LEAK_EXTRACTION,
            recordFields(U("occurred_at", "2026-10-10T11:00:00", 0.9, "the time was 11 AM", "answered")),
        )
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Actually, the time was 11 AM.")
        assertEquals("2026-10-10T11:00:00", f.session.form.text("occurred_at"))
    }

    @Test
    fun s7_noOneWasInjuredIsContentNotANo() = runBlocking {
        assertNull(FastIntents.classify("No one was injured."))
        val llm = ScriptedLlm.of(recordFields(U("description", "No one was injured.", 0.9, "No one was injured")))
        val f = SessionFixture(llm)
        f.session.onUtterance("No one was injured.")
        assertEquals(1, llm.calls)
        assertEquals("No one was injured.", f.session.form.text("description"))
    }

    @Test
    fun s8_skipTheWitnessDetailsOnlySkipsWitnesses() = runBlocking {
        val f = fixture(
            WATER_LEAK_EXTRACTION,
            recordFields(U("witnesses", "", 0.9, "skip the witness details", "unknown")),
        )
        f.session.onUtterance(WATER_LEAK)
        f.session.onUtterance("Medium")
        f.session.onUtterance("Skip the witness details.")
        assertEquals("skip the witness details", f.session.form.get("witnesses").evidence)
        assertEquals(FieldStatus.UNKNOWN, f.session.form.get("witnesses").status)
    }

    @Test
    fun s9_oneUtteranceFillsSeveralFields() = runBlocking {
        val f = fixture(
            recordFields(
                U("incident_type", "fire"), U("severity", "high"), U("location", "Lab 3"),
                U("persons_involved", "Ann and Raj", 0.9, "Ann and Raj were there"),
            ),
        )
        val reply = f.session.onUtterance("A serious fire in Lab 3, Ann and Raj were there")
        assertEquals(4, reply.changedFieldIds.size)
        assertEquals("Ann; Raj", f.session.form.text("persons_involved"))
        assertEquals("TELL ME: when it happened | what happened | what has been done so far to handle it?", reply.speech)
    }

    @Test
    fun s10_factsTheFormCannotHoldAreDroppedNotForcedIntoFields() = runBlocking {
        val f = fixture(
            recordFields(
                U("insurance_number", "4471", 0.9, "insurance number is 4471"),
                U("cctv_status", "down", 0.9, "CCTV was down"),
                U("incident_type", "security", 0.7, "CCTV camera was down"),
            ),
        )
        val reply = f.session.onUtterance("The CCTV camera was down and the insurance number is 4471.")
        assertEquals(listOf("incident_type"), reply.changedFieldIds)
        assertTrue(f.session.form.entries().keys == setOf("incident_type"))
    }
}
