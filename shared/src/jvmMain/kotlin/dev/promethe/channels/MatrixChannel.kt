package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * MatrixChannel — connects Prométhé to a Matrix homeserver via Client-Server API v3.
 *
 * Setup:
 *   1. Register a bot account on your homeserver (or use an existing one)
 *   2. Obtain an access token (via login or shared-secret registration)
 *   3. Join the bot to target rooms
 *
 * API Reference: https://spec.matrix.org/v1.11/client-server-api/
 *
 * This class handles:
 *   - Sending text and formatted messages to rooms
 *   - Sending typing indicators
 *   - Parsing /sync response events
 *   - Transaction-based unique event IDs
 */
class MatrixChannel(
    private val homeserverUrl: String, // e.g. "https://matrix.example.com"
    private val accessToken: String,
    private val httpClient: HttpClient,
) {
    private val json = PrometheJson
    private var txnCounter = System.currentTimeMillis()

    private fun nextTxnId(): String = "m${txnCounter++}"

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class MatrixRoomEvent(
        val type: String,
        val sender: String? = null,
        val event_id: String? = null,
        val room_id: String? = null,
        val content: MatrixEventContent? = null,
        val origin_server_ts: Long? = null,
    )

    @kotlinx.serialization.Serializable
    data class MatrixEventContent(
        val msgtype: String? = null,
        val body: String? = null,
        val format: String? = null,
        val formatted_body: String? = null,
    )

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse a Matrix room event from an Application Service transaction
     * or a forwarded event body.
     */
    fun parseEvent(body: String): MatrixRoomEvent? =
        try {
            json.decodeFromString<MatrixRoomEvent>(body)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Matrix room event" }
            null
        }

    /**
     * Parse events from a /sync response.
     * Returns list of (roomId, event) pairs for m.room.message events.
     */
    fun parseSyncResponse(body: String): List<Pair<String, MatrixRoomEvent>> {
        val results = mutableListOf<Pair<String, MatrixRoomEvent>>()
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val rooms = root["rooms"]?.jsonObject?.get("join")?.jsonObject ?: return results
            for ((roomId, roomData) in rooms) {
                val timeline = roomData.jsonObject["timeline"]?.jsonObject
                val events = timeline?.get("events")?.jsonArray ?: continue
                for (eventEl in events) {
                    val event = json.decodeFromJsonElement<MatrixRoomEvent>(eventEl)
                    if (event.type == "m.room.message") {
                        results.add(roomId to event)
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Matrix /sync response" }
            // Malformed sync response
        }
        return results
    }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send a text message to a Matrix room.
     *
     * @param roomId  Fully-qualified room ID, e.g. "!abc123:matrix.org"
     * @param text    Plain-text message body
     * @param html    Optional HTML-formatted body (Matrix "org.matrix.custom.html" format)
     */
    suspend fun sendMessage(
        roomId: String,
        text: String,
        html: String? = null,
    ): Boolean {
        val txnId = nextTxnId()
        val encodedRoomId = roomId.encodeURLParameter()
        val url = "$homeserverUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId"

        val body = buildJsonObject {
            put("msgtype", "m.text")
            put("body", text)
            if (html != null) {
                put("format", "org.matrix.custom.html")
                put("formatted_body", html)
            }
        }

        val response = httpClient.put(url) {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a notice (bot-style message that doesn't trigger notifications for most clients).
     */
    suspend fun sendNotice(
        roomId: String,
        text: String,
    ): Boolean {
        val txnId = nextTxnId()
        val encodedRoomId = roomId.encodeURLParameter()
        val url = "$homeserverUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId"

        val body = buildJsonObject {
            put("msgtype", "m.notice")
            put("body", text)
        }

        val response = httpClient.put(url) {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a typing indicator to a room.
     *
     * @param roomId  Target room
     * @param typing  true to start typing, false to stop
     * @param timeoutMs  How long the typing indicator lasts (default 30s)
     */
    suspend fun sendTypingAction(
        roomId: String,
        typing: Boolean = true,
        timeoutMs: Long = 30_000,
    ) {
        val encodedRoomId = roomId.encodeURLParameter()
        // We need the user ID; use a well-known endpoint or extract from token.
        // The typing endpoint requires the user ID in the path.
        val userId = getWhoAmI() ?: return
        val encodedUserId = userId.encodeURLParameter()
        val url = "$homeserverUrl/_matrix/client/v3/rooms/$encodedRoomId/typing/$encodedUserId"

        httpClient.put(url) {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("typing", typing)
                    if (typing) put("timeout", timeoutMs)
                }.toString(),
            )
        }
    }

    // ── Utility ─────────────────────────────────────────────

    /**
     * Perform a /sync request (long-poll for new events).
     *
     * @param since   The next_batch token from a previous sync (null for initial sync)
     * @param timeoutMs  Long-poll timeout in milliseconds
     * @return Raw JSON response body
     */
    suspend fun sync(
        since: String? = null,
        timeoutMs: Long = 30_000,
    ): String {
        val response = httpClient.get("$homeserverUrl/_matrix/client/v3/sync") {
            header("Authorization", "Bearer $accessToken")
            parameter("timeout", timeoutMs)
            since?.let { parameter("since", it) }
            // Minimal filter: only room messages
            parameter("filter", """{"room":{"timeline":{"types":["m.room.message"]}}}""")
        }
        return response.bodyAsText()
    }

    /**
     * Get the authenticated user's Matrix ID.
     */
    suspend fun getWhoAmI(): String? =
        try {
            val response = httpClient.get("$homeserverUrl/_matrix/client/v3/account/whoami") {
                header("Authorization", "Bearer $accessToken")
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["user_id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get Matrix whoami" }
            null
        }

    /**
     * Join a room by ID or alias.
     */
    suspend fun joinRoom(roomIdOrAlias: String): Boolean {
        val encoded = roomIdOrAlias.encodeURLParameter()
        val response = httpClient.post("$homeserverUrl/_matrix/client/v3/join/$encoded") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return response.status.value in 200..299
    }
}
