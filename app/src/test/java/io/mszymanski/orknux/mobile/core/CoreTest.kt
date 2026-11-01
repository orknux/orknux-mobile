package io.mszymanski.orknux.mobile.core

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun framesAreParsedAndHalfAFrameIsNot() {
        val frames = mutableListOf<SseFrame>()
        Buffer().writeUtf8("event: chunk\ndata: {\"text\":\"hi\"}\n\n: ping\n\nevent:done\ndata:{}\n\nevent: chunk\ndata: {\"text\":\"ha")
            .readSseFrames { frames += it }
        assertEquals(listOf(SseFrame("chunk", "{\"text\":\"hi\"}"), SseFrame("done", "{}")), frames)
    }

    @Test
    fun addressesAreNormalised() {
        assertEquals("https://orknux.example.com", ServerAddress.normalise(" orknux.example.com/ "))
        assertEquals("http://10.0.2.2:8080", ServerAddress.normalise("10.0.2.2:8080"))
        assertEquals("http://192.168.1.4:8080", ServerAddress.normalise("192.168.1.4:8080"))
        assertEquals("http://example.com", ServerAddress.normalise("http://example.com"))
        assertNull(ServerAddress.normalise("  "))
        assertNull(ServerAddress.normalise("ftp://example.com"))
    }

    @Test
    fun onlyAnAddressAcrossANetworkCountsAsExposed() {
        assertTrue(ServerAddress.isExposed("http://192.168.1.4:8080"))
        assertFalse(ServerAddress.isExposed("http://10.0.2.2:8080"))
        assertFalse(ServerAddress.isExposed("https://example.com"))
    }

    @Test
    fun cookiesSurviveARestartAndAreNeverSentElsewhere() {
        var saved = emptyList<String>()
        val server = "https://orknux.example.com/graphql".toHttpUrl()
        ServerCookies("https://orknux.example.com", emptyList()) { saved = it }
            .saveFromResponse(server, listOf(Cookie.parse(server, "JSESSIONID=abc; Path=/; HttpOnly")!!))
        val restarted = ServerCookies("https://orknux.example.com", saved) {}
        assertEquals("abc", restarted.loadForRequest(server).single().value)
        assertTrue(restarted.loadForRequest("https://elsewhere.example.org/a.png".toHttpUrl()).isEmpty())
    }

    @Test
    fun theTokenGoesToTheServerAndNowhereElse() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("ok").build())
            server.enqueue(MockResponse.Builder().body("ok").build())
            val base = server.url("/").toString().trimEnd('/')
            val client = OrknuxClient(base, "orkx_secret", okhttp3.CookieJar.NO_COOKIES)
            client.send(client.request("/a").build()) {}
            assertEquals("Bearer orkx_secret", server.takeRequest().headers["Authorization"])

            // The same machine under another name: another host as far as credentials go.
            val other = server.url("/b").newBuilder().host("127.0.0.1").build().let {
                if (server.hostName == "127.0.0.1") it.newBuilder().host("localhost").build() else it
            }
            client.send(okhttp3.Request.Builder().url(other).build()) {}
            assertNull(server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun aRefusalSaysWhatTheServerSaid() = runTest {
        assertEquals("Nope", OrknuxClient.refusalText("""{"error":"Nope"}"""))
        assertEquals("Nope", OrknuxClient.refusalText("Nope"))
        assertNull(OrknuxClient.refusalText("<html>Bad gateway</html>"))
    }

    @Test
    fun unauthorisedSignsOut() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            var signedOut = false
            val client = OrknuxClient(server.url("/").toString().trimEnd('/'), null, okhttp3.CookieJar.NO_COOKIES) { signedOut = true }
            val failure = runCatching { client.send(client.request("/a").build()) {} }.exceptionOrNull()
            assertEquals(401, (failure as ApiException).status)
            assertTrue(signedOut)
        }
    }
}
