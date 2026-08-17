package dev.promethe.core

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import dev.promethe.core.hooks.HookContext
import dev.promethe.core.hooks.HookEvent
import dev.promethe.core.hooks.HookManager
import dev.promethe.core.hooks.HookResult
import dev.promethe.core.sandbox.SandboxCommandExecutor
import dev.promethe.core.sandbox.renderCommandOutput
import io.ktor.client.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Dangerous commands that should never be executed locally.
 * Matched as prefix of the command string.
 */
internal val COMMAND_BLOCKLIST =
    listOf(
        "rm -rf /",
        "rm -rf /*",
        "format c:",
        "format d:",
        "shutdown",
        "reboot",
        "mkfs",
        "dd if=/dev/zero",
        ":(){ :|:& };:", // fork bomb
        "del /s /q c:\\",
        "rd /s /q c:\\",
    )

/**
 * Truncate output to maxBytes, appending a marker if truncated.
 */
internal fun truncateOutput(
    output: String,
    maxBytes: Int,
): String {
    if (output.length <= maxBytes) return output
    return output.take(maxBytes) + "\n\n--- [OUTPUT TRUNCATED at ${maxBytes / 1000}KB] ---"
}

/**
 * Check if a command matches any entry in the blocklist.
 */
internal fun isBlockedCommand(
    command: String,
    args: List<String>,
): Boolean {
    val fullCommand = (listOf(command) + args).joinToString(" ").lowercase()
    return COMMAND_BLOCKLIST.any { blocked ->
        fullCommand.startsWith(blocked.lowercase())
    }
}

/**
 * Platform-specific process execution.
 * On JVM: delegates to the installed native sandbox runner.
 * On other platforms: returns an error (no process execution support).
 */
expect fun runLocalProcess(
    command: String,
    args: List<String>,
    timeoutMs: Long = 30_000,
    maxOutputBytes: Int = 50_000,
): String

class ActionExecutor(
    private val config: AgentConfig,
    @Suppress("UNUSED_PARAMETER") httpClient: HttpClient,
    @Suppress("UNUSED_PARAMETER") sandboxBaseUrl: String = "",
    private val hookManager: HookManager? = null,
    private val approvalGate: ApprovalGate? = null,
    private val sandboxCommandExecutor: SandboxCommandExecutor? = null,
) : SecureToolExecutor {
    /** Shared Koog-compatible serializer backed by kotlinx-serialization. */
    private val koogSerializer = KotlinxSerializer()

    /** Current session ID for approval tracking. Set by AIAgent before each loop. */
    var currentSessionId: String = "unknown"

    /**
     * Per-turn exact-match dedup cache (key = "toolName|args", value = result).
     * Prevents the LLM from re-executing identical tool calls.
     * Cleared via [clearDedupCache] at the start of each agent turn.
     */
    private val dedupCache = mutableMapOf<String, String>()

    /** Clear the dedup cache (call at the start of each agent turn). */
    fun clearDedupCache() {
        dedupCache.clear()
    }

    /** Tools that are safe to deduplicate (read-only, no side effects). */
    private val dedupSafeTools = setOf(
        "web_search",
        "web_browse",
        "read_file",
        "list_directory",
        "get_current_datetime",
        "get_config",
        "list_profiles",
        "workspace_roots",
    )

    @OptIn(InternalAgentToolsApi::class)
    suspend fun execute(
        toolName: String,
        args: JsonObject,
    ): String =
        execute(
            ToolExecutionRequest(
                toolName = toolName,
                arguments = args,
                sessionId = currentSessionId,
                origin = ToolCallOrigin.AGENT,
            ),
        )

    @OptIn(InternalAgentToolsApi::class)
    override suspend fun execute(request: ToolExecutionRequest): String {
        val toolName = request.toolName
        val args = request.arguments
        return Tracing.span("agent.tool.execute") {
            setAttribute("tool.name", toolName)
            setAttribute("tool.origin", request.origin.name)
            setAttribute("tool.args_keys", args.keys.joinToString(","))

            if (toolName == "discord_policy" && request.origin !in ownerPolicyOrigins) {
                return@span "[BLOCKED] Discord policy administration is restricted to owner conversations"
            }

            // ── DEDUP CHECK — return cached result for identical read-only calls ──
            if (toolName in dedupSafeTools) {
                val cacheKey = "$toolName|$args"
                dedupCache[cacheKey]?.let { cached ->
                    return@span "$cached\n\n[NOTE: Cached result — you already called this tool with identical arguments. Use the information above to formulate your response.]"
                }
            }

            // ── BEFORE_TOOL_CALL hook ──
            if (hookManager != null) {
                val beforeCtx =
                    HookContext(
                        event = HookEvent.BEFORE_TOOL_CALL,
                        toolName = toolName,
                        toolArgs = args,
                    )
                val hookResult = hookManager.fire(beforeCtx)
                if (hookResult is HookResult.Abort) {
                    return@span "[BLOCKED] ${hookResult.reason}"
                }
            }

            // ── APPROVAL GATE — human-in-the-loop for dangerous tools ──
            val policy = ToolApprovalPolicy.evaluate(toolName, args)
            val unconfinedFileAccess =
                toolName in fileAccessTools && sandboxCommandExecutor?.hasUnconfinedFileAccess() == true
            if (unconfinedFileAccess && request.origin !in localInteractiveOrigins) {
                return@span "[BLOCKED] Full local file access is unavailable from ${request.origin.name.lowercase()}"
            }
            val requiresMandatoryApproval = policy.mandatoryApproval || unconfinedFileAccess
            setAttribute("tool.risk", policy.risk.name)
            if (requiresMandatoryApproval && approvalGate == null) {
                return@span "[BLOCKED] A human approval service is required for '$toolName'"
            }
            if (approvalGate != null) {
                val canonicalArguments =
                    canonicalJson(args) +
                        if (toolName in processBackedTools || toolName in fileAccessTools) {
                            "\n[workspace=${request.workspaceRelativePath ?: "."}]" +
                                "\n[sandbox-policy=${sandboxCommandExecutor?.approvalContext() ?: "unavailable"}]"
                        } else {
                            ""
                        }
                val approvalResult =
                    if (requiresMandatoryApproval) {
                        approvalGate.checkMandatory(toolName, canonicalArguments, request.sessionId)
                    } else {
                        approvalGate.check(toolName, canonicalArguments, request.sessionId)
                    }
                if (!approvalResult.allowed) return@span "[BLOCKED] Approval denied: ${approvalResult.reason}"
            }

            val result =
                run {
                    // Recherche dans le registre d'outils structurés Koog
                    val tool: ToolBase<*, *>? = ToolRegistry.getTool(toolName)
                    if (tool != null) {
                        return@run try {
                            // 1. Convert kotlinx JsonObject -> Koog JSONObject (builtin extension)
                            val koogArgs = args.toKoogJSONObject()

                            // 2. Decode into the tool's typed TArgs using its TypeToken
                            val typedArgs = tool.decodeArgs(koogArgs, koogSerializer)

                            // 3. Execute with type-erased dispatch
                            val execResult =
                                withContext(ToolInvocationContext(request)) {
                                    tool.executeUnsafe(typedArgs)
                                }

                            // 4. Coerce result to String, truncate
                            val raw = execResult?.toString() ?: ""
                            truncateOutput(raw, config.maxOutputBytes)
                        } catch (e: Exception) {
                            "[ERROR] Tool execution failed: ${e.message}"
                        }
                    }

                    // Cas par defaut d'execution de commande systeme
                    if (toolName == "execute_command") {
                        val executable =
                            args["executable"]?.jsonPrimitive?.content
                                ?: return@run "Error: 'executable' argument missing"
                        val arguments = args["arguments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                        // Generic command execution never falls back to the host.
                        // The external sandbox receives an executable plus arguments.
                        return@run runSandboxCommand(
                            executable,
                            arguments,
                            request.sessionId,
                            request.workspaceRelativePath ?: ".",
                        )
                    }

                    "Error: Tool '$toolName' is not registered"
                }

            // ── AFTER_TOOL_CALL hook ──
            val finalResult =
                if (hookManager != null) {
                    val afterCtx =
                        HookContext(
                            event = HookEvent.AFTER_TOOL_CALL,
                            toolName = toolName,
                            toolArgs = args,
                            toolResult = result,
                        )
                    when (val hookResult = hookManager.fire(afterCtx)) {
                        is HookResult.Modify -> hookResult.newValue
                        else -> result
                    }
                } else {
                    result
                }

            setAttribute("tool.result.length", finalResult.length)
            setAttribute("tool.success", !finalResult.startsWith("[ERROR]") && !finalResult.startsWith("[BLOCKED]"))

            // ── STORE in dedup cache for safe tools ──
            if (toolName in dedupSafeTools && !finalResult.startsWith("[ERROR]") && !finalResult.startsWith("[BLOCKED]")) {
                dedupCache["$toolName|$args"] = finalResult
            }

            finalResult
        }
    }

    private val processBackedTools =
        setOf(
            "execute_command",
            "shell",
            "execute_code",
            "docker",
            "process_manager",
            "git_status",
            "git_diff",
            "git_commit",
            "git_log",
            "git_branch",
            "web_screenshot",
        )

    private val fileAccessTools =
        setOf(
            "read_file",
            "write_file",
            "file_delete",
            "file_move",
            "directory_tree",
            "file_search",
            "code_grep",
            "patch",
            "csv",
            "workspace_roots",
        )

    private val localInteractiveOrigins = setOf(ToolCallOrigin.AGENT, ToolCallOrigin.A2A)
    private val ownerPolicyOrigins = setOf(ToolCallOrigin.A2A)

    private suspend fun runSandboxCommand(
        command: String,
        args: List<String>,
        sessionId: String,
        workingDirectory: String,
    ): String {
        val executor =
            sandboxCommandExecutor
                ?: return "[SANDBOX BACKEND_UNAVAILABLE] Native sandbox is not configured."
        return executor
            .executeCommand(
                executable = command,
                arguments = args,
                workingDirectory = workingDirectory,
                sessionId = sessionId,
                timeoutMillis = config.executionTimeoutMs,
            ).renderCommandOutput()
    }
}
