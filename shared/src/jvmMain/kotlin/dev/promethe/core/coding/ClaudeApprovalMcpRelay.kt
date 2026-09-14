package dev.promethe.core.coding

import dev.promethe.core.ApprovalGate
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ClaudeApprovalMcpRelay(
    private val approvalGate: ApprovalGate,
    private val sessionId: String,
) : AutoCloseable {
    private val token = randomToken()
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)
    private val worker =
        thread(name = "claude-approval-relay", isDaemon = true) {
            while (!closed.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { handle(socket) }
            }
        }

    fun createMcpConfig(): Path {
        val java =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").contains("win", ignoreCase = true)) "java.exe" else "java",
            ).toString()
        val config =
            buildJsonObject {
                put(
                    "mcpServers",
                    buildJsonObject {
                        put(
                            "promethe_approval",
                            buildJsonObject {
                                put("type", "stdio")
                                put("command", java)
                                put(
                                    "args",
                                    buildJsonArray {
                                        add(JsonPrimitive("-cp"))
                                        add(JsonPrimitive(System.getProperty("java.class.path")))
                                        add(JsonPrimitive(ClaudeApprovalMcpMain::class.java.name))
                                    },
                                )
                                put(
                                    "env",
                                    buildJsonObject {
                                        put(RELAY_PORT_ENV, server.localPort.toString())
                                        put(RELAY_TOKEN_ENV, token)
                                    },
                                )
                            },
                        )
                    },
                )
            }
        val file = Files.createTempFile("promethe-claude-mcp-", ".json")
        Files.writeString(file, config.toString())
        file.toFile().deleteOnExit()
        return file
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.close()
            worker.interrupt()
        }
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = RELAY_IO_TIMEOUT_MILLIS
            val line = connection.getInputStream().bufferedReader().readLine() ?: return
            val request = JSON.parseToJsonElement(line).jsonObject
            val suppliedToken = request["token"]?.jsonPrimitive?.contentOrNull
            val response =
                if (!SecureRandomToken.equals(token, suppliedToken)) {
                    buildJsonObject {
                        put("allowed", false)
                        put("reason", "Invalid approval relay token")
                    }
                } else {
                    val arguments = request["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                    val requestedTool = arguments["tool_name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val result = runBlocking {
                        approvalGate.checkMandatory("claude_code_action:$requestedTool", arguments.toString(), sessionId)
                    }
                    buildJsonObject {
                        put("allowed", result.allowed)
                        put("reason", result.reason)
                    }
                }
            connection.getOutputStream().bufferedWriter().use { writer ->
                writer.write(response.toString())
                writer.newLine()
            }
        }
    }
}

/** Minimal stdio MCP server used only as Claude Code's permission prompt tool. */
object ClaudeApprovalMcpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv(RELAY_PORT_ENV)?.toIntOrNull() ?: return
        val token = System.getenv(RELAY_TOKEN_ENV) ?: return
        val input = System.`in`.bufferedReader()
        val output = System.out.bufferedWriter()
        input.lineSequence().forEach { line ->
            val request = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            val id = request["id"]
            val method = request["method"]?.jsonPrimitive?.contentOrNull
            if (id == null) return@forEach
            val result =
                when (method) {
                    "initialize" -> initializeResult()
                    "tools/list" -> toolsListResult()
                    "tools/call" -> callApproval(port, token, request["params"]?.jsonObject)
                    else -> JsonNull
                }
            val response =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                }
            output.write(response.toString())
            output.newLine()
            output.flush()
        }
    }

    private fun initializeResult(): JsonObject =
        buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "promethe-approval")
                    put("version", "1.0.0")
                },
            )
        }

    private fun toolsListResult(): JsonObject =
        buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "approve")
                            put("description", "Request local Promethe owner approval for a Claude Code action.")
                            put(
                                "inputSchema",
                                buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", true)
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun callApproval(
        port: Int,
        token: String,
        params: JsonObject?,
    ): JsonObject {
        val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())
        val relayResponse =
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = RELAY_IO_TIMEOUT_MILLIS
                val request =
                    buildJsonObject {
                        put("token", token)
                        put("arguments", arguments)
                    }
                socket.getOutputStream().bufferedWriter().apply {
                    write(request.toString())
                    newLine()
                    flush()
                }
                val line = socket.getInputStream().bufferedReader().readLine()
                JSON.parseToJsonElement(line).jsonObject
            }
        val allowed = relayResponse["allowed"]?.jsonPrimitive?.contentOrNull == "true"
        val permissionResult =
            if (allowed) {
                buildJsonObject {
                    put("behavior", "allow")
                    put("updatedInput", arguments["input"] ?: JsonObject(emptyMap()))
                }
            } else {
                buildJsonObject {
                    put("behavior", "deny")
                    put("message", relayResponse["reason"]?.jsonPrimitive?.contentOrNull ?: "Approval denied")
                }
            }
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", permissionResult.toString())
                        },
                    )
                },
            )
        }
    }
}

private object SecureRandomToken {
    fun equals(
        expected: String,
        actual: String?,
    ): Boolean =
        actual != null &&
            java.security.MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}

private fun randomToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private val JSON = Json { ignoreUnknownKeys = true }
private const val RELAY_PORT_ENV = "PROMETHE_APPROVAL_RELAY_PORT"
private const val RELAY_TOKEN_ENV = "PROMETHE_APPROVAL_RELAY_TOKEN"
private const val RELAY_IO_TIMEOUT_MILLIS = 5_000
