package dev.promethe.gateway.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * McpStdioServerMode — runs Promethe as an MCP server over stdio.
 *
 * This enables integration with IDEs (VS Code, Cursor, etc.) and any MCP client
 * that uses the stdio transport. The protocol is JSON-RPC 2.0, one message per line.
 *
 * Architecture:
 *   stdin  → [JSON-RPC request] → McpToolExporter.dispatch() → [JSON-RPC response] → stdout
 *   stderr → diagnostic logs (never pollute stdout)
 *
 * Lifecycle:
 *   1. Client launches the Promethe process with `--mcp-stdio` flag
 *   2. Server reads JSON-RPC messages from stdin, one per line
 *   3. Each message is dispatched through [McpToolExporter]
 *   4. Responses are written to stdout, one per line
 *   5. On EOF or `shutdown` method, the server exits cleanly
 *
 * @param toolExporter the shared exporter that converts ToolRegistry tools into MCP responses
 */
class McpStdioServerMode(
    private val toolExporter: McpToolExporter,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Start the stdio MCP server loop.
     *
     * This method blocks the calling coroutine until stdin is closed (EOF)
     * or a `shutdown` JSON-RPC method is received.
     */
    suspend fun run() {
        logger.info { "Promethe MCP server started on stdio" }

        val reader: BufferedReader = System.`in`.bufferedReader()
        val writer: BufferedWriter = System.out.bufferedWriter()

        try {
            while (currentCoroutineContext().isActive) {
                // Read one line from stdin (blocking I/O on the IO dispatcher)
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                }

                // EOF — client closed stdin
                if (line == null) {
                    logger.info { "EOF received, shutting down" }
                    break
                }

                // Skip empty lines
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // Parse and dispatch
                val response = handleLine(trimmed)

                // Write response (null means it was a notification — no response needed)
                if (response != null) {
                    withContext(Dispatchers.IO) {
                        writer.write(response.toString())
                        writer.newLine()
                        writer.flush()
                    }
                }

                // Check if shutdown was requested
                if (isShutdownRequest(trimmed)) {
                    logger.info { "Shutdown requested, exiting" }
                    break
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Fatal error in MCP stdio loop" }
        } finally {
            logger.info { "MCP stdio server stopped" }
        }
    }

    /**
     * Parse a single line as JSON-RPC 2.0 and dispatch it through the exporter.
     * Returns the JSON-RPC response, or null for notifications.
     * On parse errors, returns a JSON-RPC error with id=null.
     */
    private suspend fun handleLine(line: String): JsonObject? {
        val request: JsonObject = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            logger.error(e) { "Malformed JSON received" }
            return buildParseError()
        }

        val method = request["method"]?.jsonPrimitive?.content ?: "unknown"
        val id = request["id"]
        logger.debug { "← $method (id=$id)" }

        val response = toolExporter.dispatch(request)

        if (response != null) {
            logger.debug { "→ response (id=$id)" }
        } else {
            logger.debug { "notification, no response" }
        }

        return response
    }

    /**
     * Check whether a raw JSON line is a shutdown request.
     * This avoids re-parsing; we just do a simple string check.
     */
    private fun isShutdownRequest(line: String): Boolean = line.contains("\"method\"") && line.contains("\"shutdown\"")

    /**
     * Build a JSON-RPC parse error response (code -32700).
     * Used when the incoming line is not valid JSON.
     */
    private fun buildParseError(): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonNull)
            put(
                "error",
                buildJsonObject {
                    put("code", -32700)
                    put("message", "Parse error: invalid JSON")
                },
            )
        }
}
