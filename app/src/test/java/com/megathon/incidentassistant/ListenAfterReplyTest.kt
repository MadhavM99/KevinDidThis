package com.megathon.incidentassistant

import com.megathon.incidentassistant.session.ListenAfterReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When may the mic reopen after a spoken reply? Lifecycle ordering, not just calls. */
class ListenAfterReplyTest {

    /** Same shape as the TTS backends' `utteranceFinished`. */
    private val ttsFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val events = mutableListOf<String>()

    private fun coordinator(scope: CoroutineScope, watchdogMs: Long = 5_000) = ListenAfterReply(
        scope = scope,
        ttsFinished = ttsFinished,
        settleMs = 20,
        watchdogMs = { watchdogMs },
        log = { events += it },
    )

    @Test
    fun micReopensOnlyAfterTheReplyFinishedPlaying() = runBlocking {
        var ready = 0
        val c = coordinator(this)
        c.afterReply("How severe is it?", listenAfter = true, speak = { true }, onReady = { ready++ })

        delay(200)
        assertEquals("still speaking: mic must stay closed", 0, ready)
        ttsFinished.emit(Unit)
        delay(10)
        assertEquals("settle not over yet", 0, ready)
        delay(60)
        assertEquals(1, ready)
        assertEquals(listOf("TTS_REQUESTED", "TTS_PLAYBACK_COMPLETE", "MICROPHONE_REOPEN"), events)
    }

    @Test
    fun aMissingFinishedCallbackCannotStrandTheConversation() = runBlocking {
        var ready = 0
        val c = coordinator(this, watchdogMs = 100)
        // e.g. engine init failed: speak() "succeeds" but nothing ever plays or calls back.
        c.afterReply("Where did it happen?", listenAfter = true, speak = { true }, onReady = { ready++ })

        delay(60)
        assertEquals(0, ready)
        delay(150)
        assertEquals(1, ready)
        assertTrue(events.any { it.startsWith("TIMEOUT tts_finished_missing") })
    }

    @Test
    fun anEngineThatFinishesInstantlyIsNotMissed() = runBlocking {
        var ready = 0
        val c = coordinator(this, watchdogMs = 5_000)
        // onError fired synchronously inside speak(): the signal precedes any wait.
        c.afterReply("Where?", listenAfter = true, speak = { ttsFinished.tryEmit(Unit); true }, onReady = { ready++ })
        delay(100)
        assertEquals(1, ready) // via the signal, long before the 5 s watchdog
        assertTrue("TTS_PLAYBACK_COMPLETE" in events)
    }

    @Test
    fun notSpokenMeansTapToAnswerNotAnAutomaticReopen() = runBlocking {
        var ready = 0
        val c = coordinator(this, watchdogMs = 50)
        c.afterReply("Where?", listenAfter = true, speak = { false }, onReady = { ready++ }) // muted / TalkBack
        delay(150)
        assertEquals(0, ready)
        assertEquals(listOf("TTS_SKIPPED listen=tap"), events)
    }

    @Test
    fun repliesThatExpectNoAnswerNeverReopenTheMic() = runBlocking {
        var ready = 0
        var spoken = 0
        val c = coordinator(this, watchdogMs = 50)
        c.afterReply("Report filed.", listenAfter = false, speak = { spoken++; true }, onReady = { ready++ })
        ttsFinished.emit(Unit)
        delay(150)
        assertEquals(1, spoken)
        assertEquals(0, ready)
    }

    @Test
    fun aNewerReplyOrCancelDropsThePendingReopenSoNoDoubleRecording() = runBlocking {
        val ready = mutableListOf<String>()
        val c = coordinator(this, watchdogMs = 100)
        c.afterReply("first", listenAfter = true, speak = { true }, onReady = { ready += "first" })
        c.afterReply("second", listenAfter = true, speak = { true }, onReady = { ready += "second" })
        delay(250)
        assertEquals(listOf("second"), ready)

        c.afterReply("third", listenAfter = true, speak = { true }, onReady = { ready += "third" })
        c.cancel() // e.g. the user tapped the mic themselves
        delay(250)
        assertEquals(listOf("second"), ready)
    }

    @Test
    fun watchdogGrowsWithTheLengthOfTheQuestion() {
        val short = ListenAfterReply.speechWatchdogMs("Where?")
        val long = ListenAfterReply.speechWatchdogMs("x".repeat(150))
        assertTrue(short >= 4_000)
        assertTrue(long > short)
    }
}
