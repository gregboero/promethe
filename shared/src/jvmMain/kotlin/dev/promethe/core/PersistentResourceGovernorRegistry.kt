package dev.promethe.core

import dev.promethe.api.AgentRunStatus
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.ResourceGovernorBindingRow
import dev.promethe.db.ResourceGovernorStateRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * SQLite-backed registry for the budget shared by a root run and its child runs.
 * Every admission is persisted before the caller may start the governed work.
 */
class PersistentResourceGovernorRegistry(
    private val database: PrometheDatabaseApi,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val aggregateQuotas: ResourceQuotaBank = ResourceQuotaBank.NONE,
) : ResourceGovernorRegistry {
    override fun quotaPolicies(): List<ResourceQuotaRule> = aggregateQuotas.policies()

    override suspend fun admit(
        runId: String?,
        resource: GovernedResource,
        scope: ResourceQuotaScope,
    ): ResourceAdmission? {
        val governor = runId?.let { governorForRun(it) }
        if (governor != null) return governor.admit(resource, scope)
        // Auxiliary model calls and direct tool invocations also consume profile-wide limits.
        val quota = aggregateQuotas.reserve(resource, scope) ?: return null
        val snapshot = ResourceUsageSnapshot(runId ?: "unbound", ResourceBudget.DEFAULT, 0, 0.0, 0, 0, 0, 0)
        return ResourceAdmission.Denied(ResourceLimit.AGGREGATE_STARTS, snapshot, quota)
    }

    override suspend fun quotaSnapshot(): List<ResourceQuotaUsage> = aggregateQuotas.snapshot()

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

            var persistedBinding = database.getResourceGovernorBinding(sessionId)
            if (persistedBinding?.runId != null && persistedBinding.runId != runId) {
                val boundRunId = persistedBinding.runId
                val boundRun = database.getAgentRun(boundRunId)
                if (boundRun?.status in TERMINAL_RUN_STATUSES) {
                    database.deleteResourceGovernorBinding(sessionId, boundRunId)
                    persistedBinding = null
                } else {
                    return@withLock ResourceGovernorAcquisition.Conflict(boundRunId)
                }
            }

            val rootRunId = persistedBinding?.rootRunId ?: runId
            val governor =
                if (persistedBinding == null) {
                    loadOrCreateGovernor(rootRunId, budget)
                } else {
                    requireNotNull(loadGovernor(rootRunId)) {
                        "Resource binding for '$sessionId' references missing budget '$rootRunId'"
                    }.also { persisted ->
                        require(rootRunId != runId || persisted.budget == budget) {
                            "Persisted resource budget does not match run '$rootRunId'"
                        }
                    }
                }
            val bindingClaimed =
                when {
                    persistedBinding == null -> {
                        val timestamp = now()
                        database.insertResourceGovernorBinding(
                            ResourceGovernorBindingRow(
                                sessionId = sessionId,
                                rootRunId = rootRunId,
                                runId = runId,
                                createdAt = timestamp,
                                updatedAt = timestamp,
                            ),
                        )
                    }

                    persistedBinding.runId == runId -> {
                        true
                    }

                    else -> {
                        database.claimResourceGovernorBinding(
                            sessionId = sessionId,
                            expectedRunId = null,
                            runId = runId,
                            updatedAt = now(),
                        )
                    }
                }
            if (!bindingClaimed) {
                val activeRunId = database.getResourceGovernorBinding(sessionId)?.runId ?: runId
                return@withLock ResourceGovernorAcquisition.Conflict(activeRunId)
            }

            byRun[runId] = RunBinding(governor, sessionId)
            bySession[sessionId] = SessionBinding(governor, runId)
            ResourceGovernorAcquisition.Acquired(governor)
        }

    override suspend fun governorForRun(runId: String): ResourceGovernor? =
        mutex.withLock {
            byRun[runId]?.governor ?: run {
                val binding = database.getResourceGovernorBindingForRun(runId) ?: return@withLock null
                val governor = loadGovernor(binding.rootRunId) ?: return@withLock null
                byRun[runId] = RunBinding(governor, binding.sessionId)
                bySession[binding.sessionId] = SessionBinding(governor, runId)
                governor
            }
        }

    override suspend fun bindChild(
        parentSessionId: String,
        childSessionId: String,
    ): ChildResourceBinding =
        mutex.withLock {
            if (bySession[childSessionId] != null || database.getResourceGovernorBinding(childSessionId) != null) {
                return@withLock ChildResourceBinding.ChildConflict
            }
            val parent = sessionBinding(parentSessionId) ?: return@withLock ChildResourceBinding.ParentNotFound
            when (val admission = parent.governor.admit(GovernedResource.SUB_AGENT)) {
                is ResourceAdmission.Denied -> {
                    ChildResourceBinding.Denied(admission)
                }

                is ResourceAdmission.Allowed -> {
                    val timestamp = now()
                    val inserted =
                        database.insertResourceGovernorBinding(
                            ResourceGovernorBindingRow(
                                sessionId = childSessionId,
                                rootRunId = parent.governor.rootRunId,
                                runId = null,
                                createdAt = timestamp,
                                updatedAt = timestamp,
                            ),
                        )
                    if (!inserted) {
                        ChildResourceBinding.ChildConflict
                    } else {
                        bySession[childSessionId] = SessionBinding(parent.governor, null)
                        ChildResourceBinding.Bound(admission.snapshot)
                    }
                }
            }
        }

    override suspend fun unbindSession(sessionId: String) {
        mutex.withLock {
            val binding =
                bySession[sessionId]
                    ?: database.getResourceGovernorBinding(sessionId)?.let { persisted ->
                        loadSessionBinding(persisted)
                    }
            if (binding?.runId == null) {
                database.deleteResourceGovernorBinding(sessionId, null)
                bySession.remove(sessionId)
            }
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
            database.deleteResourceGovernorBinding(sessionId, runId)
        }
    }

    private suspend fun sessionBinding(sessionId: String): SessionBinding? {
        bySession[sessionId]?.let { return it }
        val persisted = database.getResourceGovernorBinding(sessionId) ?: return null
        return loadSessionBinding(persisted)
    }

    private suspend fun loadSessionBinding(binding: ResourceGovernorBindingRow): SessionBinding? {
        val governor = loadGovernor(binding.rootRunId) ?: return null
        val sessionBinding = SessionBinding(governor, binding.runId)
        bySession[binding.sessionId] = sessionBinding
        binding.runId?.let { byRun[it] = RunBinding(governor, binding.sessionId) }
        return sessionBinding
    }

    private suspend fun loadOrCreateGovernor(
        rootRunId: String,
        budget: ResourceBudget,
    ): ResourceGovernor {
        loadGovernor(rootRunId)?.let { governor ->
            require(governor.budget == budget) {
                "Persisted resource budget does not match run '$rootRunId'"
            }
            return governor
        }
        val timestamp = now()
        val initial =
            ResourceGovernorState(
                rootRunId = rootRunId,
                budget = budget,
                startedAt = timestamp,
            )
        val inserted = database.insertResourceGovernorState(initial.toRow(timestamp))
        val persisted = if (inserted) initial else database.getResourceGovernorState(rootRunId)?.toState()
        val restored = requireNotNull(persisted) { "Failed to initialize resource budget for '$rootRunId'" }
        require(restored.budget == budget) { "Persisted resource budget does not match run '$rootRunId'" }
        return createGovernor(restored)
    }

    private suspend fun loadGovernor(rootRunId: String): ResourceGovernor? =
        byRun.values.firstOrNull { it.governor.rootRunId == rootRunId }?.governor
            ?: bySession.values.firstOrNull { it.governor.rootRunId == rootRunId }?.governor
            ?: database.getResourceGovernorState(rootRunId)?.toState()?.let(::createGovernor)

    private fun createGovernor(state: ResourceGovernorState): ResourceGovernor =
        ResourceGovernor(
            rootRunId = state.rootRunId,
            budget = state.budget,
            now = now,
            initialState = state,
            stateWriter =
                ResourceGovernorStateWriter { expectedVersion, next ->
                    database.updateResourceGovernorState(expectedVersion, next.toRow(now()))
                },
            aggregateQuotas = aggregateQuotas,
        )
}

private val TERMINAL_RUN_STATUSES =
    setOf(
        AgentRunStatus.SUCCEEDED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED,
    )

private fun ResourceGovernorState.toRow(updatedAt: Long) =
    ResourceGovernorStateRow(
        rootRunId = rootRunId,
        maxTokens = budget.maxTokens,
        maxCostDollars = budget.maxCostDollars,
        maxLlmCalls = budget.maxLlmCalls,
        maxToolStarts = budget.maxToolStarts,
        maxSubAgents = budget.maxSubAgents,
        maxDurationMs = budget.maxDurationMs,
        startedAt = startedAt,
        tokensUsed = tokensUsed,
        costDollars = costDollars,
        llmCallsStarted = llmCallsStarted,
        toolsStarted = toolsStarted,
        subAgentsStarted = subAgentsStarted,
        version = version,
        updatedAt = updatedAt,
    )

private fun ResourceGovernorStateRow.toState() =
    ResourceGovernorState(
        rootRunId = rootRunId,
        budget =
            ResourceBudget(
                maxTokens = maxTokens,
                maxCostDollars = maxCostDollars,
                maxLlmCalls = maxLlmCalls,
                maxToolStarts = maxToolStarts,
                maxSubAgents = maxSubAgents,
                maxDurationMs = maxDurationMs,
            ),
        startedAt = startedAt,
        tokensUsed = tokensUsed,
        costDollars = costDollars,
        llmCallsStarted = llmCallsStarted,
        toolsStarted = toolsStarted,
        subAgentsStarted = subAgentsStarted,
        version = version,
    )
