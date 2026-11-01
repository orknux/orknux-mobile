package io.mszymanski.orknux.mobile.chat

import io.mszymanski.orknux.mobile.core.ApiException
import io.mszymanski.orknux.mobile.core.OrknuxClient
import io.mszymanski.orknux.mobile.core.OrknuxJson
import io.mszymanski.orknux.mobile.core.SseFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from a real server, where this client first got them wrong. */
class WireFormatTest {
    @Test
    fun graphqlFloatsAreRead() {
        val line = OrknuxJson.decodeFromString(
            ChatMessage.serializer(),
            """{"role":"assistant","content":"hi","actor":null,"thinkingMillis":3800.0,"attachments":[{"id":"1","filename":"a.png","contentType":"image/png","sizeBytes":2048.0}]}""",
        )
        assertEquals(3800L, line.thinkingMillis?.toLong())
        assertTrue(line.attachments.single().isPicture)
    }

    @Test
    fun aFiledFrameIsRead() {
        val event = SseFrame("filed", """{"id":"7","filename":"a.txt","contentType":"text/plain","sizeBytes":12}""").toTurnEvent()
        assertEquals("7", (event as TurnEvent.Filed).attachment.id)
    }

    /** The real endpoint's shape: `ChatAttachmentView.id` is a Long, so a number on the wire. */
    @Test
    fun anUploadedFileGoesUpAndItsIdGoesOutWithTheMessage() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"attachments":[{"id":9,"filename":"a.png","contentType":"image/png","sizeBytes":12,"uploadedBy":"alice"}]}""").build())
            server.enqueue(MockResponse.Builder().body("event: done\ndata: {}\n\n").build())
            val client = OrknuxClient(server.url("/").toString().trimEnd('/'), null, okhttp3.CookieJar.NO_COOKIES)
            val api = ChatApi(client)

            val kept = api.upload("1", "a.png", "image/png", ByteArray(12))
            assertEquals("9", kept.single().id)
            assertTrue(kept.single().isPicture)
            val upload = server.takeRequest()
            assertEquals("/api/workspaces/1/attachments", upload.url.encodedPath)
            val form = upload.body!!.utf8()
            assertTrue(form, form.contains("name=\"files\"; filename=\"a.png\"") && form.contains("Content-Type: image/png"))

            api.stream("3", "look", kept.map(Attachment::id), voice = false) { }
            val sent = OrknuxJson.parseToJsonElement(server.takeRequest().body!!.utf8()).toString()
            assertTrue(sent, sent.contains("\"attachmentIds\":[9]"))
        }
    }

    @Test
    fun anAttachmentFromGraphqlIsStillRead() {
        val line = OrknuxJson.decodeFromString(ChatMessage.serializer(), """{"role":"user","attachments":[{"id":"9"}]}""")
        assertEquals("9", line.attachments.single().id)
    }

    @Test
    fun anAnswerThatCannotBeReadIsAFailureNotACrash() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"data":{"chatMessages":[{"role":7,"thinkingMillis":"soon"}]}}""").build())
            val client = OrknuxClient(server.url("/").toString().trimEnd('/'), null, okhttp3.CookieJar.NO_COOKIES)
            val failure = runCatching {
                client.graphql(ListSerializer(ChatMessage.serializer()), "chatMessages", "query { x }")
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ApiException)
        }
    }
}
