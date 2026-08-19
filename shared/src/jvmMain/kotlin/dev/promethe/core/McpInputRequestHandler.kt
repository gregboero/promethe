package dev.promethe.core

import kotlinx.serialization.json.JsonObject

/** Resolves one server-to-client request embedded in a modern MCP MRTR result. */
fun interface McpInputRequestHandler {
    suspend fun fulfill(
        requestKey: String,
        request: JsonObject,
    ): JsonObject
}

/** Raised when a modern MCP server needs input that this client cannot safely provide. */
class McpInputRequiredException(
    val inputRequiredResult: JsonObject,
    reason: String = "MCP server requires additional client input",
) : IllegalStateException(reason)
