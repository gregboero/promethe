package dev.promethe.core.coding

import dev.promethe.core.ApprovalGate
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CodexLocalAdapter(
    private val workspace: Path,
    private val approvalGate: ApprovalGate,
    private val processFactory: TrustedLocalAgentProcessFactory = JvmTrustedLocalAgentProcessFactory,
) : LocalCodingAgentAdapter {
    override val kind: LocalCodingAgentKind = LocalCodingAgentKind.CODEX
    private val ids = AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun authenticationStatus(executable: TrustedExecutable): dev.promethe.api.CapabilityAuthentication =
        withTimeout(AUTH_PROBE_TIMEOUT_MILLIS) {
            val process = processFactory.start(executable, listOf("app-server"), workspace, emptyMap())
            try {
                initialize(process, "local-detection")
                awaitResponse(
                    process,
                    sendRequest(
                        process,
                        "permissionProfile/list",
                        buildJsonObject { put("cwd", workspace.toString()) },
                    ),
                    "local-detection",
                )
                val response =
                    awaitResponse(
                        process,
                        sendRequest(process, "account/read", buildJsonObject { put("refreshToken", false) }),
                        "local-detection",
                    )
                val result = response["result"]?.jsonObject
                val account = result?.get("account")
                val requiresAuthentication = result?.get("requiresOpenaiAuth")?.jsonPrimitive?.booleanOrNull == true
                when {
                    account != null && account !is JsonNull -> dev.promethe.api.CapabilityAuthentication.AUTHENTICATED
                    requiresAuthentication -> dev.promethe.api.CapabilityAuthentication.SIGNED_OUT
                    else -> dev.promethe.api.CapabilityAuthentication.AUTHENTICATED
                }
            } finally {
                process.cancel()
            }
        }

    override suspend fun execute(
        executable: TrustedExecutable,
        request: LocalCodingAgentRequest,
    ): LocalCodingAgentResult =
        withTimeout(EXECUTION_TIMEOUT_MILLIS) {
            val process = processFactory.start(executable, listOf("app-server"), workspace, emptyMap())
            try {
                initialize(process, request.prometheSessionId)
                val isolationConfig = readIsolationConfig(process, request.prometheSessionId)
                val threadId = startOrResumeThread(process, request, isolationConfig)
                val turnId = startTurn(process, threadId, request)
                val finalText = awaitTurn(process, request.prometheSessionId, threadId, turnId)
                LocalCodingAgentResult(
                    summary = finalText.ifBlank { "Codex completed the task without a text summary." },
                    externalSessionId = threadId,
                )
            } finally {
                process.cancel()
            }
        }

    private suspend fun initialize(
        process: TrustedLocalAgentProcess,
        sessionId: String,
    ) {
        val id = sendRequest(
            process,
            "initialize",
            buildJsonObject {
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "promethe")
                        put("title", "Promethe")
                        put("version", "1.0.0")
                    },
                )
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
            },
        )
        awaitResponse(process, id, sessionId)
        process.sendLine(buildJsonObject { put("method", "initialized") }.toString())
    }

    private suspend fun startOrResumeThread(
        process: TrustedLocalAgentProcess,
        request: LocalCodingAgentRequest,
        isolationConfig: JsonObject,
    ): String {
        val method = if (request.externalSessionId.isNullOrBlank()) "thread/start" else "thread/resume"
        val params = buildJsonObject {
            if (method == "thread/resume") {
                put("threadId", request.externalSessionId)
            }
            put("cwd", workspace.toString())
            put("approvalPolicy", "on-request")
            put("permissions", request.permissionProfile())
            put("config", isolationConfig)
            put("runtimeWorkspaceRoots", buildJsonArray { add(JsonPrimitive(workspace.toString())) })
            if (method == "thread/start") {
                put("dynamicTools", buildJsonArray {})
                put("environments", buildJsonArray {})
            }
        }
        val response = awaitResponse(process, sendRequest(process, method, params), request.prometheSessionId)
        return response.path("result", "thread", "id")
            ?: response.path("result", "threadId")
            ?: request.externalSessionId
            ?: error("Codex app-server did not return a thread id")
    }

    private suspend fun startTurn(
        process: TrustedLocalAgentProcess,
        threadId: String,
        request: LocalCodingAgentRequest,
    ): String {
        val params =
            buildJsonObject {
                put("threadId", threadId)
                put("cwd", workspace.toString())
                put("approvalPolicy", "on-request")
                put("permissions", request.permissionProfile())
                put("runtimeWorkspaceRoots", buildJsonArray { add(JsonPrimitive(workspace.toString())) })
                put("environments", buildJsonArray {})
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", request.task)
                            },
                        )
                    },
                )
            }
        val response = awaitResponse(process, sendRequest(process, "turn/start", params), threadId)
        return response.path("result", "turn", "id")
            ?: response.path("result", "turnId")
            ?: error("Codex app-server did not return a turn id")
    }

    private suspend fun awaitTurn(
        process: TrustedLocalAgentProcess,
        sessionId: String,
        threadId: String,
        turnId: String,
    ): String {
        val output = StringBuilder()
        while (true) {
            val message = readMessage(process)
            if (message["id"] != null && message["method"] != null) {
                handleServerRequest(process, message, sessionId)
                continue
            }
            when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "item/completed" -> {
                    if (!message.belongsTo(threadId, turnId)) continue
                    val item = message["params"]?.jsonObject?.get("item")?.jsonObject
                    if (item?.get("type")?.jsonPrimitive?.contentOrNull == "agentMessage") {
                        val text = item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isNotBlank()) {
                            if (output.isNotEmpty()) output.appendLine()
                            output.append(text)
                        }
                    }
                }

                "turn/completed" -> {
                    if (!message.belongsTo(threadId, turnId)) continue
                    val status = message.path("params", "turn", "status")
                    if (status != "completed") {
                        error(
                            message.path("params", "turn", "error", "message")
                                ?: "Codex turn ended with status ${status ?: "unknown"}",
                        )
                    }
                    return output.toString().trim()
                }
            }
        }
    }

    private suspend fun readIsolationConfig(
        process: TrustedLocalAgentProcess,
        sessionId: String,
    ): JsonObject {
        val response =
            awaitResponse(
                process,
                sendRequest(
                    process,
                    "config/read",
                    buildJsonObject {
                        put("cwd", workspace.toString())
                        put("includeLayers", false)
                    },
                ),
                sessionId,
            )
        val effective = response["result"]?.jsonObject?.get("config") as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("permissions", permissionProfiles())
            put("mcp_servers", disabledEntries(effective["mcp_servers"] as? JsonObject))
            put("plugins", disabledEntries(effective["plugins"] as? JsonObject))
            put("apps", disabledEntries(effective["apps"] as? JsonObject))
            put(
                "features",
                buildJsonObject {
                    put("remote_plugin", false)
                    put("skill_mcp_dependency_install", false)
                },
            )
        }
    }

    private fun permissionProfiles(): JsonObject =
        buildJsonObject {
            put(
                READ_ONLY_PROFILE,
                permissionProfile(
                    workspaceAccess = "read",
                    protectedMetadataAccess = "read",
                    extends = null,
                ),
            )
            put(
                WORKSPACE_WRITE_PROFILE,
                permissionProfile(
                    workspaceAccess = "write",
                    protectedMetadataAccess = "read",
                    extends = ":workspace",
                ),
            )
        }

    private fun permissionProfile(
        workspaceAccess: String,
        protectedMetadataAccess: String,
        extends: String?,
    ): JsonObject =
        buildJsonObject {
            extends?.let { put("extends", it) }
            put(
                "filesystem",
                buildJsonObject {
                    put(":root", "deny")
                    put(":minimal", "read")
                    put(
                        ":workspace_roots",
                        buildJsonObject {
                            put(".", workspaceAccess)
                            put(".git", protectedMetadataAccess)
                            put(".promethe", "deny")
                            put(".codex", "deny")
                            put(".agents", protectedMetadataAccess)
                        },
                    )
                    put(":tmpdir", "deny")
                    put(":slash_tmp", "deny")
                },
            )
            put("network", buildJsonObject { put("enabled", false) })
        }

    private fun disabledEntries(entries: JsonObject?): JsonObject =
        buildJsonObject {
            entries?.keys?.forEach { id -> put(id, buildJsonObject { put("enabled", false) }) }
        }

    private suspend fun awaitResponse(
        process: TrustedLocalAgentProcess,
        id: Long,
        sessionId: String,
    ): JsonObject {
        while (true) {
            val message = readMessage(process)
            if (message["id"]?.jsonPrimitive?.contentOrNull == id.toString() && message["method"] == null) {
                message["error"]?.let { error("Codex app-server error: $it") }
                return message
            }
            if (message["id"] != null && message["method"] != null) {
                handleServerRequest(process, message, sessionId)
            }
        }
    }

    private suspend fun handleServerRequest(
        process: TrustedLocalAgentProcess,
        message: JsonObject,
        sessionId: String,
    ) {
        val method = message["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val id = message["id"] ?: return
        when (method) {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            -> {
                val allowed =
                    approvalGate.checkMandatory("codex_local_action", message["params"].toString(), sessionId).allowed
                process.sendLine(
                    buildJsonObject {
                        put("id", id)
                        put("result", buildJsonObject { put("decision", if (allowed) "accept" else "decline") })
                    }.toString(),
                )
            }

            "item/permissions/requestApproval" -> {
                process.sendLine(
                    buildJsonObject {
                        put("id", id)
                        put("result", buildJsonObject { put("permissions", buildJsonObject {}) })
                    }.toString(),
                )
            }

            else -> {
                process.sendLine(
                    buildJsonObject {
                        put("id", id)
                        put(
                            "error",
                            buildJsonObject {
                                put("code", -32601)
                                put("message", "Unsupported Codex app-server request")
                            },
                        )
                    }.toString(),
                )
            }
        }
    }

    private suspend fun sendRequest(
        process: TrustedLocalAgentProcess,
        method: String,
        params: JsonObject,
    ): Long {
        val id = ids.getAndIncrement()
        process.sendLine(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString(),
        )
        return id
    }

    private suspend fun readMessage(process: TrustedLocalAgentProcess): JsonObject {
        val line = process.readLine(RPC_READ_TIMEOUT_MILLIS)
            ?: error("Codex app-server stopped responding${process.stderr().takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
        return json.parseToJsonElement(line).jsonObject
    }

    private fun JsonObject.path(vararg names: String): String? {
        var current: JsonElement = this
        names.forEach { name -> current = (current as? JsonObject)?.get(name) ?: return null }
        return current.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.belongsTo(
        threadId: String,
        turnId: String,
    ): Boolean {
        val eventThreadId = path("params", "threadId") ?: return false
        val eventTurnId = path("params", "turnId") ?: path("params", "turn", "id") ?: return false
        return eventThreadId == threadId && eventTurnId == turnId
    }

    private fun LocalCodingAgentRequest.permissionProfile(): String =
        when (accessMode) {
            LocalCodingAccessMode.READ_ONLY -> READ_ONLY_PROFILE
            LocalCodingAccessMode.WORKSPACE_WRITE -> WORKSPACE_WRITE_PROFILE
        }
}

private const val RPC_READ_TIMEOUT_MILLIS = 120_000L
private const val AUTH_PROBE_TIMEOUT_MILLIS = 10_000L
private const val EXECUTION_TIMEOUT_MILLIS = 30 * 60 * 1_000L
private const val READ_ONLY_PROFILE = "promethe-readonly"
private const val WORKSPACE_WRITE_PROFILE = "promethe-workspace"
