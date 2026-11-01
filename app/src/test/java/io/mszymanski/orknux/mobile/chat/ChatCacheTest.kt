package io.mszymanski.orknux.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private fun cache(server: String = "https://a.example") = ChatCache(
        temp.root,
        server,
        seal = { it.reversedArray() },
        open = { it.reversedArray() },
    )

    private val chat = CachedChat(lines = listOf(ChatMessage("user", "zażółć gęślą")))

    @Test
    fun whatWasWrittenIsRead() {
        cache().write("7", chat)
        assertEquals(chat, cache().read("7"))
    }

    @Test
    fun itIsSealedOnDisk() {
        cache().write("7", chat)
        val onDisk = temp.root.listFiles()!!.single().readBytes().decodeToString()
        assertFalse(onDisk.contains("\"role\""))
    }

    @Test
    fun anotherServersChatOfTheSameNumberIsNotThisOne() {
        cache("https://a.example").write("7", chat)
        assertNull(cache("https://b.example").read("7"))
    }

    @Test
    fun whatCannotBeReadIsSimplyNotThere() {
        cache().write("7", chat)
        temp.root.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache().read("7"))
        assertNull(cache().read("8"))
    }

    @Test
    fun onlyTheLastLookedAtAreKept() {
        repeat(25) { cache().write(it.toString(), chat) }
        assertTrue(temp.root.listFiles()!!.size <= 20)
    }

    @Test
    fun aDeletedChatLeavesNothing() {
        cache().write("7", chat)
        cache().remove("7")
        assertNull(cache().read("7"))
    }
}
