package dev.promethe.gateway.mcp

import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolRegistry
import dev.promethe.api.PrometheVersion
import dev.promethe.gateway.AuthMiddleware
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Ktor routing extension that adds MCP server endpoints to the gateway.
 *
 * Implements the **2025-11-05 MCP specification** (Streamable HTTP transport):
 *
 * Endpoints:
 * - `GET  /.well-known/mcp` — Server Card for discovery (capabilities, endpoint URL)
 * - `POST /mcp` — Full JSON-RPC 2.0 endpoint for MCP protocol communication.
 *   Handles `initialize`, `tools/list`, `tools/call`, `tasks/get`, `tasks/cancel`,
 *   `notifications/initialized`, and `shutdown`.
 * - `GET  /mcp/tools` — Simple REST endpoint listing all registered tools (for debugging/UI).
 *
 * Compliance features:
 * - `MCP-Protocol-Version` header validation (returns 400 if unsupported)
 * - `Origin` header validation (returns 403 if invalid)
 * - `202 Accepted` for notifications (no response body)
 * - Batch JSON-RPC arrays rejected with 400
 *
 * All JSON-RPC dispatch is delegated to [McpToolExporter].
 */
fun Route.mcpServerRoutes(
    allowedOrigins: Set<String> = emptySet(),
    secureToolExecutor: SecureToolExecutor? = null,
) {
    val exporter = McpToolExporter(secureToolExecutor = secureToolExecutor)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── Server Card (discovery endpoint) ─────────────────────
    get("/.well-known/mcp") {
        call.respondText(
            text = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("name", "promethe")
                    put("version", PrometheVersion.CURRENT)
                    put("protocolVersion", McpToolExporter.PROTOCOL_VERSION)
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                        putJsonObject("tasks") {}
                    }
                    put("endpoint", "/mcp")
                },
            ),
            contentType = ContentType.Application.Json,
        )
    }

    // ── JSON-RPC 2.0 endpoint (full MCP protocol) ────────────────
    post("/mcp") {
        // ── Origin validation (2025-11-05 spec) ──────────────
        val origin = call.request.header("Origin")
        if (origin != null && origin !in allowedOrigins) {
            logger.warn { "Rejected MCP request from invalid origin: $origin" }
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        // ── Protocol version validation ──────────────────────
        val protocolVersion = call.request.header("MCP-Protocol-Version")
        if (protocolVersion != null && protocolVersion != McpToolExporter.PROTOCOL_VERSION) {
            logger.warn { "Unsupported MCP protocol version: $protocolVersion" }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "Unsupported protocol version: $protocolVersion. Expected: ${McpToolExporter.PROTOCOL_VERSION}",
                ),
            )
            return@post
        }

        val body = call.receive<String>()

        // ── Reject batch arrays (2025-11-05 spec) ────────────
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            logger.warn { "Rejected batch JSON-RPC request (arrays not supported)" }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "Batch JSON-RPC requests are not supported",
                ),
            )
            return@post
        }

        val request = try {
            json.decodeFromString(JsonObject.serializer(), body)
        } catch (e: Exception) {
            val errorResponse = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", null as String?)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    },
                )
            }
            call.respondText(
                text = json.encodeToString(JsonObject.serializer(), errorResponse),
                contentType = ContentType.Application.Json,
            )
            return@post
        }

        val sessionId = call.attributes.getOrNull(AuthMiddleware.SessionIdKey) ?: "local-api-key"
        val response = exporter.dispatch(request, sessionId)

        if (response != null) {
            call.respondText(
                text = json.encodeToString(JsonObject.serializer(), response),
                contentType = ContentType.Application.Json,
            )
        } else {
            // Notifications return 202 Accepted with empty body (2025-11-05 spec)
            call.respond(HttpStatusCode.Accepted)
        }
    }

    // ── Simple REST endpoint for debugging/UI ────────────────────
    get("/mcp/tools") {
        val tools = ToolRegistry.listTools()
        val result = buildJsonObject {
            put("count", tools.size)
            put(
                "tools",
                buildJsonArray {
                    for (tool in tools) {
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.descriptor.description)
                            },
                        )
                    }
                },
            )
        }
        call.respondText(
            text = json.encodeToString(JsonObject.serializer(), result),
            contentType = ContentType.Application.Json,
        )
    }
}
