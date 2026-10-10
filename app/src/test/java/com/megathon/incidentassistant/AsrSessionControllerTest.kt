package com.megathon.incidentassistant

import com.megathon.incidentassistant.ai.AsrSessionController
import com.megathon.incidentassistant.ai.NoSpeechException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Whisper session lifecycle, against a fake that behaves like the SDK's callbacks:
 * transcripts and `onFinished` arrive asynchronously after start, errors may come without
 * a finish, and `stop()` may itself trigger a late `onFinished`.
 */
class AsrSessionControllerTest {

    /** What the "SDK" does with session n after start(). */
    private sealed interface Behaviour {
        /** Speaks [text], then VAD closes the utterance. */
        data class Speech(val text: String, val afterMs: Long = 30) : Behaviour
        /** Nothing heard: NO_SPEECH_TIMEOUT error, no finish (the stall case). */
        data object Silence : Behaviour
        /** Engine failure mid-session (e.g. HTP execution error), no finish. */
        data class Error(val code: Int) : Behaviour
        /** Never reports anything. */
        data object Hang : Behaviour
    }

    private class FakeInput(val n: Int, val events: MutableList<String>) : AsrSessionController.Input {
        var released = false
        override fun goSilent() = Unit
        override fun release() {
            if (!released) events += "release$n"
            released = true
        }
    }

    private class FakeSdk(
        private val scope: CoroutineScope,
        private val script: List<Behaviour>,
        /** Real SDK sessions closed by stop() may report onFinished later. */
        private val stopFinishesAfterMs: Long? = null,
        private val failStartOn: Set<Int> = emptySet(),
    ) : AsrSessionController.Engine {
        lateinit var c: AsrSessionController
        val events = mutableListOf<String>()
        val inputs = mutableListOf<FakeInput>()
        private var n = 0
        /** Session the SDK currently runs (null = idle). Callbacks only come from it. */
        private var running: Int? = null
        private val jobs = mutableListOf<Job>()

        override fun openInput(): AsrSessionController.Input {
            n++
            events += "open$n"
            return FakeInput(n, events).also { inputs += it }
        }

        override suspend fun start(input: AsrSessionController.Input) {
            val s = (input as FakeInput).n
            if (s in failStartOn) {
                events += "startFailed$s"
                throw IllegalStateException("start rejected")
            }
            check(running == null) { "start($s) while session $running is still open — would be ignored by the SDK" }
            running = s
            events += "start$s"
            when (val b = script[s - 1]) {
                is Behaviour.Speech -> jobs += scope.launch {
                    delay(b.afterMs)
                    c.onTranscription(b.text, isFinal = true)
                    delay(5)
                    events += "finished$s"
                    running = null // the SDK stops itself after the VAD flush
                    c.onFinished()
                }
                Behaviour.Silence -> jobs += scope.launch {
                    delay(40)
                    c.onError(NO_SPEECH)
                }
                is Behaviour.Error -> jobs += scope.launch {
                    delay(20)
                    c.onError(b.code)
                }
                Behaviour.Hang -> Unit
            }
        }

        override fun stop() {
            val s = running
            events += "stop"
            running = null
            if (s != null && stopFinishesAfterMs != null) {
                scope.launch {
                    delay(stopFinishesAfterMs)
                    events += "finished$s(late)"
                    c.onFinished()
                }
            }
        }
    }

    private fun controller(sdk: FakeSdk, log: MutableList<String> = mutableListOf()) =
        AsrSessionController(sdk, NO_SPEECH, flushTimeoutMs = 300, forceStopTimeoutMs = 200, log = { log += it })
            .also { sdk.c = it }

    /** Mirrors the view model: wait for the utterance to end, then stop and read the text. */
    private suspend fun CoroutineScope.turn(c: AsrSessionController, ends: () -> Int): String {
        val before = ends()
        c.start()
        // Bounded: a missing end-of-utterance signal is the device stall, fail, don't hang.
        withTimeout(3_000) { while (ends() == before) delay(5) }
        return c.stop()
    }

    private fun CoroutineScope.countEnds(c: AsrSessionController): Pair<() -> Int, Job> {
        var ends = 0
        val job = launch { c.utteranceFinished.collect { ends++ } }
        return Pair({ ends }, job)
    }

    @Test
    fun consecutiveFollowUpsEachGetTheirOwnTranscriptAndStopPrecedesTheNextStart() = runBlocking {
        val answers = listOf("water leak near room two", "medium", "around ten", "no", "yes")
        val sdk = FakeSdk(this, answers.map { Behaviour.Speech(it) })
        val c = controller(sdk)
        val (ends, job) = countEnds(c)
        delay(10)
        val got = answers.map { turn(c, ends) }
        job.cancel()

        assertEquals(answers, got)
        assertEquals(5, ends())
        // Per session: start → finish → stop (closed by us) → mic released → next open/start.
        for (i in 1..5) {
            val e = sdk.events
            assertTrue(e.indexOf("start$i") < e.indexOf("finished$i"))
            assertTrue(e.indexOf("finished$i") < e.indexOf("release$i"))
            if (i < 5) assertTrue("session $i closed before ${i + 1} opened", e.indexOf("release$i") < e.indexOf("open${i + 1}"))
        }
        assertEquals("one stop per session", 5, sdk.events.count { it == "stop" })
        assertTrue(sdk.inputs.all { it.released })
    }

    @Test
    fun anErrorWithoutFinishEndsTheUtteranceInsteadOfListeningForever() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Error(-1), Behaviour.Speech("medium")))
        val log = mutableListOf<String>()
        val c = controller(sdk, log)
        val (ends, job) = countEnds(c)
        delay(10)

        assertEquals("", turn(c, ends))               // the caller was told, and gets nothing
        assertTrue(log.any { it.startsWith("ASR_ERROR session=1 code=-1") })
        assertEquals("medium", turn(c, ends))         // and the next session works
        job.cancel()
    }

    @Test
    fun noSpeechTimeoutSurfacesAsNoSpeechAndTheRetryWorks() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Silence, Behaviour.Speech("medium")))
        val c = controller(sdk)
        val (ends, job) = countEnds(c)
        delay(10)
        try {
            turn(c, ends)
            fail("expected NoSpeechException")
        } catch (_: NoSpeechException) {
        }
        assertEquals("medium", turn(c, ends))
        job.cancel()
    }

    @Test
    fun anUnclosedSessionIsFullyClosedBeforeTheNextStartSoItsLateFinishCannotEndIt() = runBlocking {
        // Session 1 is never stopped by the caller; the SDK reports its stop-induced
        // onFinished 60 ms after stop(). It must land on session 1, not on session 2.
        val sdk = FakeSdk(this, listOf(Behaviour.Hang, Behaviour.Speech("medium", afterMs = 150)), stopFinishesAfterMs = 60)
        val c = controller(sdk)
        val (ends, job) = countEnds(c)
        delay(10)
        c.start()
        c.start() // restart without stop

        val e = sdk.events
        assertTrue(e.indexOf("finished1(late)") in 0 until e.indexOf("start2"))
        delay(80)
        assertEquals("session 2 must still be listening", 1, ends()) // only session 1's end so far
        withTimeout(3_000) { while (ends() < 2) delay(5) }
        assertEquals("medium", c.stop())
        job.cancel()
    }

    @Test
    fun aFailedStartReleasesTheMicAndPropagatesAndTheNextStartWorks() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Hang, Behaviour.Speech("medium")), failStartOn = setOf(1))
        val c = controller(sdk)
        val (ends, job) = countEnds(c)
        delay(10)
        try {
            c.start()
            fail("start failure must reach the caller")
        } catch (_: IllegalStateException) {
        }
        assertTrue(sdk.inputs[0].released)
        try {
            c.stop()
            fail("no session must be open after a failed start")
        } catch (_: IllegalStateException) {
        }
        assertEquals("medium", turn(c, ends))
        job.cancel()
    }

    @Test
    fun aHungEngineIsForceStoppedWithinTheBudgetAndKeepsFinalsSoFar() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Hang, Behaviour.Speech("medium")))
        val log = mutableListOf<String>()
        val c = controller(sdk, log)
        delay(10)
        c.start()
        c.onTranscription("around ten", isFinal = true)
        val t0 = System.currentTimeMillis()
        assertEquals("around ten", c.stop())
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
        assertTrue(log.any { it.startsWith("TIMEOUT asr_flush session=1") })
        val (ends, job) = countEnds(c)
        delay(10)
        assertEquals("medium", turn(c, ends))
        job.cancel()
    }

    @Test
    fun callbacksBetweenSessionsAreIgnoredAndNeverLeakIntoTheNextTranscript() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Speech("medium"), Behaviour.Speech("around ten")))
        val log = mutableListOf<String>()
        val c = controller(sdk, log)
        val (ends, job) = countEnds(c)
        delay(10)
        assertEquals("medium", turn(c, ends))
        val endsBefore = ends()
        c.onTranscription("stale words", isFinal = true)
        c.onFinished()
        c.onError(-1)
        delay(20)
        assertEquals(endsBefore, ends())
        assertEquals(2, log.count { it.startsWith("STALE_CALLBACK") })
        assertEquals("around ten", turn(c, ends))
        job.cancel()
    }

    @Test
    fun aDuplicateFinishSignalsTheCallerOnce() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Hang))
        val c = controller(sdk)
        val (ends, job) = countEnds(c)
        delay(10)
        c.start()
        c.onFinished()
        c.onFinished()
        delay(20)
        assertEquals(1, ends())
        c.stop()
        job.cancel()
    }

    @Test
    fun abandonFreesTheMicWithoutWaiting() = runBlocking {
        val sdk = FakeSdk(this, listOf(Behaviour.Hang))
        val c = controller(sdk)
        c.start()
        c.abandon()
        assertTrue(sdk.inputs[0].released)
        assertFalse(sdk.events.isEmpty())
    }

    private companion object {
        const val NO_SPEECH = -4
    }
}
