package io.mszymanski.orknux.mobile.chat

import io.mszymanski.orknux.mobile.core.OrknuxClient
import io.mszymanski.orknux.mobile.core.OrknuxJson
import io.mszymanski.orknux.mobile.core.SseFrame
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private const val SESSION_FIELDS =
    "id workspaceId title pinned modelName createdAt lastMessageAt unreadCount agentId agentName llmSessionId mode"
private const val REQUEST_FIELDS =
    "id kind askedBy question options { id label style description } items { name kind description } chosen multiple allowsText"
private const val WORKSPACE_FIELDS =
    "id name transcriptionModelId speechModelId voicePauseEndsTurnMs voiceSpeechOverRoomPercent " +
        "voiceUnattendedMicrophoneMs voiceBargeInMs voiceBargeInWhileThinking voiceSpeechChunking voiceButtonHidden"

/** The chat's half of the server's API; what the web client's `api/chat.ts` holds. */
class ChatApi(private val client: OrknuxClient) {

    suspend fun workspaces(): List<Workspace> = client.graphql(
        Page.serializer(Workspace.serializer()),
        "workspaces",
        "query { workspaces(page: 0, size: 100) { content { $WORKSPACE_FIELDS } } }",
    ).content

    suspend fun workspace(id: String): Workspace = client.graphql(
        Workspace.serializer(),
        "workspace",
        "query (\$id: ID!) { workspace(id: \$id) { $WORKSPACE_FIELDS } }",
        buildJsonObject { put("id", id) },
    )

    suspend fun agents(workspaceId: String): List<Agent> = client.graphql(
        Page.serializer(Agent.serializer()),
        "workspaceAgents",
        "query (\$w: ID!) { workspaceAgents(workspaceId: \$w, page: 0, size: 100, order: \"NAME\", ascending: true) { content { id name enabled modelId } } }",
        buildJsonObject { put("w", workspaceId) },
    ).content

    suspend fun sessions(workspaceId: String): List<ChatSession> = client.graphql(
        ListSerializer(ChatSession.serializer()),
        "chatSessions",
        "query (\$w: ID!) { chatSessions(workspaceId: \$w) { $SESSION_FIELDS } }",
        buildJsonObject { put("w", workspaceId) },
    )

    suspend fun session(id: String): ChatSession? {
        val data = client.graphqlData(
            "query (\$id: ID!) { chatSession(id: \$id) { $SESSION_FIELDS } }",
            buildJsonObject { put("id", id) },
        )
        val found = data["chatSession"]
        return if (found == null || found is JsonNull) null else OrknuxClient.decoded { OrknuxJson.decodeFromJsonElement(found) }
    }

    suspend fun messages(id: String): List<ChatMessage> = client.graphql(
        ListSerializer(ChatMessage.serializer()),
        "chatMessages",
        "query (\$id: ID!) { chatMessages(id: \$id) { role content actor thinking thinkingMillis at attachments { id filename contentType sizeBytes } } }",
        buildJsonObject { put("id", id) },
    )

    suspend fun suggestions(id: String): List<String> = client.graphql(
        ListSerializer(String.serializer()),
        "chatReplySuggestions",
        "query (\$id: ID!) { chatReplySuggestions(chatId: \$id) }",
        buildJsonObject { put("id", id) },
    )

    /** Left without an agent, the server opens it on whichever this person last talked to. */
    suspend fun start(workspaceId: String, agentId: String?): ChatSession = client.graphql(
        ChatSession.serializer(),
        "startChat",
        "mutation (\$input: StartChatInput!) { startChat(input: \$input) { $SESSION_FIELDS } }",
        buildJsonObject {
            putJsonObject("input") {
                put("workspaceId", workspaceId)
                if (agentId != null) put("agentId", agentId)
            }
        },
    )

    private suspend fun changeSession(mutation: String, arguments: String, declared: String, vars: kotlinx.serialization.json.JsonObject) =
        client.graphql(
            ChatSession.serializer(),
            mutation,
            "mutation ($declared) { $mutation($arguments) { $SESSION_FIELDS } }",
            vars,
        )

    suspend fun rename(id: String, title: String) = changeSession(
        "renameChat", "id: \$id, title: \$t", "\$id: ID!, \$t: String!",
        buildJsonObject { put("id", id); put("t", title) },
    )

    suspend fun setPinned(id: String, pinned: Boolean) = changeSession(
        "setChatPinned", "id: \$id, pinned: \$p", "\$id: ID!, \$p: Boolean!",
        buildJsonObject { put("id", id); put("p", pinned) },
    )

    suspend fun setMode(id: String, mode: ChatMode) = changeSession(
        "setChatMode", "id: \$id, mode: \$m", "\$id: ID!, \$m: ChatMode!",
        buildJsonObject { put("id", id); put("m", mode.name) },
    )

    suspend fun effort(id: String): ChatEffort? {
        val found = client.graphqlData(
            "query (\$id: ID!) { chatSession(id: \$id) { reasoningEffort reasoningEfforts } }",
            buildJsonObject { put("id", id) },
        )["chatSession"]
        return if (found == null || found is JsonNull) null else OrknuxClient.decoded { OrknuxJson.decodeFromJsonElement(found) }
    }

    /** Null goes back to the model's own setting. */
    suspend fun setEffort(id: String, effort: String?): ChatEffort = client.graphql(
        ChatEffort.serializer(),
        "setChatReasoningEffort",
        "mutation (\$id: ID!, \$e: String) { setChatReasoningEffort(id: \$id, effort: \$e) { reasoningEffort reasoningEfforts } }",
        buildJsonObject { put("id", id); put("e", effort) },
    )

    suspend fun chooseAgent(id: String, agentId: String) = changeSession(
        "chooseChatAgent", "id: \$id, agentId: \$a", "\$id: ID!, \$a: ID!",
        buildJsonObject { put("id", id); put("a", agentId) },
    )

    suspend fun markRead(id: String) {
        client.graphqlData("mutation (\$id: ID!) { markChatRead(id: \$id) { id } }", buildJsonObject { put("id", id) })
    }

    suspend fun delete(id: String) {
        client.graphqlData("mutation (\$id: ID!) { deleteChat(id: \$id) }", buildJsonObject { put("id", id) })
    }

    /** False where no turn is running any more: the message is then a turn of its own. */
    suspend fun postDuringTurn(id: String, text: String): Boolean = client.graphql(
        Boolean.serializer(),
        "postChatMessageDuringTurn",
        "mutation (\$id: ID!, \$t: String!) { postChatMessageDuringTurn(id: \$id, text: \$t) }",
        buildJsonObject { put("id", id); put("t", text) },
    )

    suspend fun requests(sessionId: String): List<InteractionRequest> = client.graphql(
        ListSerializer(InteractionRequest.serializer()),
        "sessionRequests",
        "query (\$s: ID!) { sessionRequests(sessionId: \$s) { $REQUEST_FIELDS } }",
        buildJsonObject { put("s", sessionId) },
    )

    suspend fun answer(id: String, options: List<String>, text: String?): InteractionRequest = client.graphql(
        InteractionRequest.serializer(),
        "answerRequest",
        "mutation (\$id: ID!, \$o: [String!], \$t: String) { answerRequest(id: \$id, options: \$o, text: \$t) { $REQUEST_FIELDS } }",
        buildJsonObject {
            put("id", id)
            putJsonArray("o") { options.forEach { add(it) } }
            put("t", text)
        },
    )

    suspend fun stream(id: String, text: String, attachmentIds: List<String>, voice: Boolean, onFrame: suspend (SseFrame) -> Unit) {
        val body = client.jsonBody(
            buildJsonObject {
                put("text", text)
                put("voice", voice)
                put("attachmentIds", buildJsonArray { attachmentIds.forEach { add(it.toLong()) } })
            },
        )
        client.stream(client.request("/api/chats/$id/stream").post(body).build(), onFrame)
    }

    suspend fun regenerate(id: String, onFrame: suspend (SseFrame) -> Unit) {
        client.stream(client.request("/api/chats/$id/regenerate").post(ByteArray(0).toRequestBody()).build(), onFrame)
    }

    suspend fun follow(id: String, wait: Boolean = false, onFrame: suspend (SseFrame) -> Unit) {
        client.stream(client.request("/api/chats/$id/follow" + if (wait) "?wait=true" else "").build(), onFrame)
    }

    suspend fun interrupt(id: String) {
        client.send(client.request("/api/chats/$id/interrupt").post(ByteArray(0).toRequestBody()).build()) { }
    }

    suspend fun upload(workspaceId: String, filename: String, contentType: String, bytes: ByteArray): List<Attachment> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", filename, bytes.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        val text = client.send(client.request("/api/workspaces/$workspaceId/attachments").post(body).build()) { it.body.string() }
        return OrknuxClient.decoded {
            OrknuxJson.parseToJsonElement(text).jsonObject["attachments"]?.let { OrknuxJson.decodeFromJsonElement<List<Attachment>>(it) }.orEmpty()
        }
    }

    fun attachmentUrl(id: String): String = client.url("/api/attachments/$id")
}
