package dev.promethe.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wire-level constants shared by Promethe's MCP client and server roles. */
object McpProtocol {
    const val MODERN_VERSION = "2026-07-28"
    const val LEGACY_VERSION = "2025-11-25"

    const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    const val METHOD_HEADER = "Mcp-Method"
    const val NAME_HEADER = "Mcp-Name"
    const val SESSION_HEADER = "Mcp-Session-Id"

    const val PROTOCOL_VERSION_META = "io.modelcontextprotocol/protocolVersion"
    const val CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo"
    const val CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities"
    const val SERVER_INFO_META = "io.modelcontextprotocol/serverInfo"

    val supportedVersions: List<String> = listOf(MODERN_VERSION, LEGACY_VERSION)

    fun requestedVersion(request: JsonObject): String? =
        (request["params"] as? JsonObject)
            ?.get("_meta")
            ?.let { it as? JsonObject }
            ?.get(PROTOCOL_VERSION_META)
            ?.let { it as? JsonPrimitive }
            ?.content
}
