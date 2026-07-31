package dev.promethe.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared action-aware policy so every protocol makes the same approval decision. */
object ToolApprovalPolicy {
    private val operationKeyCandidates = listOf("action", "operation", "command")
    private var certifiedReadOnlyMcpTools: Set<String> = emptySet()

    private val knownReadOnlyTools =
        setOf(
            "read_file",
            "http_fetch",
            "web_scrape",
            "web_search",
            "web_crawl",
            "web_extract",
            "rss_feed",
            "x_search",
            "directory_tree",
            "file_search",
            "code_grep",
            "git_status",
            "git_diff",
            "git_log",
            "system_info",
            "environment",
            "json_query",
            "pdf_reader",
            "hash",
            "encrypt",
            "cert_check",
            "embedding",
            "speech_to_text",
            "vector_search",
            "knowledge_search",
            "clarify",
            "session_search",
            "config_get",
            "plugin_list",
            "checkpoint_list",
            "agent_status",
            "session_history",
            "token_budget",
            "memory_search",
            "memory_list",
            "skill_search",
            "skill_load",
            "skill_list",
            "ha_list_entities",
            "ha_get_state",
            "ha_list_services",
            "browser_extract",
            "browser_screenshot",
            "browser_scroll",
            "browser_back",
            "browser_get_images",
            "browser_vision",
        )

    private val alwaysRiskyTools =
        mapOf(
            "execute_code" to ToolRisk.EXECUTE,
            "shell" to ToolRisk.EXECUTE,
            "execute_command" to ToolRisk.EXECUTE,
            "docker" to ToolRisk.EXECUTE,
            "process_manager" to ToolRisk.EXECUTE,
            "plugin_hook" to ToolRisk.EXECUTE,
            "browser_eval" to ToolRisk.EXECUTE,
            "write_file" to ToolRisk.WRITE,
            "file_delete" to ToolRisk.DESTRUCTIVE,
            "file_move" to ToolRisk.DESTRUCTIVE,
            "patch" to ToolRisk.WRITE,
            "git_commit" to ToolRisk.WRITE,
            "git_branch" to ToolRisk.WRITE,
            "config_set" to ToolRisk.CONFIG_CHANGE,
            "send_email" to ToolRisk.EXTERNAL_EFFECT,
            "twilio" to ToolRisk.EXTERNAL_EFFECT,
            "send_message" to ToolRisk.EXTERNAL_EFFECT,
            "signal" to ToolRisk.EXTERNAL_EFFECT,
            "slack" to ToolRisk.EXTERNAL_EFFECT,
            "discord" to ToolRisk.EXTERNAL_EFFECT,
            "ha_call_service" to ToolRisk.DEVICE_CONTROL,
            "memory_save" to ToolRisk.WRITE,
            "memory_forget" to ToolRisk.DESTRUCTIVE,
            "knowledge_ingest" to ToolRisk.WRITE,
            "knowledge_delete" to ToolRisk.DESTRUCTIVE,
            "skill_create" to ToolRisk.WRITE,
            "skill_improve" to ToolRisk.WRITE,
            "create_agent" to ToolRisk.CONFIG_CHANGE,
            "promote_agent" to ToolRisk.CONFIG_CHANGE,
            "cleanup_ephemeral_agents" to ToolRisk.DESTRUCTIVE,
            "browser_click" to ToolRisk.EXTERNAL_EFFECT,
            "browser_type" to ToolRisk.EXTERNAL_EFFECT,
            "browser_press" to ToolRisk.EXTERNAL_EFFECT,
            "browser_dialog" to ToolRisk.EXTERNAL_EFFECT,
        )

    private val riskyOperations =
        mapOf(
            "github" to mapOf("create_issue" to ToolRisk.EXTERNAL_EFFECT, "create_pr" to ToolRisk.EXTERNAL_EFFECT),
            "calendar" to mapOf("create_event" to ToolRisk.EXTERNAL_EFFECT, "delete_event" to ToolRisk.DESTRUCTIVE),
            "notion" to mapOf("create_page" to ToolRisk.EXTERNAL_EFFECT, "append_blocks" to ToolRisk.EXTERNAL_EFFECT),
            "jira" to
                mapOf(
                    "create_issue" to ToolRisk.EXTERNAL_EFFECT,
                    "transition" to ToolRisk.EXTERNAL_EFFECT,
                    "add_comment" to ToolRisk.EXTERNAL_EFFECT,
                ),
            "cronjob" to mapOf("create" to ToolRisk.CONFIG_CHANGE, "delete" to ToolRisk.DESTRUCTIVE),
        )

    fun evaluate(
        toolName: String,
        arguments: JsonObject,
    ): ToolPolicyDecision {
        if (toolName == "csv") {
            val risk = csvRisk(arguments)
            return ToolPolicyDecision(risk = risk, mandatoryApproval = risk != ToolRisk.READ)
        }

        val directRisk = alwaysRiskyTools[toolName]
        val operation =
            operationKeyCandidates
                .mapNotNull { key -> (arguments[key] as? JsonPrimitive)?.content }
                .firstOrNull()
                ?.lowercase()
        val operationRisk = operation?.let { riskyOperations[toolName]?.get(it) }
        val risk =
            operationRisk
                ?: directRisk
                ?: if (toolName in knownReadOnlyTools || toolName in certifiedReadOnlyMcpTools) {
                    ToolRisk.READ
                } else {
                    ToolRisk.EXTERNAL_EFFECT
                }
        return ToolPolicyDecision(risk = risk, mandatoryApproval = risk != ToolRisk.READ)
    }

    fun requiresMandatoryApproval(
        toolName: String,
        arguments: JsonObject,
    ): Boolean = evaluate(toolName, arguments).mandatoryApproval

    fun catalogRisk(toolName: String): ToolRisk =
        if (toolName == "csv") {
            ToolRisk.WRITE
        } else {
            alwaysRiskyTools[toolName]
                ?: riskyOperations[toolName]?.values?.maxByOrNull { it.ordinal }
                ?: if (toolName in knownReadOnlyTools || toolName in certifiedReadOnlyMcpTools) {
                    ToolRisk.READ
                } else {
                    ToolRisk.EXTERNAL_EFFECT
                }
        }

    private fun csvRisk(arguments: JsonObject): ToolRisk {
        val actionElement = arguments["action"] ?: return ToolRisk.READ
        val action = (actionElement as? JsonPrimitive)?.content?.lowercase() ?: return ToolRisk.WRITE
        return when (action) {
            "read", "query" -> ToolRisk.READ
            "write" -> ToolRisk.WRITE
            else -> ToolRisk.WRITE
        }
    }

    /**
     * Records an owner-controlled MCP read-only certification. Remote MCP
     * metadata is never sufficient to call this API.
     */
    internal fun certifyReadOnlyMcpTool(toolName: String) {
        require(toolName.startsWith("mcp_")) { "Only qualified MCP tools can be certified read-only" }
        certifiedReadOnlyMcpTools = certifiedReadOnlyMcpTools + toolName
    }

    internal fun revokeReadOnlyMcpToolCertification(toolName: String) {
        certifiedReadOnlyMcpTools = certifiedReadOnlyMcpTools - toolName
    }

    /** Compatibility snapshot for callers that cannot yet provide arguments. */
    val dangerousTools =
        alwaysRiskyTools.keys + riskyOperations.keys
}
