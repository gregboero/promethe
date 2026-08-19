package dev.promethe.gateway.mcp

import dev.promethe.api.PrometheVersion
import dev.promethe.core.McpProtocol
import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolRegistry
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Ktor routing extension that adds MCP server endpoints to the gateway.
 *
 * Implements a dual-era MCP Streamable HTTP endpoint:
 * modern stateless 2026-07-28 requests and legacy 2025-11-25 requests.
 *
 * Endpoints:
 * - `GET  /.well-known/mcp` — Server Card for discovery (capabilities, endpoint URL)
 * - `POST /mcp` — Full JSON-RPC 2.0 endpoint for MCP protocol communication.
 *   Handles modern `server/discover`, shared tool methods, and the legacy lifecycle.
 * - `GET  /mcp/tools` — Simple REST endpoint listing all registered tools (for debugging/UI).
 *
 * Compliance features:
 * - Modern `_meta`, `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` validation
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
                    put("protocolVersion", McpProtocol.MODERN_VERSION)
                    putJsonArray("supportedVersions") {
                        McpProtocol.supportedVersions.forEach { add(it) }
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                    put("endpoint", "/mcp")
                },
            ),
            contentType = ContentType.Application.Json,
        )
    }

    // ── JSON-RPC 2.0 endpoint (full MCP protocol) ────────────────
    post("/mcp") {
        // ── Origin validation ────────────────────────────────
        val origin = call.request.header("Origin")
        if (origin != null && origin !in allowedOrigins) {
            logger.warn { "Rejected MCP request from invalid origin: $origin" }
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        val body = call.receive<String>()

        // ── Reject batch arrays ──────────────────────────────
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
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }

        val validation =
            validateHttpRequest(
                request = request,
                protocolHeader = call.request.header(McpProtocol.PROTOCOL_VERSION_HEADER),
                methodHeader = call.request.header(McpProtocol.METHOD_HEADER),
                nameHeader = call.request.header(McpProtocol.NAME_HEADER),
            )
        if (validation.error != null) {
            logger.warn { "Rejected invalid MCP request: ${validation.error["error"]}" }
            call.respondText(
                text = json.encodeToString(JsonObject.serializer(), validation.error),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }

        val sessionId = call.attributes.getOrNull(AuthMiddleware.SessionIdKey) ?: "local-api-key"
        val response = exporter.dispatch(request, sessionId, validation.protocolVersion)

        if (response != null) {
            call.respondText(
                text = json.encodeToString(JsonObject.serializer(), response),
                contentType = ContentType.Application.Json,
            )
        } else {
            // Notifications return 202 Accepted with an empty body.
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

private data class McpHttpValidation(
    val protocolVersion: String,
    val error: JsonObject? = null,
)

private fun validateHttpRequest(
    request: JsonObject,
    protocolHeader: String?,
    methodHeader: String?,
    nameHeader: String?,
): McpHttpValidation {
    val id = request["id"] ?: JsonNull
    val method = (request["method"] as? JsonPrimitive)?.content.orEmpty()
    val metadataVersion = McpProtocol.requestedVersion(request)
    val requestedVersion = metadataVersion ?: protocolHeader

    val unsupported = listOfNotNull(protocolHeader, metadataVersion).firstOrNull { it !in McpProtocol.supportedVersions }
    if (unsupported != null) {
        return McpHttpValidation(
            protocolVersion = unsupported,
            error = unsupportedVersionError(id, unsupported),
        )
    }
    if (protocolHeader != null && metadataVersion != null && protocolHeader != metadataVersion) {
        return McpHttpValidation(
            protocolVersion = metadataVersion,
            error = headerMismatchError(id, "Protocol metadata and header must match"),
        )
    }

    val modern = requestedVersion == McpProtocol.MODERN_VERSION || method == "server/discover"
    if (!modern) {
        return McpHttpValidation(protocolVersion = requestedVersion ?: McpProtocol.LEGACY_VERSION)
    }

    if (protocolHeader != McpProtocol.MODERN_VERSION || metadataVersion != McpProtocol.MODERN_VERSION) {
        return McpHttpValidation(
            protocolVersion = McpProtocol.MODERN_VERSION,
            error = headerMismatchError(id, "Modern requests require matching protocol metadata and header"),
        )
    }
    if (methodHeader != method) {
        return McpHttpValidation(
            protocolVersion = McpProtocol.MODERN_VERSION,
            error = headerMismatchError(id, "Mcp-Method must match the JSON-RPC method"),
        )
    }

    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
    val metadata = params["_meta"] as? JsonObject
    if (metadata?.get(McpProtocol.CLIENT_CAPABILITIES_META) !is JsonObject) {
        return McpHttpValidation(
            protocolVersion = McpProtocol.MODERN_VERSION,
            error = invalidParamsError(id, "Missing per-request client capabilities metadata"),
        )
    }

    val expectedName = routingName(method, params)
    if (nameHeader != expectedName) {
        return McpHttpValidation(
            protocolVersion = McpProtocol.MODERN_VERSION,
            error = headerMismatchError(id, "Mcp-Name must match the routed request parameter"),
        )
    }
    return McpHttpValidation(protocolVersion = McpProtocol.MODERN_VERSION)
}

private fun routingName(
    method: String,
    params: JsonObject,
): String? =
    when (method) {
        "tools/call" -> (params["name"] as? JsonPrimitive)?.content
        "resources/read" -> (params["uri"] as? JsonPrimitive)?.content
        "prompts/get" -> (params["name"] as? JsonPrimitive)?.content
        else -> null
    }

private fun unsupportedVersionError(
    id: JsonElement,
    requested: String,
): JsonObject =
    jsonRpcError(
        id = id,
        code = -32022,
        message = "Unsupported protocol version",
        data =
            buildJsonObject {
                putJsonArray("supported") {
                    McpProtocol.supportedVersions.forEach { add(it) }
                }
                put("requested", requested)
            },
    )

private fun headerMismatchError(
    id: JsonElement,
    message: String,
): JsonObject = jsonRpcError(id, -32020, message)

private fun invalidParamsError(
    id: JsonElement,
    message: String,
): JsonObject = jsonRpcError(id, -32602, message)

private fun jsonRpcError(
    id: JsonElement,
    code: Int,
    message: String,
    data: JsonObject? = null,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            data?.let { put("data", it) }
        }
    }
