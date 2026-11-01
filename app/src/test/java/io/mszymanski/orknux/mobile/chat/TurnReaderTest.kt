package io.mszymanski.orknux.mobile.chat

import io.mszymanski.orknux.mobile.core.ApiException
import io.mszymanski.orknux.mobile.core.SseFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TurnReaderTest {
    private fun chunk(text: String) = SseFrame("chunk", """{"text":"$text"}""")
    private val done = SseFrame("done", """{"millis":5}""")

    private fun texts(events: List<TurnEvent>) = events.filterIsInstance<TurnEvent.Chunk>().joinToString("") { it.text }

    @Test
    fun aCutStreamIsPickedUpWithoutDrawingAnythingTwice() = runTest {
        val events = mutableListOf<TurnEvent>()
        val reader = TurnReader(
            follow = { onFrame ->
                onFrame(SseFrame("following", "{}"))
                listOf("a", "b", "c").forEach { onFrame(chunk(it)) }
                onFrame(done)
            },
            pause = {},
        )
        val heard = reader.read(
            first = { onFrame ->
                onFrame(chunk("a"))
                onFrame(chunk("b"))
                throw IOException("the network went away")
            },
            onEvent = { events += it },
        )
        assertTrue(heard)
        assertEquals("abc", texts(events))
        assertEquals("a repeat of following is not a new turn", 0, events.count { it is TurnEvent.Following })
    }

    @Test
    fun aTurnThatEndedWhileNobodyWasReadingSaysToReadTheHistory() = runTest {
        val reader = TurnReader(follow = { it(SseFrame("idle", "{}")) }, pause = {})
        val heard = reader.read(first = { it(chunk("a")) }, onEvent = {})
        assertFalse(heard)
    }

    @Test
    fun aRefusalBeforeTheFirstFrameIsNotACutStream() = runTest {
        var followed = false
        val reader = TurnReader(follow = { followed = true }, pause = {})
        val failure = runCatching {
            reader.read(first = { throw ApiException("No such chat", 404) }, onEvent = {})
        }.exceptionOrNull()
        assertEquals("No such chat", failure?.message)
        assertFalse(followed)
    }

    @Test
    fun givesUpAfterItsAttempts() = runTest {
        var attempts = 0
        val reader = TurnReader(follow = { attempts++; throw IOException("still gone") }, pause = {})
        assertFalse(reader.read(first = { throw IOException("gone") }, onEvent = {}))
        assertEquals(5, attempts)
    }

    @Test
    fun anErrorEndsTheTurn() = runTest {
        val events = mutableListOf<TurnEvent>()
        val reader = TurnReader(follow = { error("not needed") }, pause = {})
        assertTrue(reader.read(first = { it(SseFrame("error", """{"reason":"no"}""")) }, onEvent = { events += it }))
        assertEquals(listOf<TurnEvent>(TurnEvent.Failed("no")), events)
    }

    @Test
    fun aFollowThatFoundNothingDoesNotKeepKnocking() = runTest {
        var attempts = 0
        val reader = TurnReader(follow = { attempts++ }, pause = {})
        assertFalse(reader.read(first = { }, onEvent = {}, reading = { false }))
        assertEquals(0, attempts)
    }

    @Test
    fun anUnknownFrameStillCountsItsPositionForTheReplay() = runTest {
        val events = mutableListOf<TurnEvent>()
        val reader = TurnReader(
            follow = { onFrame ->
                onFrame(SseFrame("widgets", """{"widgets":[]}"""))
                onFrame(chunk("a"))
                onFrame(done)
            },
            pause = {},
        )
        reader.read(
            first = { onFrame ->
                onFrame(SseFrame("widgets", """{"widgets":[]}"""))
                throw IOException("cut")
            },
            onEvent = { events += it },
        )
        assertEquals("a", texts(events))
    }
}
