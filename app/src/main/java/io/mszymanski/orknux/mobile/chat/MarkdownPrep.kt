package io.mszymanski.orknux.mobile.chat

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Makes a model's markdown safe and drawable on a phone.
 *
 * Pictures the server itself holds are drawn. A picture from anywhere else
 * becomes a link: what a model writes is untrusted, and `![](https://x/?q=…)` is
 * fetched the moment it is drawn, which is the road an injected prompt carries
 * data out by. In a browser an installation can close that with its
 * content-security policy; nothing enforces that policy here, so it stays closed.
 */
object MarkdownPrep {
    private val PICTURE = Regex("""!\[([^]]*)]\(\s*([^)\s]+)([^)]*)\)""")
    private val LINK = Regex("""(?<!!)\[([^]]*)]\(\s*(/[^)\s]*)([^)]*)\)""")
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")

    fun prepare(markdown: String, base: String): String {
        val host = base.toHttpUrl().host
        var fenced = false
        return markdown.lineSequence().joinToString("\n") { line ->
            if (FENCE.containsMatchIn(line)) {
                fenced = !fenced
                line
            } else if (fenced) {
                line
            } else {
                line
                    .replace(PICTURE) { found ->
                        val alt = found.groupValues[1]
                        val url = found.groupValues[2]
                        when {
                            url.startsWith("/") -> "![$alt]($base$url)"
                            url.toHttpUrlOrNull()?.host == host -> found.value
                            else -> "[${alt.ifBlank { "Picture" }}]($url)"
                        }
                    }
                    .replace(LINK) { found -> "[${found.groupValues[1]}]($base${found.groupValues[2]})" }
            }
        }
    }
}
