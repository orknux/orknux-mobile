package io.mszymanski.orknux.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A refusal from the server, or the reason it could not be asked. */
class ApiException(message: String, val status: Int = 0) : IOException(message)

val OrknuxJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

private val JSON_TYPE = "application/json".toMediaType()

/**
 * The one way to the server: GraphQL for almost everything, REST for bytes and
 * server-sent events for answers, the same split the web client has.
 *
 * Non-browser requests carry no Origin, which is what the server's cross-site
 * guard lets through, and an access token where one was given. The token only
 * goes to [base]: this client also fetches pictures.
 */
class OrknuxClient(
    val base: String,
    private val token: String?,
    cookies: CookieJar,
    private val onSignedOut: () -> Unit = {},
) {
    private val serverHost = base.toHttpUrl().host

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        // Speech and transcription are a model answering, not a lookup.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (token != null && request.url.host == serverHost) {
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                } else {
                    request
                },
            )
        }
        .build()

    /** No read timeout: a model can think for ten minutes between two frames. */
    private val streaming: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    fun url(path: String): String = base + path

    fun request(path: String): Request.Builder = Request.Builder().url(url(path))

    suspend fun <T> graphql(
        strategy: DeserializationStrategy<T>,
        field: String,
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
    ): T {
        val data = graphqlData(query, variables)
        return decoded { OrknuxJson.decodeFromJsonElement(strategy, data[field] ?: JsonObject(emptyMap())) }
    }

    suspend fun graphqlData(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(JSON_TYPE)
        val text = send(request("/graphql").post(body).build()) { it.body.string() }
        val root = runCatching { OrknuxJson.parseToJsonElement(text).jsonObject }
            .getOrElse { throw ApiException("The server did not answer as Orknux does.") }
        root["errors"]?.jsonArray?.firstOrNull()?.let { first ->
            throw ApiException(first.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "The server refused that.")
        }
        return root["data"]?.jsonObject ?: throw ApiException("The server answered with nothing.")
    }

    /** Sends and hands over a successful response; anything else is an [ApiException]. */
    suspend fun <T> send(request: Request, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw refusal(response)
            read(response)
        }
    }

    /**
     * Opens an event stream and reads it to its end, on this coroutine.
     * Cancelling the coroutine closes the connection and nothing else: the
     * server goes on writing the answer, and stopping it is `/interrupt`.
     */
    suspend fun stream(request: Request, onFrame: suspend (SseFrame) -> Unit) = withContext(Dispatchers.IO) {
        val call = streaming.newCall(request)
        coroutineScope {
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.await().use { response ->
                    if (!response.isSuccessful) throw refusal(response)
                    response.body.source().readSseFrames { frame ->
                        ensureActive()
                        kotlinx.coroutines.runBlocking { onFrame(frame) }
                    }
                }
            } finally {
                closer.cancel()
            }
        }
    }

    fun jsonBody(body: JsonElement): RequestBody = body.toString().toRequestBody(JSON_TYPE)

    private fun refusal(response: Response): ApiException {
        if (response.code == 401) onSignedOut()
        val text = runCatching { response.body.string() }.getOrDefault("")
        return ApiException(refusalText(text) ?: "The server answered ${response.code}.", response.code)
    }

    companion object {
        /** An answer this version cannot read is a failed request to show, never a crash. */
        fun <T> decoded(read: () -> T): T = try {
            read()
        } catch (unreadable: kotlinx.serialization.SerializationException) {
            throw ApiException("The server's answer could not be read: ${unreadable.message?.take(160)}")
        } catch (unreadable: IllegalArgumentException) {
            throw ApiException("The server's answer could not be read: ${unreadable.message?.take(160)}")
        }

        /** What a refusal says, whichever of the server's shapes it came in; never a page of HTML. */
        fun refusalText(body: String): String? {
            val parsed = runCatching { OrknuxJson.parseToJsonElement(body).jsonObject }.getOrNull()
            if (parsed != null) {
                return listOf("error", "message", "detail", "reason")
                    .firstNotNullOfOrNull { parsed[it]?.jsonPrimitive?.contentOrNull }
            }
            val plain = body.trim()
            return plain.takeIf { it.isNotEmpty() && !it.startsWith("<") && it.length < 400 }
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, held, _ -> held.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
