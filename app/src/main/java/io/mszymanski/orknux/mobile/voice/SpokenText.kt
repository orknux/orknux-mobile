package io.mszymanski.orknux.mobile.voice

/**
 * What a message reads as out loud: what it renders to, never its markdown.
 * A fenced code block is announced rather than read, as on the web.
 */
fun spokenText(markdown: String): String {
    val lines = mutableListOf<String>()
    var fence: String? = null
    for (raw in markdown.lineSequence()) {
        val opens = FENCE.find(raw)?.groupValues?.get(1)
        val open = fence
        if (open != null) {
            if (opens != null && opens.startsWith(open.take(3))) fence = null
            continue
        }
        if (opens != null) {
            fence = opens
            lines += CODE_BLOCK_SAID
            continue
        }
        if (RULE.matches(raw) || UNDERLINE.matches(raw) || ADDRESS.containsMatchIn(raw)) continue
        var line = raw
        while (QUOTE.containsMatchIn(line)) line = line.replaceFirst(QUOTE, "")
        HEADING.matchEntire(line)?.let { line = it.groupValues[1] }
        line = line.replaceFirst(BULLET, "").replaceFirst(BOX, "")
        if (ROW.containsMatchIn(line)) {
            if (TABLE_RULE.matches(line)) continue
            line = line.trim().trim('|').split('|').joinToString(", ") { it.trim() }
        }
        lines += inline(line)
    }
    return lines.joinToString("\n").replace(BLANKS, "\n\n").trim()
}

private fun inline(line: String): String = line
    .replace(PICTURE, "$1")
    .replace(LINK, "$1")
    .replace(AUTOLINK, "$1")
    // Also takes a marker whose partner has not arrived yet, in an answer still being written.
    .replace(EMPHASIS, "")
    .replace(UNDERSCORES, "$1")
    .replace("`", "")
    .replace(TAG, "")

const val CODE_BLOCK_SAID = "Code block."

private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")
private val HEADING = Regex("""^ {0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
private val UNDERLINE = Regex("""^ {0,3}(=+|-{3,})\s*$""")
private val RULE = Regex("""^ {0,3}((\*\s*){3,}|(-\s*){3,}|(_\s*){3,})$""")
private val QUOTE = Regex("""^ {0,3}>\s?""")
private val BULLET = Regex("""^\s*([-*+]|\d+[.)])\s+""")
private val BOX = Regex("""^\[[ xX]]\s+""")
private val ADDRESS = Regex("""^ {0,3}\[[^]]+]:\s+\S+""")
private val ROW = Regex("""^ {0,3}\|""")
private val TABLE_RULE = Regex("""^[\s|:-]+$""")
private val PICTURE = Regex("""!\[([^]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^]]+)]\([^)]*\)""")
private val AUTOLINK = Regex("""<(https?://[^>]+)>""")
private val EMPHASIS = Regex("""\*+|~~""")
private val UNDERSCORES = Regex("""(?<![\w])_{1,3}([^_]+)_{1,3}(?![\w])""")
private val TAG = Regex("""</?[a-zA-Z][^>]*>""")
private val BLANKS = Regex("""\n{3,}""")
