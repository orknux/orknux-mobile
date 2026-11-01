package io.mszymanski.orknux.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadByAgentTest {
    private val asked = ChatMessage("user", "hello")
    private val answered = ChatMessage("assistant", "hi")

    @Test
    fun theEyeGoesOnTheLastThingSaidOnceAnythingFollowsIt() {
        assertEquals(2, readLineIndex(listOf(asked, answered, asked, answered), null))
        assertNull("nothing has come of it yet", readLineIndex(listOf(answered, asked), LiveTurn()))
        assertNull("a stopped turn", readLineIndex(listOf(asked), null))
        assertNull(readLineIndex(emptyList(), null))
    }

    @Test
    fun theAgentAtWorkHasReadIt() {
        assertEquals(0, readLineIndex(listOf(asked), LiveTurn(thinking = "hm")))
        assertEquals(0, readLineIndex(listOf(asked), LiveTurn(answer = "h")))
        assertEquals(0, readLineIndex(listOf(asked), LiveTurn(steps = listOf(LiveStep.Call(0, "tool", "{}")))))
    }

    @Test
    fun readingALongPromptOrAQueuedLineIsNotReadingIt() {
        assertNull(readLineIndex(listOf(asked), LiveTurn(status = "Reading the conversation... 40%")))
        assertNull(readLineIndex(listOf(asked), LiveTurn(steps = listOf(LiveStep.Person("more")))))
    }

    @Test
    fun aLineWrittenDuringTheTurnIsMarkedOnceAndInOrder() {
        val two = listOf(LiveStep.Person("again"), LiveStep.Person("again"))
        assertEquals(listOf(LiveStep.Person("again", true), LiveStep.Person("again")), markPersonRead(two, "again"))
    }

    @Test
    fun aLineWrittenElsewhereIsAddedAsRead() {
        assertEquals(listOf<LiveStep>(LiveStep.Person("from the web", true)), markPersonRead(emptyList(), "from the web"))
    }
}
