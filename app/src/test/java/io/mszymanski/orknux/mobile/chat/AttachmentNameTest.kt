package io.mszymanski.orknux.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentNameTest {
    @Test
    fun anOrdinaryNameIsKept() {
        assertEquals("Bm-G-A-E.mp3", safeFileName("Bm-G-A-E.mp3"))
        assertEquals("nuty zażółć.pdf", safeFileName("nuty zażółć.pdf"))
    }

    @Test
    fun aNameCannotLeaveItsFolder() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("evil.txt", safeFileName("..\\..\\evil.txt"))
        assertEquals("hidden", safeFileName(".hidden"))
        assertEquals("file", safeFileName(".."))
        assertEquals("file", safeFileName(""))
    }

    @Test
    fun aFileThatArrivesDuringATurnIsRead() {
        val event = io.mszymanski.orknux.mobile.core.SseFrame("filed", """{"id":"7","filename":"Bm-G-A-E.mp3","contentType":"audio/mpeg","sizeBytes":12}""").toTurnEvent()
        assertEquals("Bm-G-A-E.mp3", (event as TurnEvent.Filed).attachment.filename)
    }
}
