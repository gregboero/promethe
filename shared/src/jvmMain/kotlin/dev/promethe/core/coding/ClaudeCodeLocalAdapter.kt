package dev.promethe.core.coding

import dev.promethe.core.ApprovalGate
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ClaudeCodeLocalAdapter(
    private val workspace: Path,
    private val approvalGate: ApprovalGate,
    private val processFactory: TrustedLocalAgentProcessFactory = JvmTrustedLocalAgentProcessFactory,
) : LocalCodingAgentAdapter {
    override val kind: LocalCodingAgentKind = LocalCodingAgentKind.CLAUDE_CODE
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        executable: TrustedExecutable,
        request: LocalCodingAgentRequest,
    ): LocalCodingAgentResult =
        withTimeout(EXECUTION_TIMEOUT_MILLIS) {
            val relay =
                if (request.accessMode == LocalCodingAccessMode.WORKSPACE_WRITE) {
                    ClaudeApprovalMcpRelay(approvalGate, request.prometheSessionId)
                } else {
                    null
                }
            val mcpConfig = relay?.createMcpConfig()
            val arguments =
                buildList {
                    addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
                    addAll(listOf("--max-turns", "30"))
                    addAll(listOf("--setting-sources", ""))
                    add("--no-chrome")
                    if (request.accessMode == LocalCodingAccessMode.READ_ONLY) {
                        addAll(listOf("--permission-mode", "plan"))
                    } else {
                        addAll(listOf("--permission-mode", "default"))
                        add("--strict-mcp-config")
                        addAll(listOf("--mcp-config", requireNotNull(mcpConfig).toString()))
                        addAll(listOf("--permission-prompt-tool", "mcp__promethe_approval__approve"))
                    }
                    request.externalSessionId?.takeIf(String::isNotBlank)?.let { addAll(listOf("--resume", it)) }
                }
            val process =
                processFactory.start(
                    executable,
                    arguments,
                    workspace,
                    mapOf("CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1"),
                )
            try {
                process.sendLine(
                    buildJsonObject {
                        put("type", "user")
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", request.task)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }.toString(),
                )
                val result = awaitResult(process)
                LocalCodingAgentResult(
                    summary = result.first.ifBlank { "Claude Code completed the task without a text summary." },
                    externalSessionId = result.second,
                )
            } finally {
                process.cancel()
                relay?.close()
                mcpConfig?.let(Files::deleteIfExists)
            }
        }

    private suspend fun awaitResult(process: TrustedLocalAgentProcess): Pair<String, String?> {
        val assistantText = StringBuilder()
        while (true) {
            val line = process.readLine(STREAM_READ_TIMEOUT_MILLIS)
                ?: error("Claude Code stopped responding${process.stderr().takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "assistant" -> {
                    val content = event["message"]?.jsonObject?.get("content")?.jsonArray.orEmpty()
                    content.forEach { block ->
                        val item = block.jsonObject
                        if (item["type"]?.jsonPrimitive?.contentOrNull == "text") {
                            val text = item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (text.isNotBlank()) assistantText.appendLine(text)
                        }
                    }
                }

                "result" -> {
                    val error = event["is_error"]?.jsonPrimitive?.contentOrNull == "true"
                    val result = event["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (error) error(result.ifBlank { "Claude Code task failed" })
                    val sessionId = event["session_id"]?.jsonPrimitive?.contentOrNull
                    return (result.ifBlank { assistantText.toString() }.trim()) to sessionId
                }
            }
        }
    }
}

private const val STREAM_READ_TIMEOUT_MILLIS = 120_000L
private const val EXECUTION_TIMEOUT_MILLIS = 30 * 60 * 1_000L
