package io.mszymanski.orknux.mobile.core

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.mszymanski.orknux.mobile.chat.ChatCache
import io.mszymanski.orknux.mobile.voice.DEFAULT_PAUSE_MS
import io.mszymanski.orknux.mobile.voice.DEFAULT_PITCH_HZ
import io.mszymanski.orknux.mobile.voice.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AuthMethod(
    val method: String = "LDAP",
    val displayName: String? = null,
    val notice: String? = null,
) {
    val noSignIn: Boolean get() = method == "NONE"
    val takesPassword: Boolean get() = method == "LDAP" || method == "INTERNAL"
}

@Serializable
data class SessionUser(val username: String = "", val displayName: String? = null) {
    val name: String get() = displayName ?: username
}

/**
 * Who is signed in, to which server, surviving restarts.
 *
 * A session cookie for a password, a bearer token otherwise. A browser's OIDC
 * or GitHub redirect ends in a cookie this app has no way to receive, so on
 * those installations a phone signs in with an access token.
 */
class AppSession(context: Context) {
    private val app = context.applicationContext
    private val store = SecureStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _client = MutableStateFlow<OrknuxClient?>(null)
    val client: StateFlow<OrknuxClient?> = _client.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val lastServer: String? get() = store.get(KEY_SERVER)

    var lastWorkspace: String?
        get() = store.get(KEY_WORKSPACE)
        set(value) = store.put(KEY_WORKSPACE, value)

    var speechSpeed: Float
        get() = store.get(KEY_SPEED)?.toFloatOrNull() ?: 1f
        set(value) = store.put(KEY_SPEED, value.toString())

    /** The server's voice until somebody chooses otherwise; kept across sign-ins, since it is about this phone. */
    var speechEngine: SpeechEngine
        get() = SpeechEngine.entries.firstOrNull { it.name == store.get(KEY_ENGINE) } ?: SpeechEngine.SERVER
        set(value) = store.put(KEY_ENGINE, value.name)

    /** Speech to text, chosen the same way. */
    var listenEngine: SpeechEngine
        get() = SpeechEngine.entries.firstOrNull { it.name == store.get(KEY_LISTEN) } ?: SpeechEngine.SERVER
        set(value) = store.put(KEY_LISTEN, value.name)

    /** The silence between rounds of voice mode's thinking sound. */
    var thinkingPauseMs: Int
        get() = store.get(KEY_THINKING_PAUSE)?.toIntOrNull() ?: DEFAULT_PAUSE_MS
        set(value) = store.put(KEY_THINKING_PAUSE, value.toString())

    /** Where each pop of the thinking sound starts, in Hz. */
    var thinkingPitchHz: Int
        get() = store.get(KEY_THINKING_PITCH)?.toIntOrNull() ?: DEFAULT_PITCH_HZ
        set(value) = store.put(KEY_THINKING_PITCH, value.toString())

    init {
        val base = lastServer
        if (base != null && store.get(KEY_SIGNED_IN) != null) use(build(base, store.get(KEY_TOKEN), keepCookies = true))
    }

    private fun build(base: String, token: String?, keepCookies: Boolean): OrknuxClient {
        val saved = if (keepCookies) store.get(KEY_COOKIES)?.let { OrknuxJson.decodeFromString(LINES, it) }.orEmpty() else emptyList()
        val cookies = ServerCookies(base, saved) { store.put(KEY_COOKIES, OrknuxJson.encodeToString(LINES, it)) }
        return OrknuxClient(base, token, cookies, onSignedOut = ::lost)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class) // Replaced on purpose: pictures need this server's session.
    private fun use(client: OrknuxClient) {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(app)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client.http })) }
                .build(),
        )
        _client.value = client
    }

    suspend fun authMethod(base: String): AuthMethod {
        val probe = OrknuxClient(base, null, okhttp3.CookieJar.NO_COOKIES)
        val text = try {
            probe.send(probe.request("/api/auth/method").build()) { it.body.string() }
        } catch (failure: java.io.IOException) {
            throw ApiException("No Orknux server answered at that address: ${failure.message}")
        }
        return runCatching { OrknuxJson.decodeFromString(AuthMethod.serializer(), text) }
            .getOrElse { throw ApiException("That address answered, but not as Orknux does.") }
    }

    suspend fun signInWithPassword(base: String, username: String, password: String) {
        val client = build(base, null, keepCookies = false)
        val body = buildJsonObject { put("username", username); put("password", password) }
        try {
            client.send(client.request("/api/session").post(client.jsonBody(body)).build()) { }
        } catch (refused: ApiException) {
            if (refused.status == 401) throw ApiException("Invalid username or password.", 401)
            throw refused
        }
        commit(base, null, client)
    }

    /** For an installation with no sign-in, and for a token: asking who this is proves it works. */
    suspend fun signInWith(base: String, token: String?) {
        val client = build(base, token, keepCookies = false)
        client.send(client.request("/api/session").build()) { }
        commit(base, token, client)
    }

    private fun commit(base: String, token: String?, client: OrknuxClient) {
        store.put(KEY_SERVER, base)
        store.put(KEY_TOKEN, token)
        store.put(KEY_SIGNED_IN, "yes")
        _notice.value = null
        use(client)
    }

    fun signOut() {
        val client = _client.value
        clear()
        if (client != null) {
            scope.launch {
                runCatching { client.send(client.request("/api/session").delete(ByteArray(0).toRequestBody()).build()) { } }
            }
        }
    }

    private fun lost() {
        if (_client.value == null) return
        _notice.value = "Your session has ended. Sign in again."
        clear()
    }

    private val cacheFolder = java.io.File(app.filesDir, "chats")

    /** The chats last looked at on this server; see [ChatCache]. */
    fun chatCache(client: OrknuxClient) = ChatCache(cacheFolder, client.base, store::seal, store::open)

    private fun clear() {
        cacheFolder.deleteRecursively()
        store.put(KEY_SIGNED_IN, null)
        store.put(KEY_TOKEN, null)
        store.put(KEY_COOKIES, null)
        _client.value = null
    }

    suspend fun user(client: OrknuxClient): SessionUser =
        client.send(client.request("/api/session").build()) { OrknuxJson.decodeFromString(SessionUser.serializer(), it.body.string()) }

    private companion object {
        val LINES = ListSerializer(String.serializer())
        const val KEY_SERVER = "server"
        const val KEY_TOKEN = "token"
        const val KEY_COOKIES = "cookies"
        const val KEY_SIGNED_IN = "signedIn"
        const val KEY_WORKSPACE = "workspace"
        const val KEY_SPEED = "speechSpeed"
        const val KEY_ENGINE = "speechEngine"
        const val KEY_LISTEN = "listenEngine"
        const val KEY_THINKING_PAUSE = "thinkingPauseMs"
        const val KEY_THINKING_PITCH = "thinkingPitchHz"
    }
}
