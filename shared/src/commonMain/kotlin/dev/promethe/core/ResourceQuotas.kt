package dev.promethe.core

import kotlinx.serialization.Serializable

@Serializable
enum class ResourceQuotaDimension { OWNER, PROVIDER, TOOL }

/** Process-owned policy. Zero denies every start; an absent rule imposes no aggregate limit. */
@Serializable
data class ResourceQuotaRule(
    val id: String,
    val dimension: ResourceQuotaDimension,
    val resource: GovernedResource,
    val maxStarts: Long,
    val windowSeconds: Long = 86_400,
    val selector: String = "*",
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_.-]{1,64}"))) { "Quota id must contain 1..64 letters, digits, dots, dashes or underscores" }
        require(maxStarts in 0..1_000_000_000) { "Quota maxStarts must be in 0..1000000000" }
        require(windowSeconds in 60..31_536_000) { "Quota windowSeconds must be between one minute and one year" }
        require(selector == "*" || selector.matches(Regex("[a-zA-Z0-9_.:/-]{1,128}"))) { "Invalid quota selector" }
        require(dimension != ResourceQuotaDimension.OWNER || selector == "*") { "Owner rules apply to the current local profile" }
        require(dimension != ResourceQuotaDimension.PROVIDER || resource == GovernedResource.LLM_CALL) { "Provider quotas govern LLM calls" }
        require(dimension != ResourceQuotaDimension.TOOL || resource == GovernedResource.TOOL_START) { "Tool quotas govern tool starts" }
    }
}

data class ResourceQuotaScope(
    val provider: String? = null,
    val toolName: String? = null,
)

@Serializable
data class ResourceQuotaUsage(
    val ruleId: String,
    val dimension: ResourceQuotaDimension,
    val resource: GovernedResource,
    val subject: String,
    val used: Long,
    val maxStarts: Long,
    val windowStartedAt: Long,
    val resetsAt: Long,
)

interface ResourceQuotaBank {
    fun policies(): List<ResourceQuotaRule> = emptyList()

    /** Reserve all matching dimensions atomically. A denial charges none of them. */
    suspend fun reserve(
        resource: GovernedResource,
        scope: ResourceQuotaScope,
    ): ResourceQuotaUsage?

    suspend fun snapshot(): List<ResourceQuotaUsage>

    companion object {
        val NONE = object : ResourceQuotaBank {
            override suspend fun reserve(
                resource: GovernedResource,
                scope: ResourceQuotaScope,
            ): ResourceQuotaUsage? = null

            override suspend fun snapshot(): List<ResourceQuotaUsage> = emptyList()
        }
    }
}
