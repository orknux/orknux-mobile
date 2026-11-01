package io.mszymanski.orknux.mobile.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The session cookie, kept across restarts and only ever for this one server.
 *
 * The same client fetches pictures, and a jar that answered for any host would
 * be one redirect away from handing the session to somebody else.
 */
class ServerCookies(
    base: String,
    saved: List<String>,
    private val save: (List<String>) -> Unit,
) : CookieJar {
    private val server = base.toHttpUrl()
    private val held = saved.mapNotNull { Cookie.parse(server, it) }.toMutableList()

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.host != server.host) return emptyList()
        val now = System.currentTimeMillis()
        return held.filter { it.expiresAt > now && it.matches(url) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != server.host || cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        cookies.forEach { fresh ->
            held.removeAll { it.name == fresh.name && it.path == fresh.path }
            if (fresh.expiresAt > now) held += fresh
        }
        save(held.map(Cookie::toString))
    }
}
