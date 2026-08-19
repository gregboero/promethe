package dev.promethe.core

import dev.promethe.api.PolicyDataSensitivity
import dev.promethe.api.PolicyDataTrust
import dev.promethe.api.ToolContractSource
import dev.promethe.api.ToolEgress
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.SecurityAuditLogRow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class PolicyLayer {
    SYSTEM,
    ORGANIZATION,
    PROJECT,
    SESSION,
}

enum class PolicyEffect {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY,
}

data class PolicyRule(
    val id: String,
    val version: String,
    val layer: PolicyLayer,
    val effect: PolicyEffect,
    val toolNames: Set<String> = emptySet(),
    val risks: Set<ToolRisk> = emptySet(),
    val origins: Set<ToolCallOrigin> = emptySet(),
    val egress: Set<ToolEgress> = emptySet(),
    val projectIds: Set<String> = emptySet(),
    val sessionIds: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "Policy rule id must not be blank" }
        require(version.isNotBlank()) { "Policy rule version must not be blank" }
    }

    fun matches(
        request: ToolExecutionRequest,
        contract: ToolContract,
        risk: ToolRisk,
    ): Boolean =
        (toolNames.isEmpty() || request.toolName in toolNames) &&
            (risks.isEmpty() || risk in risks) &&
            (origins.isEmpty() || request.origin in origins) &&
            (egress.isEmpty() || contract.egress in egress) &&
            (projectIds.isEmpty() || request.projectId in projectIds) &&
            (sessionIds.isEmpty() || request.sessionId in sessionIds)
}

data class PolicyMatch(
    val ruleId: String,
    val version: String,
    val layer: PolicyLayer,
    val effect: PolicyEffect,
    val reason: String,
)

data class PolicyDecision(
    val decisionId: String,
    val policyVersion: String,
    val effect: PolicyEffect,
    val risk: ToolRisk,
    val egress: ToolEgress,
    val matches: List<PolicyMatch>,
) {
    val requiresApproval: Boolean
        get() = effect == PolicyEffect.REQUIRE_APPROVAL

    val denialReason: String
        get() = matches.lastOrNull { it.effect == PolicyEffect.DENY }?.reason ?: "Policy denied execution"
}

/**
 * Deterministic policy engine. System rules are built in and cannot be removed
 * or weakened by organization, project, or session overlays.
 */
class PolicyKernel(
    rules: List<PolicyRule> = emptyList(),
) {
    init {
        require(rules.none { it.layer == PolicyLayer.SYSTEM }) {
            "System policy rules are immutable"
        }
    }

    private val overlayRules = rules.sortedWith(compareBy(PolicyRule::layer, PolicyRule::id))

    fun evaluate(
        request: ToolExecutionRequest,
        contract: ToolContract,
    ): PolicyDecision {
        val contractDecision = contract.evaluate(request.arguments)
        val matches = mutableListOf<PolicyMatch>()

        if (contractDecision.mandatoryApproval) {
            matches += systemMatch(
                id = "contract-approval",
                effect = PolicyEffect.REQUIRE_APPROVAL,
                reason = "The tool contract requires human approval",
            )
        }
        if (!contract.explicit && contract.source == ToolContractSource.FALLBACK) {
            matches += systemMatch(
                id = "unknown-tool-deny",
                effect = PolicyEffect.DENY,
                reason = "Unknown tools are denied until an explicit contract is registered",
            )
        }
        if (contract.ownerOnly && request.origin !in OWNER_AUTHORIZED_ORIGINS) {
            matches += systemMatch(
                id = "owner-only",
                effect = PolicyEffect.DENY,
                reason = "This tool is restricted to owner conversations",
            )
        }
        if (
            request.dataTrust == PolicyDataTrust.UNTRUSTED &&
            (contractDecision.risk != ToolRisk.READ || contract.egress != ToolEgress.NONE)
        ) {
            matches += systemMatch(
                id = "untrusted-no-effects",
                effect = PolicyEffect.DENY,
                reason = "Untrusted content cannot initiate effectful tools",
            )
        }
        if (request.dataSensitivity == PolicyDataSensitivity.SECRET && contract.egress != ToolEgress.NONE) {
            matches += systemMatch(
                id = "secret-no-egress",
                effect = PolicyEffect.DENY,
                reason = "Secret data cannot leave the local trust boundary",
            )
        }
        if (contract.egress == ToolEgress.UNKNOWN) {
            matches += systemMatch(
                id = "unknown-egress-approval",
                effect = PolicyEffect.REQUIRE_APPROVAL,
                reason = "Unknown egress requires human approval",
            )
        }

        overlayRules
            .filter { it.matches(request, contract, contractDecision.risk) }
            .forEach { rule ->
                matches +=
                    PolicyMatch(
                        ruleId = rule.id,
                        version = rule.version,
                        layer = rule.layer,
                        effect = rule.effect,
                        reason = "${rule.layer.name.lowercase()} policy '${rule.id}' matched",
                    )
            }

        val effect = matches.maxOfOrNull(PolicyMatch::effect) ?: PolicyEffect.ALLOW
        val decisionId =
            toolIntentDigest(
                buildString {
                    append("promethe-policy-decision-v1\u0000")
                    append(CURRENT_VERSION)
                    append('\u0000')
                    append(toolInvocationHash(request))
                    append('\u0000')
                    append(effect.name)
                    append('\u0000')
                    append(matches.joinToString(",") { it.ruleId })
                },
            )
        return PolicyDecision(
            decisionId = decisionId,
            policyVersion = CURRENT_VERSION,
            effect = effect,
            risk = contractDecision.risk,
            egress = contract.egress,
            matches = matches.toList(),
        )
    }

    private fun systemMatch(
        id: String,
        effect: PolicyEffect,
        reason: String,
    ): PolicyMatch =
        PolicyMatch(
            ruleId = id,
            version = CURRENT_VERSION,
            layer = PolicyLayer.SYSTEM,
            effect = effect,
            reason = reason,
        )

    companion object {
        const val CURRENT_VERSION = "promethe-policy-1"

        private val OWNER_AUTHORIZED_ORIGINS = setOf(ToolCallOrigin.A2A)
    }
}

fun interface PolicyAuditSink {
    suspend fun record(
        request: ToolExecutionRequest,
        contract: ToolContract,
        decision: PolicyDecision,
        createdAt: Long,
    ): Boolean
}

object NoOpPolicyAuditSink : PolicyAuditSink {
    override suspend fun record(
        request: ToolExecutionRequest,
        contract: ToolContract,
        decision: PolicyDecision,
        createdAt: Long,
    ): Boolean = true
}

class PersistentPolicyAuditSink(
    private val database: PrometheDatabaseApi,
) : PolicyAuditSink {
    override suspend fun record(
        request: ToolExecutionRequest,
        contract: ToolContract,
        decision: PolicyDecision,
        createdAt: Long,
    ): Boolean =
        runCatching {
            val detail =
                buildJsonObject {
                    put("decisionId", decision.decisionId)
                    put("policyVersion", decision.policyVersion)
                    put("tool", request.toolName)
                    put("origin", request.origin.name)
                    put("contractSource", contract.source.name)
                    put("risk", decision.risk.name)
                    put("egress", decision.egress.name)
                    put("effect", decision.effect.name)
                    put("invocationHash", toolInvocationHash(request))
                    put("matchedRules", decision.matches.joinToString(",") { it.ruleId })
                }.toString()
            database.insertSecurityAuditLog(
                SecurityAuditLogRow(
                    eventType = "tool_policy_decision",
                    actor = request.origin.name,
                    detail = detail,
                    createdAt = createdAt,
                ),
            )
            true
        }.getOrDefault(false)
}
