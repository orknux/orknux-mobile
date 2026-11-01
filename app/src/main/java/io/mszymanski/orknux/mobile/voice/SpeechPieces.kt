package io.mszymanski.orknux.mobile.voice

/** Where a workspace has said an answer may be cut before it goes to the speech model. */
enum class SpeechChunking {
    NONE, SENTENCE, PARAGRAPH;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: SENTENCE
    }
}

/**
 * Cuts the text so far into pieces that can be read now.
 *
 * Reading starts on the first sentences rather than the whole answer, so a long
 * answer begins about as quickly as a short one.
 */
object SpeechPieces {
    const val LONGEST_TO_SPEAK = 220

    private val SENTENCE_END = Regex("""[.!?…]["')\]]*(?=\s)|\n""")
    private val PARAGRAPH_END = Regex("""\n[ \t]*\n+""")

    data class Cut(val parts: List<String>, val upTo: Int)

    /** @param whole no more text is coming, so what is left is a piece however it ends. */
    fun cut(text: String, whole: Boolean, chunking: SpeechChunking): Cut {
        val boundary = when (chunking) {
            SpeechChunking.SENTENCE -> SENTENCE_END
            SpeechChunking.PARAGRAPH -> PARAGRAPH_END
            SpeechChunking.NONE -> null
        }
        val cuts = boundary?.findAll(text)?.map { it.range.last + 1 }?.toMutableList() ?: mutableListOf()
        if (whole && cuts.lastOrNull() != text.length) cuts += text.length

        val parts = mutableListOf<String>()
        var start = 0
        var at = 0
        while (at < cuts.size) {
            var end = cuts[at]
            // Sentences gather up while they still fit in one breath; a paragraph is already the size asked for.
            if (chunking == SpeechChunking.SENTENCE) {
                while (at + 1 < cuts.size && cuts[at + 1] - start <= LONGEST_TO_SPEAK) {
                    at++
                    end = cuts[at]
                }
            }
            parts += text.substring(start, end)
            start = end
            at++
        }
        return Cut(parts.map(String::trim).filter(String::isNotEmpty), start)
    }
}
