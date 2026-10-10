package com.megathon.incidentassistant.session

/**
 * Short control phrases recognised without an LLM call (docs/DESIGN.md §5.3).
 *
 * Only a WHOLE utterance matches ("no", "skip that"), never a phrase inside a sentence —
 * "No one was injured" is content for the extraction model, not a "no". What each
 * intent means depends on what the assistant just asked; [IncidentSession] decides.
 */
enum class FastIntent {
    YES,
    NO,
    /** "I don't know" / "not sure": the user cannot answer the pending question. */
    UNKNOWN,
    /** "skip that": the user does not want to answer the pending question. */
    SKIP,
    /** "nothing" / "none": closes an optional question, but is a real answer elsewhere. */
    NONE,
    CANCEL,
    REPEAT,
    START_OVER,
    /** Obvious chit-chat ("hello", "tell me a joke"): redirected without a model call. */
    SMALL_TALK,
}

object FastIntents {

    private val PHRASES: Map<FastIntent, Set<String>> = mapOf(
        FastIntent.YES to setOf(
            "yes", "yeah", "yep", "yup", "yes please", "sure", "correct", "that's correct",
            "that is correct", "that's right", "that is right", "right", "ok", "okay",
            "confirm", "confirmed", "file it", "yes file it", "submit", "submit it",
            "go ahead", "do it", "sounds good", "affirmative",
        ),
        FastIntent.NO to setOf(
            "no", "nope", "nah", "no thanks", "not yet", "wait", "hold on", "don't", "do not",
            "that's wrong", "that is wrong", "wrong", "incorrect", "negative",
        ),
        FastIntent.UNKNOWN to setOf(
            "i don't know", "i do not know", "don't know", "dunno", "no idea", "i have no idea",
            "not sure", "i'm not sure", "i am not sure", "unknown", "no clue",
            "i can't remember", "i cannot remember", "can't remember", "i don't remember",
        ),
        FastIntent.SKIP to setOf(
            "skip", "skip it", "skip that", "skip this", "skip this one", "skip the question",
            "pass", "next", "next question", "rather not say", "i'd rather not say",
        ),
        FastIntent.NONE to setOf(
            "none", "nothing", "nothing to add", "nothing else", "no one", "nobody",
            "not applicable", "n a", "na", "that's all", "that's it", "no nothing",
        ),
        FastIntent.CANCEL to setOf(
            "cancel", "stop", "abort", "cancel it", "cancel report", "cancel the report",
            "discard", "discard it", "discard the report", "never mind", "nevermind",
        ),
        FastIntent.REPEAT to setOf(
            "repeat", "repeat that", "repeat the question", "say again", "say that again",
            "come again", "pardon", "what", "sorry what", "can you repeat that",
            "could you repeat that",
        ),
        FastIntent.START_OVER to setOf(
            "start over", "start again", "new report", "restart", "reset", "begin again",
        ),
        // Only unmistakable whole utterances; anything else goes to the model, which can
        // also answer off_topic. "thanks" alone is not here: it may close a question.
        FastIntent.SMALL_TALK to setOf(
            "hello", "hi", "hey", "hello there", "hi there", "hey there", "good morning",
            "good afternoon", "good evening", "how are you", "hi how are you",
            "hello how are you", "how are you doing", "what's up", "tell me a joke",
            "tell me something funny", "what's the weather", "what's the weather today",
            "what is the weather", "what is the weather today", "how's the weather",
            "what time is it", "who are you", "what's your name", "what is your name",
        ),
    )

    private val LEADING_FILLERS = setOf("uh", "um", "umm", "er", "so", "well", "oh", "hmm")
    private val TRAILING_FILLERS = listOf("please", "thanks", "thank you")

    fun classify(utterance: String): FastIntent? {
        val text = normalize(utterance)
        if (text.isEmpty()) return null
        return PHRASES.entries.firstOrNull { (_, phrases) -> text in phrases }?.key
    }

    /** Lower-case, straight apostrophes, no punctuation, no filler words around the phrase. */
    internal fun normalize(utterance: String): String {
        var words = utterance.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first() in LEADING_FILLERS) words = words.drop(1)
        var text = words.joinToString(" ")
        for (filler in TRAILING_FILLERS) {
            if (text.endsWith(" $filler")) text = text.removeSuffix(" $filler")
        }
        return text
    }
}
