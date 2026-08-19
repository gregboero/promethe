package dev.promethe.app.network

import dev.promethe.api.*
import dev.promethe.api.providers.ModelDescriptor
import dev.promethe.api.providers.ProviderDescriptor
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Prométhé client — all chat goes through A2A protocol.
 *
 * Chat flow:
 *   Client → POST /a2a (JSON-RPC message/send) → A2A Server → AIAgent
 *
 * Streaming flow:
 *   Client → POST /a2a (JSON-RPC message/stream) → SSE → ChatEvent flow
 *
 * Admin/REST routes (sessions, profiles, stats) remain as REST.
 */
class PrometheClient(
    private val baseUrl: String = "http://localhost:8080",
    private val apiKey: String = "",
    private val clientKind: LoginClientKind = LoginClientKind.NATIVE,
    private val csrfToken: String? = null,
    private val onUnauthorized: () -> Unit = {},
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val client =
        createPlatformHttpClient {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 3600_000L
                socketTimeoutMillis = 3600_000L
            }
            defaultRequest {
                csrfToken?.takeIf(String::isNotBlank)?.let { header("X-CSRF-Token", it) }
            }
            HttpResponseValidator {
                validateResponse { response ->
                    if (response.status == HttpStatusCode.Unauthorized) onUnauthorized()
                }
            }
        }

    /** Koog-backed A2A client for native protocol support */
    private val a2aChatClient = A2AChatClient(baseUrl, apiKey, csrfToken)

    /** WebSocket base URL derived from baseUrl (http→ws, https→wss). */
    val wsBaseUrl: String
        get() = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    /** Expose the active credential for native WebSocket Authorization headers. */
    fun getApiKey(): String = apiKey

    suspend fun getCapabilities(): CapabilityListResponse = authenticatedGet("$baseUrl/api/v1/capabilities")

    // ── Internal helpers ─────────────────────────────────────────────────────

    private suspend inline fun <reified T> authenticatedGet(url: String): T =
        client
            .get(url) {
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.body()

    private suspend inline fun <reified T> authenticatedPost(
        url: String,
        body: Any,
    ): T =
        client
            .post(url) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                setBody(body)
            }.body()

    private suspend inline fun <reified T> authenticatedPut(
        url: String,
        body: Any,
    ): T =
        client
            .put(url) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                setBody(body)
            }.body()

    // ── Auth helpers (no Bearer token needed) ────────────────────────────────

    /** Login from a remote device — returns a short-lived opaque session token. */
    suspend fun login(
        gatewayUrl: String,
        user: String,
        password: String,
    ): LoginResponse {
        val response = client.post("$gatewayUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(user = user, password = password, clientKind = clientKind))
        }
        if (response.status == io.ktor.http.HttpStatusCode.Forbidden) {
            throw IllegalStateException("403: Remote access is not configured on this gateway")
        }
        if (response.status == io.ktor.http.HttpStatusCode.Unauthorized) {
            throw IllegalStateException("401: Invalid credentials")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("${response.status.value}: ${response.bodyAsText().take(100)}")
        }
        return response.body()
    }

    suspend fun getAuthSession(): AuthSessionResponse = client.get("$baseUrl/api/v1/auth/session").body()

    suspend fun getProviders(): List<ProviderDescriptor> = authenticatedGet("$baseUrl/api/v1/providers")

    suspend fun getProviderModels(
        providerId: String,
    ): List<ModelDescriptor> = authenticatedGet("$baseUrl/api/v1/providers/$providerId/models")

    /** Current native sandbox backend status and self-test state. */
    suspend fun getSandboxStatus(): SandboxStatus =
        client
            .get("$baseUrl/api/v1/security/sandbox/status") {
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.requireSuccessfulJson("Get sandbox status")

    /** Active sandbox permission profile. */
    suspend fun getSandboxPermissionProfile(): SandboxPermissionProfile =
        client
            .get("$baseUrl/api/v1/security/permission-profile") {
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.requireSuccessfulJson("Get sandbox permission profile")

    /** Update the local sandbox permission profile. */
    suspend fun updateSandboxPermissionProfile(
        profile: SandboxPermissionProfile,
    ): SandboxPermissionProfile =
        client
            .put("$baseUrl/api/v1/security/permission-profile") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                setBody(profile)
            }.requireSuccessfulJson("Update sandbox permission profile")

    /** Run the native sandbox self-test. */
    suspend fun runSandboxSelfTest(): SandboxStatus =
        client
            .post("$baseUrl/api/v1/security/sandbox/self-test") {
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.requireSuccessfulJson("Run sandbox self-test")

    /** Install or repair the local Windows sandbox after explicit UAC consent. */
    suspend fun setupSandbox(): SandboxStatus =
        client
            .post("$baseUrl/api/v1/security/sandbox/setup") {
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.requireSuccessfulJson("Install sandbox")

    /** Create or replace the remote owner from a loopback client using its local API key. */
    suspend fun setupRemoteOwner(
        user: String,
        password: String,
    ) {
        client.put("$baseUrl/api/v1/security/remote-owner") {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            setBody(SetupRemoteOwnerRequest(user = user, password = password))
        }
    }

    /** Generic JSON GET — returns a raw JsonObject for dynamic endpoints. */
    suspend fun getJson(path: String): kotlinx.serialization.json.JsonObject {
        val text =
            client
                .get("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                }.bodyAsText()
        if (text.isBlank()) return kotlinx.serialization.json.JsonObject(emptyMap())
        return json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    /** GET a JSON array of strings (or objects with an `id` field) from the given path. */
    suspend fun getJsonList(path: String): List<String> {
        val text =
            client
                .get("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                }.bodyAsText()
        if (text.isBlank()) return emptyList()
        return try {
            val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                when (el) {
                    is kotlinx.serialization.json.JsonPrimitive -> el.content
                    is kotlinx.serialization.json.JsonObject -> el["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch voice list preserving id + description.
     * Returns list of pairs (id, description).
     */
    suspend fun getVoiceInfoList(path: String): List<Pair<String, String>> {
        val text =
            client
                .get("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                }.bodyAsText()
        if (text.isBlank()) return emptyList()
        return try {
            val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@mapNotNull null
                val desc = obj["description"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
                id to desc
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** POST /api/v1/voice/preview — synthesize a voice sample. Returns (base64Data, sampleRate) or null. */
    suspend fun previewVoice(
        provider: String,
        voice: String,
        text: String? = null,
    ): Pair<String, Int>? {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("provider", provider)
            put("voice", voice)
            if (text != null) put("text", text)
        }
        val response = client.post("$baseUrl/api/v1/voice/preview") {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            setBody(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body))
        }
        if (response.status.value !in 200..299) return null
        val obj = json.parseToJsonElement(response.bodyAsText()) as? kotlinx.serialization.json.JsonObject ?: return null
        val data = obj["data"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return null
        val sampleRate = obj["sampleRate"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 24000
        return data to sampleRate
    }

    /** Test gateway connectivity by calling the health endpoint. */
    suspend fun testConnection(): Result<String> =
        try {
            val text =
                client
                    .get("$baseUrl/api/v1/health") {
                        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                    }.bodyAsText()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Generic DELETE — for endpoints that return a simple status map. */
    suspend fun delete(path: String) {
        client.delete("$baseUrl$path") {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }
    }

    /** Generic POST JSON — sends body and returns response text. */
    suspend fun postJson(
        path: String,
        body: String,
    ): String =
        client
            .post("$baseUrl$path") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()

    /** Generic PUT JSON — sends body and returns response text. */
    suspend fun putJson(
        path: String,
        body: String,
    ): String =
        client
            .put("$baseUrl$path") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()

    /** Generic PATCH JSON — sends body and returns response text. */
    suspend fun patchJson(
        path: String,
        body: String,
    ): String =
        client
            .patch("$baseUrl$path") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()

    private fun generateId(): String = "msg-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"

    // ═════════════════════════════════════════════════════════════════════════
    // A2A Protocol — Agent Discovery
    // ═════════════════════════════════════════════════════════════════════════

    /** Discover the Prométhé agent card via A2A protocol */
    suspend fun discoverAgent(): A2AAgentCard = authenticatedGet("$baseUrl/.well-known/agent-card.json")

    // ═════════════════════════════════════════════════════════════════════════
    // A2A Protocol — Chat (message/send)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Send a message via A2A JSON-RPC (message/send).
     * One-shot — waits for full response.
     */
    suspend fun sendMessage(request: ChatRequest): ChatEvent {
        val msgId = generateId()
        val a2aParams =
            A2AMessageSendParams(
                message =
                    A2AMessage(
                        messageId = msgId,
                        role = "user",
                        parts = listOf(A2APart.Text(text = request.message)),
                        contextId = request.sessionId,
                    ),
            )

        val rpcRequest =
            A2AJsonRpcRequest(
                id = msgId,
                method = "message/send",
                params = json.encodeToJsonElement(A2AMessageSendParams.serializer(), a2aParams),
            )

        val response: A2AJsonRpcResponse = authenticatedPost("$baseUrl/agents/a2a", rpcRequest)

        // Extract text from A2A response
        val rpcError = response.error
        if (rpcError != null) {
            return ChatEvent(type = "error", content = rpcError.message)
        }

        // Parse the result as an A2A Message
        val resultMessage =
            response.result?.let {
                try {
                    json.decodeFromJsonElement(A2AMessage.serializer(), it)
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to decode A2A message from RPC result" }
                    null
                }
            }

        val text =
            resultMessage
                ?.parts
                ?.filterIsInstance<A2APart.Text>()
                ?.joinToString("\n") { it.text }
                ?: ""

        return ChatEvent(type = "response", content = text)
    }

    /**
     * Stream chat via A2A protocol using the native Koog A2AClient.
     *
     * Delegates to A2AChatClient which handles the full A2A protocol:
     * - Message streaming (SSE)
     * - Task status updates
     * - Task artifact updates
     * - Proper event mapping to ChatEvent
     *
     * @param profileProvider  Optional provider override (from the selected agent profile).
     * @param profileModel     Optional model override (from the selected agent profile).
     *   When provided, the server-side agent uses this model for the request.
     */
    fun chatStream(
        sessionId: String,
        message: String,
        profileId: String? = null,
        profileProvider: String? = null,
        profileModel: String? = null,
    ): Flow<ChatEvent> =
        a2aChatClient.sendMessageStreaming(
            sessionId = sessionId,
            text = message,
            profileId = profileId,
            profileProvider = profileProvider,
            profileModel = profileModel,
        )

    /** Alias for A2A-style naming */
    fun sendMessageStream(
        sessionId: String,
        message: String,
        profileId: String? = null,
        profileProvider: String? = null,
        profileModel: String? = null,
    ) = chatStream(sessionId, message, profileId, profileProvider, profileModel)

    // ═════════════════════════════════════════════════════════════════════════
    // REST API — Sessions (admin, not chat)
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getSessions(): List<SessionInfo> = authenticatedGet<SessionListResponse>("$baseUrl/api/v1/sessions").sessions

    suspend fun createSession(id: String? = null): SessionInfo = authenticatedPost("$baseUrl/api/v1/sessions", CreateSessionRequest(id))

    suspend fun createSession(
        id: String? = null,
        projectId: String?,
    ): SessionInfo = authenticatedPost("$baseUrl/api/v1/sessions", CreateSessionRequest(id, projectId))

    suspend fun deleteSession(sessionId: String) {
        client.delete("$baseUrl/api/v1/sessions/$sessionId") {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }
    }

    suspend fun getMessages(sessionId: String): List<ChatEvent> = authenticatedGet("$baseUrl/api/v1/sessions/$sessionId/messages")

    suspend fun updateSessionMetadata(
        sessionId: String,
        metadata: String,
    ) {
        patchJson("/api/v1/sessions/$sessionId/metadata", metadata)
    }

    suspend fun assignSessionProject(
        sessionId: String,
        projectId: String?,
    ): SessionInfo =
        client
            .patch("$baseUrl/api/v1/sessions/$sessionId/project") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                setBody(AssignSessionProjectRequest(projectId))
            }.requireSuccessfulJson("Assign session project")

    // ── Projects ─────────────────────────────────────────────────────────────

    suspend fun getProjects(): ProjectListResponse = authenticatedGet("$baseUrl/api/v1/projects")

    suspend fun createProject(request: CreateProjectRequest): ProjectInfo = authenticatedPost("$baseUrl/api/v1/projects", request)

    suspend fun updateProject(
        id: String,
        request: UpdateProjectRequest,
    ): ProjectInfo = authenticatedPut("$baseUrl/api/v1/projects/$id", request)

    suspend fun activateProject(id: String): ProjectInfo = authenticatedPut("$baseUrl/api/v1/projects/$id/active", emptyMap<String, String>())

    // ── Export ────────────────────────────────────────────────────────────────

    suspend fun exportSessionJson(sessionId: String): ConversationExport = authenticatedGet("$baseUrl/api/v1/sessions/$sessionId/export/json")

    suspend fun exportSessionMarkdown(sessionId: String): String =
        client
            .get("$baseUrl/api/v1/sessions/$sessionId/export/markdown") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.body()

    // ── Stats ────────────────────────────────────────────────────────────────

    suspend fun getStats(): StatsResponse = authenticatedGet("$baseUrl/api/v1/stats")

    // ── Feedback ─────────────────────────────────────────────────────────────

    suspend fun submitFeedback(request: FeedbackRequest) {
        authenticatedPost<Map<String, String>>("$baseUrl/api/v1/feedback", request)
    }

    // ── Agent Profiles ────────────────────────────────────────────────────────

    suspend fun getAgentProfiles(): List<AgentProfile> = authenticatedGet("$baseUrl/api/v1/agents")

    suspend fun getAgentProfile(id: String): AgentProfile = authenticatedGet("$baseUrl/api/v1/agents/$id")

    suspend fun createAgentProfile(request: AgentProfileRequest): AgentProfile = authenticatedPost("$baseUrl/api/v1/agents", request)

    suspend fun updateAgentProfile(
        id: String,
        request: AgentProfileRequest,
    ): AgentProfile =
        client
            .put("$baseUrl/api/v1/agents/$id") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                setBody(request)
            }.body()

    suspend fun deleteAgentProfile(id: String) {
        client.delete("$baseUrl/api/v1/agents/$id") {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }
    }

    suspend fun setDefaultProfile(id: String) {
        authenticatedPost<Map<String, String>>("$baseUrl/api/v1/agents/$id/set-default", mapOf<String, String>())
    }

    suspend fun reloadSettings(): String =
        client
            .post("$baseUrl/api/v1/settings/reload") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.requireSuccessfulBody("POST /api/v1/settings/reload")

    /** Legacy — for AgentMonitorScreen compatibility */
    suspend fun getAgents(): List<AgentStatusDto> =
        try {
            authenticatedGet("$baseUrl/api/v1/agents/status")
        } catch (
            _: Exception,
        ) {
            emptyList()
        }

    fun agentEventsStream(): Flow<AgentExecutionEvent> =
        flow {
            val wsUrl = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            val websocketClient =
                HttpClient {
                    install(WebSockets)
                    if (apiKey.isNotBlank()) {
                        defaultRequest { header(HttpHeaders.Authorization, "Bearer $apiKey") }
                    }
                }
            try {
                websocketClient.webSocket("$wsUrl/ws/agents") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val event = json.decodeFromString(AgentExecutionEvent.serializer(), frame.readText())
                            emit(event)
                        }
                    }
                }
            } finally {
                websocketClient.close()
            }
        }

    // ── GEPA ─────────────────────────────────────────────────────────────────

    /** Start a GEPA optimization job in the background. Returns the job ID. */
    suspend fun startGepaOptimization(request: GepaOptimizeRequest = GepaOptimizeRequest()): GepaStartResponse = authenticatedPost("$baseUrl/api/v1/gepa/optimize", request)

    /** Get the status of a GEPA job by ID. */
    suspend fun getGepaJobStatus(jobId: String): GepaJobResponse = authenticatedGet("$baseUrl/api/v1/gepa/jobs/$jobId")

    /** Get the currently running GEPA job, or null if none. */
    suspend fun getGepaCurrentJob(): GepaJobResponse? =
        try {
            val response = client.get("$baseUrl/api/v1/gepa/current") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                accept(ContentType.Application.Json)
            }
            if (response.status == io.ktor.http.HttpStatusCode.NoContent) {
                null
            } else {
                response.body<GepaJobResponse>()
            }
        } catch (e: Exception) {
            null
        }

    /** Get all GEPA jobs (history). */
    suspend fun getGepaJobs(): List<GepaJobResponse> = authenticatedGet("$baseUrl/api/v1/gepa/jobs")

    // ── Connectivity ─────────────────────────────────────────────────────────

    /**
     * Wraps [chatStream] with automatic reconnection using exponential backoff.
     * Retries on any exception up to [maxRetries] times (delays: 1 s, 2 s, 4 s …).
     */
    fun chatStreamWithRetry(
        sessionId: String,
        message: String,
        maxRetries: Int = 3,
    ): Flow<ChatEvent> =
        flow {
            var attempt = 0
            var success = false
            while (attempt <= maxRetries && !success) {
                try {
                    chatStream(sessionId, message).collect { event ->
                        emit(event)
                        if (event.type == "done") success = true
                    }
                    success = true
                } catch (e: Exception) {
                    attempt++
                    if (attempt > maxRetries) throw e
                    // Exponential backoff: 1s, 2s, 4s …
                    delay(1000L * (1L shl (attempt - 1)))
                }
            }
        }

    /** Quick health-check – returns `true` when the server's /health endpoint responds 200. */
    suspend fun isServerReachable(): Boolean =
        try {
            client.get("$baseUrl/health").status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.debug(e) { "Health check failed" }
            false
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun close() {
        a2aChatClient.close()
        client.close()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MCP — Model Context Protocol server management
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getMcpServers(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/mcp/servers")

    suspend fun addMcpServer(body: String): String = postJson("/api/v1/mcp/servers", body)

    suspend fun connectMcpServer(id: String): String = postJson("/api/v1/mcp/servers/$id/connect", "{}")

    suspend fun disconnectMcpServer(id: String): String = postJson("/api/v1/mcp/servers/$id/disconnect", "{}")

    suspend fun getMcpTools(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/mcp/tools")

    // ═════════════════════════════════════════════════════════════════════════
    // Tool Approval Gate
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getPendingApprovals(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/approval/pending")

    suspend fun respondToApproval(
        id: String,
        approved: Boolean,
    ): String =
        postJson(
            "/api/v1/approval/$id",
            buildJsonObject {
                put("approved", approved)
            }.toString(),
        )

    suspend fun getPendingMcpElicitations(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/approval/mcp/pending")

    suspend fun respondToMcpElicitation(
        id: String,
        action: String,
        content: kotlinx.serialization.json.JsonObject? = null,
    ): String =
        client
            .post("$baseUrl/api/v1/approval/mcp/$id") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("action", action)
                        content?.let { put("content", it) }
                    }.toString(),
                )
            }.requireSuccessfulBody("POST /api/v1/approval/mcp/$id")

    // Provider Choice (multi-provider AI capabilities)

    suspend fun getPendingProviderChoices(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/approval/providers/pending")

    suspend fun respondToProviderChoice(
        id: String,
        approved: Boolean,
        selectedProviderId: String? = null,
    ): String =
        postJson(
            "/api/v1/approval/providers/$id",
            buildJsonObject {
                put("approved", approved)
                if (selectedProviderId != null) {
                    put("selectedProviderId", selectedProviderId)
                }
            }.toString(),
        )

    // ═════════════════════════════════════════════════════════════════════════
    // Channels — messaging channel configuration
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getChannels(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/channels")

    suspend fun configureChannel(
        name: String,
        body: String,
    ): String = putJson("/api/v1/channels/$name/config", body)

    suspend fun testChannel(name: String): String = postJson("/api/v1/channels/$name/test", "{}")

    // ═════════════════════════════════════════════════════════════════════════
    // Config — environment variable management
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getConfigEnv(): kotlinx.serialization.json.JsonObject = getJson("/api/v1/config/env")

    suspend fun putConfigEnv(
        key: String,
        value: String,
    ): String =
        client
            .put("$baseUrl/api/v1/config/env/$key") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("value", value) }.toString())
            }.requireSuccessfulBody("PUT /api/v1/config/env/$key")

    suspend fun deleteConfigEnv(key: String): String =
        client
            .delete("$baseUrl/api/v1/config/env/$key") {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.requireSuccessfulBody("DELETE /api/v1/config/env/$key")

    private suspend fun HttpResponse.requireSuccessfulBody(operation: String): String {
        val responseBody = bodyAsText()
        if (status.value !in 200..299) {
            val detail = responseBody.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
            throw IllegalStateException("$operation failed (${status.value} ${status.description})$detail")
        }
        return responseBody
    }

    private suspend inline fun <reified T> HttpResponse.requireSuccessfulJson(operation: String): T {
        val responseBody = bodyAsText()
        if (!status.isSuccess()) {
            val serverMessage =
                runCatching { json.decodeFromString<ErrorResponse>(responseBody).error }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
            val fallback = responseBody.trim().take(1_000).takeIf(String::isNotBlank)
            val detail = serverMessage ?: fallback ?: "${status.value} ${status.description}"
            throw IllegalStateException("$operation failed: $detail")
        }
        try {
            return json.decodeFromString(responseBody)
        } catch (error: SerializationException) {
            throw IllegalStateException("$operation returned an invalid response.", error)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Voice — TTS synthesis
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Synthesize text to speech via the gateway TTS endpoint.
     * Returns the TtsResult with base64 PCM audio, or throws on error.
     */
    suspend fun postTtsSynthesize(
        text: String,
        provider: String? = null,
        voice: String? = null,
    ): kotlinx.serialization.json.JsonObject {
        val body = buildJsonObject {
            put("text", text)
            if (provider != null) put("provider", provider)
            if (voice != null) put("voice", voice)
        }
        // Serialize manually to avoid JsonLiteral serializer issue
        return authenticatedPost("$baseUrl/api/v1/tts/synthesize", body.toString())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Scheduler — scheduled task management
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getScheduledTasks(): List<dev.promethe.api.ScheduledTaskResponse> {
        val response: dev.promethe.api.ScheduledTaskListResponse = authenticatedGet("$baseUrl/api/v1/scheduler/tasks")
        return response.tasks
    }

    suspend fun createScheduledTask(request: dev.promethe.api.ScheduledTaskRequest): dev.promethe.api.ScheduledTaskResponse = authenticatedPost("$baseUrl/api/v1/scheduler/tasks", request)

    suspend fun updateScheduledTask(
        id: String,
        request: dev.promethe.api.ScheduledTaskRequest,
    ): dev.promethe.api.ScheduledTaskResponse =
        client
            .put("$baseUrl/api/v1/scheduler/tasks/$id") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                setBody(request)
            }.body()

    suspend fun deleteScheduledTask(id: String) {
        client.delete("$baseUrl/api/v1/scheduler/tasks/$id") {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }
    }

    suspend fun toggleScheduledTask(id: String): dev.promethe.api.ScheduledTaskResponse = authenticatedPost("$baseUrl/api/v1/scheduler/tasks/$id/toggle", Unit)

    // ═════════════════════════════════════════════════════════════════════════
    // Monitoring — System status & provider health
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getSystemStatus(): dev.promethe.api.SystemStatusResponse = authenticatedGet("$baseUrl/api/v1/status")

    suspend fun getProviderHealth(): dev.promethe.api.ProvidersResponse = authenticatedGet("$baseUrl/api/v1/status/providers")

    suspend fun getHealth(): dev.promethe.api.HealthResponse = authenticatedGet("$baseUrl/api/v1/health")

    // ═════════════════════════════════════════════════════════════════════════
    // Plugins & RAG
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getPlugins(): PluginListResponse = authenticatedGet("$baseUrl/api/v1/plugins")

    // ── Skills ───────────────────────────────────────────────────────────────

    suspend fun getSkills(): dev.promethe.api.SkillListResponse = authenticatedGet("$baseUrl/api/v1/skills")

    suspend fun getSkill(name: String): dev.promethe.api.SkillDto = authenticatedGet("$baseUrl/api/v1/skills/$name")

    suspend fun createSkill(
        name: String,
        content: String,
    ): dev.promethe.api.SkillDto = authenticatedPost("$baseUrl/api/v1/skills", dev.promethe.api.CreateSkillRequest(name = name, content = content))

    suspend fun updateSkill(
        name: String,
        content: String,
    ): dev.promethe.api.SkillDto = authenticatedPut("$baseUrl/api/v1/skills/$name", dev.promethe.api.UpdateSkillRequest(content = content))

    suspend fun updateSkillLifecycle(
        name: String,
        lifecycle: dev.promethe.api.SkillLifecycle,
    ): dev.promethe.api.SkillDto =
        authenticatedPut(
            "$baseUrl/api/v1/skills/$name/lifecycle",
            dev.promethe.api.UpdateSkillLifecycleRequest(lifecycle),
        )

    suspend fun deleteSkill(name: String) {
        client.delete("$baseUrl/api/v1/skills/$name") {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }
    }

    suspend fun getRagConfig(): RagConfig = authenticatedGet("$baseUrl/api/v1/rag/config")

    suspend fun updateRagConfig(config: RagConfig): RagConfig = authenticatedPut("$baseUrl/api/v1/rag/config", config)

    suspend fun getEmbeddingModels(): List<EmbeddingModelInfo> = authenticatedGet("$baseUrl/api/v1/rag/models")

    suspend fun testEmbedding(): RagTestResponse = authenticatedPost("$baseUrl/api/v1/rag/test-embedding", Unit)

    suspend fun getRagDocuments(): List<RagDocumentInfo> = authenticatedGet("$baseUrl/api/v1/rag/documents")

    suspend fun searchKnowledge(
        query: String,
        topK: Int = 5,
    ): RagSearchResponse = authenticatedPost("$baseUrl/api/v1/rag/search", RagSearchRequest(query, topK))
}
