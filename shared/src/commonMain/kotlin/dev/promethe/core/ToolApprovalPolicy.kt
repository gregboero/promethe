package dev.promethe.core

import kotlinx.serialization.json.JsonObject

/** Compatibility facade over the declarative [ToolContractRegistry]. */
object ToolApprovalPolicy {
    fun evaluate(
        toolName: String,
        arguments: JsonObject,
    ): ToolPolicyDecision = ToolContractRegistry.evaluate(toolName, arguments)

    fun requiresMandatoryApproval(
        toolName: String,
        arguments: JsonObject,
    ): Boolean = evaluate(toolName, arguments).mandatoryApproval

    fun catalogRisk(toolName: String): ToolRisk = ToolContractRegistry.contractFor(toolName).catalogRisk

    fun contractFor(toolName: String): ToolContract = ToolContractRegistry.contractFor(toolName)

    internal fun certifyReadOnlyMcpTool(toolName: String) {
        ToolContractRegistry.certifyReadOnlyMcpTool(toolName)
    }

    internal fun revokeReadOnlyMcpToolCertification(toolName: String) {
        ToolContractRegistry.revokeReadOnlyMcpToolCertification(toolName)
    }

    /** Compatibility snapshot for callers that cannot yet provide arguments. */
    val dangerousTools: Set<String>
        get() = ToolContractRegistry.approvalRequiredToolNames
}
