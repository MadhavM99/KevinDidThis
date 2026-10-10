package com.megathon.incidentassistant

import com.megathon.incidentassistant.session.FastIntent
import com.megathon.incidentassistant.session.FastIntents
import org.junit.Assert.assertEquals
import org.junit.Test

class FastIntentsTest {

    @Test
    fun wholeUtteranceControlPhrasesAreRecognised() {
        val expected = mapOf(
            "Yes." to FastIntent.YES,
            "Yeah, file it" to null, // two phrases glued together: let the model see it
            "yes please" to FastIntent.YES,
            "Okay." to FastIntent.YES,
            "No" to FastIntent.NO,
            "Not yet!" to FastIntent.NO,
            "I don't know." to FastIntent.UNKNOWN,
            "Um, I’m not sure" to FastIntent.UNKNOWN,
            "Skip that" to FastIntent.SKIP,
            "Nothing to add, thanks" to FastIntent.NONE,
            "Cancel" to FastIntent.CANCEL,
            "Can you repeat that?" to FastIntent.REPEAT,
            "Start over" to FastIntent.START_OVER,
        )
        expected.forEach { (said, intent) -> assertEquals(said, intent, FastIntents.classify(said)) }
    }

    @Test
    fun contentSentencesAreLeftToTheModel() {
        listOf(
            "No one was injured.",
            "I'm not sure how serious it is.",
            "Skip the witness details.",
            "Yes there was a fire in the lab",
            "Medium.",
            "",
        ).forEach { assertEquals(it, null, FastIntents.classify(it)) }
    }
}
