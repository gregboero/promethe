package dev.promethe.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock

data class ResourceBudget(
    val maxTokens: Long = 200_000,
    val maxCostDollars: Double = 5.0,
    val maxLlmCalls: Int = 20,
    val maxToolStarts: Int = 50,
    val maxSubAgents: Int = 8,
    val maxDurationMs: Long = 30 * 60 * 1_000L,
) {
    init {
        require(maxTokens >= 0) { "Token budget must not be negative" }
        require(maxCostDollars >= 0.0 && maxCostDollars.isFinite()) { "Cost budget must be finite and non-negative" }
        require(maxLlmCalls >= 0) { "LLM call budget must not be negative" }
        require(maxToolStarts >= 0) { "Tool start budget must not be negative" }
        require(maxSubAgents >= 0) { "Sub-agent budget must not be negative" }
        require(maxDurationMs >= 0) { "Duration budget must not be negative" }
    }

    companion object {
        val DEFAULT = ResourceBudget()
    }
}

@Serializable
enum class GovernedResource {
    LLM_CALL,
    TOOL_START,
    SUB_AGENT,
}

enum class ResourceLimit {
    TOKENS,
    COST,
    LLM_CALLS,
    TOOL_STARTS,
    SUB_AGENTS,
    DURATION,
    AGGREGATE_STARTS,
}

sealed interface ResourceAdmission {
    data class Allowed(
        val snapshot: ResourceUsageSnapshot,
    ) : ResourceAdmission

    data class Denied(
        val limit: ResourceLimit,
        val snapshot: ResourceUsageSnapshot,
        val quota: ResourceQuotaUsage? = null,
    ) : ResourceAdmission
}

data class ResourceUsageSnapshot(
    val rootRunId: String,
    val budget: ResourceBudget,
    val tokensUsed: Long,
    val costDollars: Double,
    val llmCallsStarted: Int,
    val toolsStarted: Int,
    val subAgentsStarted: Int,
    val elapsedMs: Long,
)

data class ResourceGovernorState(
    val rootRunId: String,
    val budget: ResourceBudget,
    val startedAt: Long,
    val tokensUsed: Long = 0,
    val costDollars: Double = 0.0,
    val llmCallsStarted: Int = 0,
    val toolsStarted: Int = 0,
    val subAgentsStarted: Int = 0,
    val version: Long = 0,
) {
    init {
        require(tokensUsed >= 0) { "Persisted token usage must not be negative" }
        require(costDollars >= 0.0 && costDollars.isFinite()) { "Persisted cost must be finite and non-negative" }
        require(llmCallsStarted >= 0) { "Persisted LLM starts must not be negative" }
        require(toolsStarted >= 0) { "Persisted tool starts must not be negative" }
        require(subAgentsStarted >= 0) { "Persisted sub-agent starts must not be negative" }
        require(version >= 0) { "Persisted resource version must not be negative" }
    }
}

fun interface ResourceGovernorStateWriter {
    suspend fun persist(
        expectedVersion: Long,
        state: ResourceGovernorState,
    ): Boolean
}

class ResourceGovernor(
    val rootRunId: String,
    val budget: ResourceBudget,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    initialState: ResourceGovernorState? = null,
    private val stateWriter: ResourceGovernorStateWriter? = null,
    private val aggregateQuotas: ResourceQuotaBank = ResourceQuotaBank.NONE,
) {
    private val mutex = Mutex()
    private val startedAt = initialState?.startedAt ?: now()
    private var tokensUsed = initialState?.tokensUsed ?: 0L
    private var costDollars = initialState?.costDollars ?: 0.0
    private var llmCallsStarted = initialState?.llmCallsStarted ?: 0
    private var toolsStarted = initialState?.toolsStarted ?: 0
    private var subAgentsStarted = initialState?.subAgentsStarted ?: 0
    private var version = initialState?.version ?: 0L

    init {
        require(initialState == null || initialState.rootRunId == rootRunId) { "Persisted resource state belongs to another run" }
        require(initialState == null || initialState.budget == budget) { "Persisted resource budget does not match the requested budget" }
    }

    suspend fun admit(
        resource: GovernedResource,
        scope: ResourceQuotaScope = ResourceQuotaScope(),
    ): ResourceAdmission =
        mutex.withLock {
            val deniedLimit = exhaustedLimit()
            if (deniedLimit != null) return@withLock ResourceAdmission.Denied(deniedLimit, snapshotUnsafe())
            val countLimit = countLimit(resource)
            if (countLimit != null) return@withLock ResourceAdmission.Denied(countLimit, snapshotUnsafe())

            aggregateQuotas.reserve(resource, scope)?.let { quota ->
                return@withLock ResourceAdmission.Denied(ResourceLimit.AGGREGATE_STARTS, snapshotUnsafe(), quota)
            }

            val nextLlmCalls = llmCallsStarted.incrementIf(resource == GovernedResource.LLM_CALL)
            val nextTools = toolsStarted.incrementIf(resource == GovernedResource.TOOL_START)
            val nextSubAgents = subAgentsStarted.incrementIf(resource == GovernedResource.SUB_AGENT)
            persistNext(
                tokens = tokensUsed,
                cost = costDollars,
                llmCalls = nextLlmCalls,
                tools = nextTools,
                subAgents = nextSubAgents,
            )
            when (resource) {
                GovernedResource.LLM_CALL -> llmCallsStarted++
                GovernedResource.TOOL_START -> toolsStarted++
                GovernedResource.SUB_AGENT -> subAgentsStarted++
            }
            ResourceAdmission.Allowed(snapshotUnsafe())
        }

    suspend fun recordLlmUsage(
        tokens: Long,
        cost: Double,
    ): ResourceUsageSnapshot =
        mutex.withLock {
            require(tokens >= 0) { "Recorded token usage must not be negative" }
            require(cost >= 0.0 && cost.isFinite()) { "Recorded cost must be finite and non-negative" }
            val nextTokens = if (Long.MAX_VALUE - tokensUsed < tokens) Long.MAX_VALUE else tokensUsed + tokens
            val nextCost = (costDollars + cost).takeIf(Double::isFinite) ?: Double.MAX_VALUE
            persistNext(
                tokens = nextTokens,
                cost = nextCost,
                llmCalls = llmCallsStarted,
                tools = toolsStarted,
                subAgents = subAgentsStarted,
            )
            tokensUsed = nextTokens
            costDollars = nextCost
            snapshotUnsafe()
        }

    suspend fun snapshot(): ResourceUsageSnapshot = mutex.withLock { snapshotUnsafe() }

    private fun exhaustedLimit(): ResourceLimit? =
        when {
            budget.maxTokens > 0 && tokensUsed >= budget.maxTokens -> ResourceLimit.TOKENS
            budget.maxCostDollars > 0.0 && costDollars >= budget.maxCostDollars -> ResourceLimit.COST
            budget.maxDurationMs > 0 && elapsedMs() >= budget.maxDurationMs -> ResourceLimit.DURATION
            else -> null
        }

    private fun countLimit(resource: GovernedResource): ResourceLimit? =
        when (resource) {
            GovernedResource.LLM_CALL -> {
                ResourceLimit.LLM_CALLS.takeIf { budget.maxLlmCalls > 0 && llmCallsStarted >= budget.maxLlmCalls }
            }

            GovernedResource.TOOL_START -> {
                ResourceLimit.TOOL_STARTS.takeIf { budget.maxToolStarts > 0 && toolsStarted >= budget.maxToolStarts }
            }

            GovernedResource.SUB_AGENT -> {
                ResourceLimit.SUB_AGENTS.takeIf { budget.maxSubAgents > 0 && subAgentsStarted >= budget.maxSubAgents }
            }
        }

    private fun snapshotUnsafe(): ResourceUsageSnapshot =
        ResourceUsageSnapshot(
            rootRunId = rootRunId,
            budget = budget,
            tokensUsed = tokensUsed,
            costDollars = costDollars,
            llmCallsStarted = llmCallsStarted,
            toolsStarted = toolsStarted,
            subAgentsStarted = subAgentsStarted,
            elapsedMs = elapsedMs(),
        )

    private suspend fun persistNext(
        tokens: Long,
        cost: Double,
        llmCalls: Int,
        tools: Int,
        subAgents: Int,
    ) {
        val writer = stateWriter ?: return
        val expectedVersion = version
        val nextState =
            ResourceGovernorState(
                rootRunId = rootRunId,
                budget = budget,
                startedAt = startedAt,
                tokensUsed = tokens,
                costDollars = cost,
                llmCallsStarted = llmCalls,
                toolsStarted = tools,
                subAgentsStarted = subAgents,
                version = expectedVersion + 1,
            )
        check(writer.persist(expectedVersion, nextState)) {
            "Resource budget state changed concurrently or could not be persisted"
        }
        version = nextState.version
    }

    private fun elapsedMs(): Long = (now() - startedAt).coerceAtLeast(0)
}

private fun Int.incrementIf(condition: Boolean): Int = if (!condition || this == Int.MAX_VALUE) this else this + 1

sealed interface ResourceGovernorAcquisition {
    data class Acquired(
        val governor: ResourceGovernor,
    ) : ResourceGovernorAcquisition

    data class Conflict(
        val activeRunId: String,
    ) : ResourceGovernorAcquisition
}

sealed interface ChildResourceBinding {
    data class Bound(
        val snapshot: ResourceUsageSnapshot,
    ) : ChildResourceBinding

    data class Denied(
        val admission: ResourceAdmission.Denied,
    ) : ChildResourceBinding

    data object ParentNotFound : ChildResourceBinding

    data object ChildConflict : ChildResourceBinding
}

interface ResourceGovernorRegistry {
    fun quotaPolicies(): List<ResourceQuotaRule> = emptyList()

    suspend fun admit(
        runId: String?,
        resource: GovernedResource,
        scope: ResourceQuotaScope = ResourceQuotaScope(),
    ): ResourceAdmission? = runId?.let { governorForRun(it)?.admit(resource, scope) }

    suspend fun quotaSnapshot(): List<ResourceQuotaUsage> = emptyList()

    suspend fun acquire(
        runId: String,
        sessionId: String,
        budget: ResourceBudget,
    ): ResourceGovernorAcquisition

    suspend fun governorForRun(runId: String): ResourceGovernor?

    suspend fun bindChild(
        parentSessionId: String,
        childSessionId: String,
    ): ChildResourceBinding

    suspend fun unbindSession(sessionId: String)

    suspend fun release(
        runId: String,
        sessionId: String,
    )
}

class InMemoryResourceGovernorRegistry : ResourceGovernorRegistry {
    private data class RunBinding(
        val governor: ResourceGovernor,
        val sessionId: String,
    )

    private data class SessionBinding(
        val governor: ResourceGovernor,
        val runId: String?,
    )

    private val mutex = Mutex()
    private val byRun = mutableMapOf<String, RunBinding>()
    private val bySession = mutableMapOf<String, SessionBinding>()

    override suspend fun acquire(
        runId: String,
        sessionId: String,
        budget: ResourceBudget,
    ): ResourceGovernorAcquisition =
        mutex.withLock {
            byRun[runId]?.let { binding ->
                return@withLock if (binding.sessionId == sessionId) {
                    ResourceGovernorAcquisition.Acquired(binding.governor)
                } else {
                    ResourceGovernorAcquisition.Conflict(runId)
                }
            }
            val existing = bySession[sessionId]
            if (existing?.runId != null && existing.runId != runId) {
                return@withLock ResourceGovernorAcquisition.Conflict(existing.runId)
            }
            val governor = existing?.governor ?: ResourceGovernor(runId, budget)
            byRun[runId] = RunBinding(governor, sessionId)
            bySession[sessionId] = SessionBinding(governor, runId)
            ResourceGovernorAcquisition.Acquired(governor)
        }

    override suspend fun governorForRun(runId: String): ResourceGovernor? = mutex.withLock { byRun[runId]?.governor }

    override suspend fun bindChild(
        parentSessionId: String,
        childSessionId: String,
    ): ChildResourceBinding =
        mutex.withLock {
            val governor = bySession[parentSessionId]?.governor ?: return@withLock ChildResourceBinding.ParentNotFound
            if (childSessionId in bySession) return@withLock ChildResourceBinding.ChildConflict
            when (val admission = governor.admit(GovernedResource.SUB_AGENT)) {
                is ResourceAdmission.Allowed -> {
                    bySession[childSessionId] = SessionBinding(governor, null)
                    ChildResourceBinding.Bound(admission.snapshot)
                }

                is ResourceAdmission.Denied -> {
                    ChildResourceBinding.Denied(admission)
                }
            }
        }

    override suspend fun unbindSession(sessionId: String) {
        mutex.withLock {
            val binding = bySession[sessionId] ?: return@withLock
            if (binding.runId == null) bySession.remove(sessionId)
        }
    }

    override suspend fun release(
        runId: String,
        sessionId: String,
    ) {
        mutex.withLock {
            val binding = byRun[runId]
            if (binding?.sessionId == sessionId) byRun.remove(runId)
            if (bySession[sessionId]?.runId == runId) bySession.remove(sessionId)
        }
    }
}

object GlobalResourceGovernorRegistry : ResourceGovernorRegistry by InMemoryResourceGovernorRegistry()

internal fun ResourceAdmission.Denied.message(): String =
    quota?.let { "Aggregate start quota exceeded: ${it.ruleId} (${it.dimension.name.lowercase()}/${it.subject}, used=${it.used}, max=${it.maxStarts}, resetsAt=${it.resetsAt})" }
        ?: (
            "Resource budget exceeded: ${limit.name.lowercase()} " +
                "(tokens=${snapshot.tokensUsed}, cost=${snapshot.costDollars}, llm=${snapshot.llmCallsStarted}, " +
                "tools=${snapshot.toolsStarted}, subAgents=${snapshot.subAgentsStarted}, elapsedMs=${snapshot.elapsedMs})"
        )
