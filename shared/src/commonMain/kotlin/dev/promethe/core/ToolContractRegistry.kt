package dev.promethe.core

import dev.promethe.api.ToolApprovalRequirement
import dev.promethe.api.ToolContractDescriptor
import dev.promethe.api.ToolContractSource
import dev.promethe.api.ToolEgress
import dev.promethe.api.ToolIdempotency
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolContractCoverageIssueType {
    MISSING_EXPLICIT_CONTRACT,
    EFFECT_WITHOUT_APPROVAL,
    UNKNOWN_EFFECT_EGRESS,
}

data class ToolContractCoverageIssue(
    val toolName: String,
    val type: ToolContractCoverageIssueType,
    val detail: String,
)

data class ToolContractCoverageReport(
    val toolCount: Int,
    val explicitContractCount: Int,
    val effectfulToolCount: Int,
    val issues: List<ToolContractCoverageIssue>,
) {
    val valid: Boolean
        get() = issues.isEmpty()
}

/** Declarative security and execution contract shared by every tool entry point. */
data class ToolContract(
    val toolName: String,
    val source: ToolContractSource,
    val catalogRisk: ToolRisk,
    val missingOperationRisk: ToolRisk,
    val unknownOperationRisk: ToolRisk,
    val approval: ToolApprovalRequirement,
    val idempotency: ToolIdempotency,
    val egress: ToolEgress,
    val ownerOnly: Boolean = false,
    val explicit: Boolean = true,
    val operationKeys: List<String> = emptyList(),
    val operationRisks: Map<String, ToolRisk> = emptyMap(),
) {
    fun evaluate(arguments: JsonObject): ToolPolicyDecision {
        val operation =
            operationKeys
                .asSequence()
                .mapNotNull { key -> (arguments[key] as? JsonPrimitive)?.content }
                .firstOrNull()
                ?.lowercase()
        val risk =
            when {
                operation == null -> missingOperationRisk
                operation in operationRisks -> operationRisks.getValue(operation)
                else -> unknownOperationRisk
            }
        val mandatoryApproval =
            when (approval) {
                ToolApprovalRequirement.NONE -> false
                ToolApprovalRequirement.RISK_BASED -> risk != ToolRisk.READ
                ToolApprovalRequirement.ALWAYS -> true
            }
        return ToolPolicyDecision(
            risk = risk,
            mandatoryApproval = mandatoryApproval,
            ownerOnly = ownerOnly,
        )
    }

    fun descriptor(): ToolContractDescriptor =
        ToolContractDescriptor(
            source = source,
            catalogRisk = catalogRisk,
            missingOperationRisk = missingOperationRisk,
            unknownOperationRisk = unknownOperationRisk,
            approval = approval,
            idempotency = idempotency,
            egress = egress,
            ownerOnly = ownerOnly,
            explicit = explicit,
            operationKeys = operationKeys,
            operationRisks = operationRisks,
        )
}

/**
 * Single source of truth for tool risk, approval, ownership, idempotency and egress.
 * Unknown tools fail closed through an explicit fallback contract.
 */
object ToolContractRegistry {
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
            "workspace_roots",
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
            "artifact_read",
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

    private val remoteReadOnlyTools =
        setOf(
            "http_fetch",
            "web_scrape",
            "web_search",
            "web_crawl",
            "web_extract",
            "rss_feed",
            "x_search",
            "embedding",
            "speech_to_text",
            "vector_search",
            "knowledge_search",
            "browser_extract",
            "browser_screenshot",
            "browser_scroll",
            "browser_back",
            "browser_get_images",
            "browser_vision",
        )

    private val directRisks =
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
            "delegate_task" to ToolRisk.EXECUTE,
            "codex_delegate" to ToolRisk.EXECUTE,
            "claude_code_delegate" to ToolRisk.EXECUTE,
        )

    private val integrationTools =
        setOf(
            "send_email",
            "twilio",
            "send_message",
            "signal",
            "slack",
            "discord",
            "ha_call_service",
        )

    private val localAgentTools = setOf("codex_delegate", "claude_code_delegate")

    private val sensitiveContracts =
        mapOf(
            "generate_image" to sensitiveRemoteContract("generate_image"),
            "analyze_image" to sensitiveRemoteContract("analyze_image"),
            "text_to_speech" to sensitiveRemoteContract("text_to_speech"),
            "video_generate" to sensitiveRemoteContract("video_generate"),
            "video_analyze" to sensitiveRemoteContract("video_analyze"),
            "api_call" to sensitiveRemoteContract("api_call"),
            "web_screenshot" to sensitiveRemoteContract("web_screenshot"),
            "browser_navigate" to sensitiveRemoteContract("browser_navigate"),
            "render_ui" to sensitiveRemoteContract("render_ui"),
            "checkpoint_save" to directContract("checkpoint_save", ToolRisk.WRITE),
            "autonomous_goal" to directContract("autonomous_goal", ToolRisk.EXECUTE),
            "mixture_of_agents" to directContract("mixture_of_agents", ToolRisk.EXECUTE),
            "list_agents" to readContract("list_agents"),
            "get_subtask_result" to readContract("get_subtask_result"),
        )

    private val operationContracts =
        mapOf(
            "csv" to
                operationContract(
                    toolName = "csv",
                    source = ToolContractSource.BUILTIN,
                    catalogRisk = ToolRisk.WRITE,
                    missingRisk = ToolRisk.READ,
                    unknownRisk = ToolRisk.WRITE,
                    operations = mapOf("read" to ToolRisk.READ, "query" to ToolRisk.READ, "write" to ToolRisk.WRITE),
                ),
            "github" to
                integrationOperationContract(
                    "github",
                    ToolRisk.EXTERNAL_EFFECT,
                    mapOf("create_issue" to ToolRisk.EXTERNAL_EFFECT, "create_pr" to ToolRisk.EXTERNAL_EFFECT),
                ),
            "calendar" to
                integrationOperationContract(
                    "calendar",
                    ToolRisk.DESTRUCTIVE,
                    mapOf("create_event" to ToolRisk.EXTERNAL_EFFECT, "delete_event" to ToolRisk.DESTRUCTIVE),
                ),
            "notion" to
                integrationOperationContract(
                    "notion",
                    ToolRisk.EXTERNAL_EFFECT,
                    mapOf("create_page" to ToolRisk.EXTERNAL_EFFECT, "append_blocks" to ToolRisk.EXTERNAL_EFFECT),
                ),
            "jira" to
                integrationOperationContract(
                    "jira",
                    ToolRisk.EXTERNAL_EFFECT,
                    mapOf(
                        "create_issue" to ToolRisk.EXTERNAL_EFFECT,
                        "transition" to ToolRisk.EXTERNAL_EFFECT,
                        "add_comment" to ToolRisk.EXTERNAL_EFFECT,
                    ),
                ),
            "cronjob" to
                operationContract(
                    toolName = "cronjob",
                    source = ToolContractSource.BUILTIN,
                    catalogRisk = ToolRisk.CONFIG_CHANGE,
                    missingRisk = ToolRisk.EXTERNAL_EFFECT,
                    unknownRisk = ToolRisk.EXTERNAL_EFFECT,
                    operations = mapOf("create" to ToolRisk.CONFIG_CHANGE, "delete" to ToolRisk.DESTRUCTIVE),
                ),
            "discord_policy" to
                operationContract(
                    toolName = "discord_policy",
                    source = ToolContractSource.INTEGRATION,
                    catalogRisk = ToolRisk.CONFIG_CHANGE,
                    missingRisk = ToolRisk.EXTERNAL_EFFECT,
                    unknownRisk = ToolRisk.EXTERNAL_EFFECT,
                    ownerOnly = true,
                    operations =
                        mapOf(
                            "list" to ToolRisk.READ,
                            "allow_user" to ToolRisk.CONFIG_CHANGE,
                            "deny_user" to ToolRisk.CONFIG_CHANGE,
                            "remove_user_rule" to ToolRisk.CONFIG_CHANGE,
                            "listen_channel" to ToolRisk.CONFIG_CHANGE,
                            "stop_listening_channel" to ToolRisk.CONFIG_CHANGE,
                            "remove_channel_rule" to ToolRisk.CONFIG_CHANGE,
                        ),
                ),
            "todo" to
                operationContract(
                    toolName = "todo",
                    source = ToolContractSource.BUILTIN,
                    catalogRisk = ToolRisk.DESTRUCTIVE,
                    missingRisk = ToolRisk.READ,
                    unknownRisk = ToolRisk.WRITE,
                    operations =
                        mapOf(
                            "list" to ToolRisk.READ,
                            "add" to ToolRisk.WRITE,
                            "done" to ToolRisk.WRITE,
                            "remove" to ToolRisk.DESTRUCTIVE,
                        ),
                ),
            "notes" to
                operationContract(
                    toolName = "notes",
                    source = ToolContractSource.BUILTIN,
                    catalogRisk = ToolRisk.DESTRUCTIVE,
                    missingRisk = ToolRisk.READ,
                    unknownRisk = ToolRisk.WRITE,
                    operations =
                        mapOf(
                            "list" to ToolRisk.READ,
                            "get" to ToolRisk.READ,
                            "set" to ToolRisk.WRITE,
                            "delete" to ToolRisk.DESTRUCTIVE,
                        ),
                ),
        )

    private val staticContracts: Map<String, ToolContract> =
        buildMap {
            setOf("harness_inspect", "harness_propose", "harness_evaluate", "harness_activate", "harness_rollback", "harness_disable", "harness_adapt").forEach { name ->
                put(name, directContract(name, if (name == "harness_inspect") ToolRisk.READ else ToolRisk.CONFIG_CHANGE, ToolContractSource.BUILTIN, ToolEgress.NONE))
            }
            knownReadOnlyTools.forEach { toolName ->
                put(
                    toolName,
                    readContract(
                        toolName = toolName,
                        egress = if (toolName in remoteReadOnlyTools) ToolEgress.REMOTE_SERVICE else ToolEgress.NONE,
                    ),
                )
            }
            directRisks.forEach { (toolName, risk) ->
                put(
                    toolName,
                    directContract(
                        toolName = toolName,
                        risk = risk,
                        source =
                            when {
                                toolName in integrationTools -> ToolContractSource.INTEGRATION
                                toolName in localAgentTools -> ToolContractSource.LOCAL_AGENT
                                toolName == "plugin_hook" -> ToolContractSource.PLUGIN
                                else -> ToolContractSource.BUILTIN
                            },
                        egress = egressFor(toolName, risk),
                    ),
                )
            }
            putAll(sensitiveContracts)
            putAll(operationContracts)
        }

    private var dynamicContracts: Map<String, ToolContract> = emptyMap()

    fun contractFor(toolName: String): ToolContract =
        dynamicContracts[toolName]
            ?: staticContracts[toolName]
            ?: when {
                toolName.startsWith("mcp_") -> remoteDynamicContract(toolName, ToolContractSource.MCP)
                toolName.startsWith("acp_") -> remoteDynamicContract(toolName, ToolContractSource.ACP)
                else -> fallbackContract(toolName, ToolContractSource.FALLBACK)
            }

    fun evaluate(
        toolName: String,
        arguments: JsonObject,
    ): ToolPolicyDecision = contractFor(toolName).evaluate(arguments)

    internal fun certifyReadOnlyMcpTool(toolName: String) {
        require(toolName.startsWith("mcp_")) { "Only qualified MCP tools can be certified read-only" }
        dynamicContracts = dynamicContracts + (toolName to readContract(toolName, ToolContractSource.MCP, ToolEgress.REMOTE_SERVICE))
    }

    internal fun revokeReadOnlyMcpToolCertification(toolName: String) {
        dynamicContracts = dynamicContracts - toolName
    }

    fun audit(toolNames: Iterable<String>): ToolContractCoverageReport {
        val names = toolNames.distinct().sorted()
        val contracts = names.associateWith(::contractFor)
        val issues =
            contracts.flatMap { (toolName, contract) ->
                buildList {
                    if (!contract.explicit) {
                        add(
                            ToolContractCoverageIssue(
                                toolName,
                                ToolContractCoverageIssueType.MISSING_EXPLICIT_CONTRACT,
                                "No explicit contract or approved dynamic contract family",
                            ),
                        )
                    }
                    if (contract.catalogRisk != ToolRisk.READ && contract.approval == ToolApprovalRequirement.NONE) {
                        add(
                            ToolContractCoverageIssue(
                                toolName,
                                ToolContractCoverageIssueType.EFFECT_WITHOUT_APPROVAL,
                                "Effectful catalog risk ${contract.catalogRisk} has no approval policy",
                            ),
                        )
                    }
                    if (contract.catalogRisk != ToolRisk.READ && contract.egress == ToolEgress.UNKNOWN) {
                        add(
                            ToolContractCoverageIssue(
                                toolName,
                                ToolContractCoverageIssueType.UNKNOWN_EFFECT_EGRESS,
                                "Effectful tool has unknown egress",
                            ),
                        )
                    }
                    verifyEffectApproval(toolName, contract).forEach(::add)
                }
            }
        return ToolContractCoverageReport(
            toolCount = names.size,
            explicitContractCount = contracts.values.count(ToolContract::explicit),
            effectfulToolCount = contracts.values.count { it.catalogRisk != ToolRisk.READ },
            issues = issues,
        )
    }

    fun requireCompleteCoverage(toolNames: Iterable<String>): ToolContractCoverageReport {
        val report = audit(toolNames)
        check(report.valid) {
            report.issues.joinToString(
                prefix = "Tool contract coverage failed: ",
                separator = "; ",
            ) { issue -> "${issue.toolName} [${issue.type}]: ${issue.detail}" }
        }
        return report
    }

    fun staticContractNames(): Set<String> = staticContracts.keys

    val approvalRequiredToolNames: Set<String>
        get() =
            staticContracts
                .filterValues { contract -> contract.catalogRisk != ToolRisk.READ }
                .keys
}

private fun verifyEffectApproval(
    toolName: String,
    contract: ToolContract,
): List<ToolContractCoverageIssue> {
    val checks = mutableListOf<Pair<String, JsonObject>>()
    if (contract.missingOperationRisk != ToolRisk.READ) {
        checks += "missing operation" to buildJsonObject {}
    }
    val operationKey = contract.operationKeys.firstOrNull()
    if (operationKey != null) {
        contract.operationRisks
            .filterValues { risk -> risk != ToolRisk.READ }
            .keys
            .forEach { operation ->
                checks += "operation '$operation'" to buildJsonObject { put(operationKey, operation) }
            }
        if (contract.unknownOperationRisk != ToolRisk.READ) {
            checks += "unknown operation" to buildJsonObject { put(operationKey, "__contract_audit_unknown__") }
        }
    }
    return checks.mapNotNull { (label, arguments) ->
        if (contract.evaluate(arguments).mandatoryApproval) {
            null
        } else {
            ToolContractCoverageIssue(
                toolName,
                ToolContractCoverageIssueType.EFFECT_WITHOUT_APPROVAL,
                "$label can produce an effect without mandatory approval",
            )
        }
    }
}

private fun readContract(
    toolName: String,
    source: ToolContractSource = ToolContractSource.BUILTIN,
    egress: ToolEgress = ToolEgress.NONE,
): ToolContract =
    ToolContract(
        toolName = toolName,
        source = source,
        catalogRisk = ToolRisk.READ,
        missingOperationRisk = ToolRisk.READ,
        unknownOperationRisk = ToolRisk.READ,
        approval = ToolApprovalRequirement.NONE,
        idempotency = ToolIdempotency.SAFE_RETRY,
        egress = egress,
    )

private fun directContract(
    toolName: String,
    risk: ToolRisk,
    source: ToolContractSource = ToolContractSource.BUILTIN,
    egress: ToolEgress = egressFor(toolName, risk),
): ToolContract =
    ToolContract(
        toolName = toolName,
        source = source,
        catalogRisk = risk,
        missingOperationRisk = risk,
        unknownOperationRisk = risk,
        approval = ToolApprovalRequirement.RISK_BASED,
        idempotency = if (risk == ToolRisk.WRITE) ToolIdempotency.IDEMPOTENCY_KEY_REQUIRED else ToolIdempotency.NEVER_AUTOMATIC,
        egress = egress,
    )

private fun sensitiveRemoteContract(toolName: String): ToolContract =
    ToolContract(
        toolName = toolName,
        source = ToolContractSource.INTEGRATION,
        catalogRisk = ToolRisk.EXTERNAL_EFFECT,
        missingOperationRisk = ToolRisk.EXTERNAL_EFFECT,
        unknownOperationRisk = ToolRisk.EXTERNAL_EFFECT,
        approval = ToolApprovalRequirement.ALWAYS,
        idempotency = ToolIdempotency.NEVER_AUTOMATIC,
        egress = ToolEgress.REMOTE_SERVICE,
    )

private fun remoteDynamicContract(
    toolName: String,
    source: ToolContractSource,
): ToolContract =
    ToolContract(
        toolName = toolName,
        source = source,
        catalogRisk = ToolRisk.EXTERNAL_EFFECT,
        missingOperationRisk = ToolRisk.EXTERNAL_EFFECT,
        unknownOperationRisk = ToolRisk.EXTERNAL_EFFECT,
        approval = ToolApprovalRequirement.ALWAYS,
        idempotency = ToolIdempotency.NEVER_AUTOMATIC,
        egress = ToolEgress.REMOTE_SERVICE,
    )

private fun integrationOperationContract(
    toolName: String,
    catalogRisk: ToolRisk,
    operations: Map<String, ToolRisk>,
): ToolContract =
    operationContract(
        toolName = toolName,
        source = ToolContractSource.INTEGRATION,
        catalogRisk = catalogRisk,
        missingRisk = ToolRisk.EXTERNAL_EFFECT,
        unknownRisk = ToolRisk.EXTERNAL_EFFECT,
        operations = operations,
        egress = ToolEgress.REMOTE_SERVICE,
    )

private fun operationContract(
    toolName: String,
    source: ToolContractSource,
    catalogRisk: ToolRisk,
    missingRisk: ToolRisk,
    unknownRisk: ToolRisk,
    operations: Map<String, ToolRisk>,
    ownerOnly: Boolean = false,
    egress: ToolEgress = ToolEgress.NONE,
): ToolContract =
    ToolContract(
        toolName = toolName,
        source = source,
        catalogRisk = catalogRisk,
        missingOperationRisk = missingRisk,
        unknownOperationRisk = unknownRisk,
        approval = ToolApprovalRequirement.RISK_BASED,
        idempotency = ToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
        egress = egress,
        ownerOnly = ownerOnly,
        operationKeys = listOf("action", "operation", "command"),
        operationRisks = operations,
    )

private fun fallbackContract(
    toolName: String,
    source: ToolContractSource,
): ToolContract =
    ToolContract(
        toolName = toolName,
        source = source,
        catalogRisk = ToolRisk.EXTERNAL_EFFECT,
        missingOperationRisk = ToolRisk.EXTERNAL_EFFECT,
        unknownOperationRisk = ToolRisk.EXTERNAL_EFFECT,
        approval = ToolApprovalRequirement.ALWAYS,
        idempotency = ToolIdempotency.NEVER_AUTOMATIC,
        egress = ToolEgress.UNKNOWN,
        explicit = false,
    )

private val sandboxProxyEgressTools =
    setOf(
        "execute_code",
        "shell",
        "execute_command",
        "docker",
        "process_manager",
        "plugin_hook",
        "codex_delegate",
        "claude_code_delegate",
    )

private val remoteServiceEgressTools =
    setOf(
        "send_email",
        "twilio",
        "send_message",
        "signal",
        "slack",
        "discord",
    )

private fun egressFor(
    toolName: String,
    risk: ToolRisk,
): ToolEgress =
    when {
        toolName in sandboxProxyEgressTools -> {
            ToolEgress.SANDBOX_PROXY
        }

        risk == ToolRisk.DEVICE_CONTROL -> {
            ToolEgress.DEVICE
        }

        toolName in remoteServiceEgressTools -> {
            ToolEgress.REMOTE_SERVICE
        }

        else -> {
            ToolEgress.NONE
        }
    }
