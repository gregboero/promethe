package dev.promethe.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import java.util.UUID

/**
 * AgentOrchestrator — manages sub-agent lifecycle.
 *
 * Capabilities:
 * - Spawn child agents with isolated sessions and optional profile overrides
 * - Track parent-child relationships
 * - Cascade cancellation (parent stop → children stop)
 * - Collect results from completed sub-agents
 * - Parallel delegation with configurable concurrency
 */
class AgentOrchestrator(
    private val llmAdapter: KoogLlmAdapter,
    private val database: dev.promethe.db.PrometheDatabaseApi,
    private val config: AgentConfig,
    private val profileManager: ProfileManager,
    private val actionExecutor: ActionExecutor,
    private val skillLoader: SkillLoader,
    private val skillWriter: SkillWriter,
    private val trajectoryEvaluator: TrajectoryEvaluator,
    private val registry: AgentA2ARegistry? = null,
    val ephemeralPromoter: EphemeralAgentPromoter = EphemeralAgentPromoter(database),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val resourceGovernors: ResourceGovernorRegistry = GlobalResourceGovernorRegistry,
) {
    private val mutex = Mutex()

    // Active sub-agent jobs: childSessionId -> Job
    private val activeJobs = mutableMapOf<String, Job>()

    // Parent-child mapping: parentSessionId -> list of childSessionIds
    private val parentChildren = mutableMapOf<String, MutableList<String>>()

    // Results: childSessionId -> final response
    private val results = mutableMapOf<String, SubAgentResult>()

    private val delegations = mutableMapOf<String, DelegationInfo>()

    data class DelegationInfo(
        val sessionId: String,
        val task: String,
        val profileId: String?,
        val startTime: Long,
    )

    data class SubAgentResult(
        val sessionId: String,
        val profileId: String?,
        val task: String,
        val response: String,
        val status: Status,
        val durationMs: Long,
    ) {
        enum class Status { SUCCESS, ERROR, CANCELLED }
    }

    data class DelegationRequest(
        val task: String,
        val systemPromptOverride: String? = null,
        val profileId: String? = null,
        val parentSessionId: String,
        val parentRunId: String? = null,
    )

    /**
     * Delegate a single task to a sub-agent.
     * Returns the child session ID immediately (non-blocking).
     */
    suspend fun delegateTask(request: DelegationRequest): String {
        val childSessionId = "sub-${UUID.randomUUID()}"
        val startTime = Clock.System.now().toEpochMilliseconds()

        if (request.parentRunId != null) {
            when (val binding = resourceGovernors.bindChild(request.parentSessionId, childSessionId)) {
                is ChildResourceBinding.Bound -> {
                    // The child acquires this same governor when its A2A run starts.
                }

                is ChildResourceBinding.Denied -> {
                    throw AgentExecutionException("resource_budget_exceeded", binding.admission.message())
                }

                ChildResourceBinding.ParentNotFound -> {
                    throw AgentExecutionException("resource_governor_unavailable", "Parent run resource governor is unavailable")
                }

                ChildResourceBinding.ChildConflict -> {
                    throw AgentExecutionException("resource_governor_conflict", "Sub-agent session is already governed")
                }
            }
        }

        // Register in DB
        try {
            database.insertSessionOrIgnore(
                id = childSessionId,
                createdAt = startTime,
                metadata = """{"parent":"${request.parentSessionId}","profileId":"${request.profileId ?: "default"}"}""",
            )
        } catch (error: Exception) {
            resourceGovernors.unbindSession(childSessionId)
            throw error
        }

        // Track parent-child
        mutex.withLock {
            parentChildren.getOrPut(request.parentSessionId) { mutableListOf() }.add(childSessionId)
            delegations[childSessionId] = DelegationInfo(
                sessionId = childSessionId,
                task = request.task,
                profileId = request.profileId,
                startTime = startTime,
            )
        }

        // Launch sub-agent in background
        val job =
            scope.launch {
                try {
                    val response = executeSubAgent(childSessionId, request)
                    mutex.withLock {
                        results[childSessionId] =
                            SubAgentResult(
                                sessionId = childSessionId,
                                profileId = request.profileId,
                                task = request.task,
                                response = response,
                                status = SubAgentResult.Status.SUCCESS,
                                durationMs = Clock.System.now().toEpochMilliseconds() - startTime,
                            )
                    }
                    // GEPA integration: track ephemeral agent usage for auto-promotion
                    request.profileId?.let { ephemeralPromoter.onDelegationComplete(it, success = true) }
                } catch (e: CancellationException) {
                    mutex.withLock {
                        results[childSessionId] =
                            SubAgentResult(
                                sessionId = childSessionId,
                                profileId = request.profileId,
                                task = request.task,
                                response = "Task cancelled",
                                status = SubAgentResult.Status.CANCELLED,
                                durationMs = Clock.System.now().toEpochMilliseconds() - startTime,
                            )
                    }
                } catch (e: Exception) {
                    mutex.withLock {
                        results[childSessionId] =
                            SubAgentResult(
                                sessionId = childSessionId,
                                profileId = request.profileId,
                                task = request.task,
                                response = "Error: ${e.message}",
                                status = SubAgentResult.Status.ERROR,
                                durationMs = Clock.System.now().toEpochMilliseconds() - startTime,
                            )
                    }
                    // Track failures too for success rate calculation
                    request.profileId?.let { ephemeralPromoter.onDelegationComplete(it, success = false) }
                } finally {
                    resourceGovernors.unbindSession(childSessionId)
                }
            }

        mutex.withLock {
            activeJobs[childSessionId] = job
        }

        return childSessionId
    }

    /**
     * Delegate multiple tasks in parallel, wait for all to complete.
     */
    suspend fun delegateParallel(
        requests: List<DelegationRequest>,
        maxConcurrency: Int = 4,
    ): List<SubAgentResult> {
        val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrency)
        val childIds = mutableListOf<String>()

        // Launch all
        for (request in requests) {
            semaphore.acquire()
            val childId = delegateTask(request)
            childIds.add(childId)
            // Release semaphore when job completes
            scope.launch {
                try {
                    activeJobs[childId]?.join()
                } finally {
                    semaphore.release()
                }
            }
        }

        // Wait for all children
        for (childId in childIds) {
            activeJobs[childId]?.join()
        }

        // Collect results
        return mutex.withLock {
            childIds.mapNotNull { results[it] }
        }
    }

    /**
     * Cancel a specific sub-agent and all its descendants.
     */
    suspend fun cancelSubAgent(childSessionId: String) {
        mutex.withLock {
            // Cancel the job
            activeJobs[childSessionId]?.cancel()
            activeJobs.remove(childSessionId)

            // Cascade: cancel all grandchildren
            parentChildren[childSessionId]?.forEach { grandchildId ->
                activeJobs[grandchildId]?.cancel()
                activeJobs.remove(grandchildId)
            }
            parentChildren.remove(childSessionId)
        }
    }

    /**
     * Cancel all children of a parent session (cascade).
     */
    suspend fun cancelAllChildren(parentSessionId: String) {
        mutex.withLock {
            val children = parentChildren[parentSessionId] ?: return@withLock
            for (childId in children) {
                activeJobs[childId]?.cancel()
                activeJobs.remove(childId)
                // Recurse for grandchildren
                parentChildren[childId]?.forEach { grandchildId ->
                    activeJobs[grandchildId]?.cancel()
                    activeJobs.remove(grandchildId)
                }
                parentChildren.remove(childId)
            }
            parentChildren.remove(parentSessionId)
        }
    }

    /**
     * Get the result of a completed sub-agent.
     */
    suspend fun getResult(childSessionId: String): SubAgentResult? =
        mutex.withLock {
            results[childSessionId]
        }

    /**
     * Check if a sub-agent is still running.
     */
    suspend fun isRunning(childSessionId: String): Boolean =
        mutex.withLock {
            activeJobs[childSessionId]?.isActive == true
        }

    /**
     * List all active sub-agents for a parent.
     */
    suspend fun listChildren(parentSessionId: String): List<String> =
        mutex.withLock {
            parentChildren[parentSessionId]?.toList() ?: emptyList()
        }

    /**
     * Execute a sub-agent: isolated session, its own deliberation loop.
     * Uses profile-based routing if profileId is specified.
     */
    private suspend fun executeSubAgent(
        childSessionId: String,
        request: DelegationRequest,
    ): String {
        val targetAgentId = request.profileId ?: "main"
        val activeRegistry = registry
            ?: error("Agent delegation requires the A2A registry")
        require(targetAgentId in activeRegistry.listAgentIds()) {
            "A2A agent '$targetAgentId' is not registered"
        }

        return activeRegistry.sendTask(
            agentId = targetAgentId,
            userMessage = request.task,
            taskId = childSessionId,
        ).response
    }

    /**
     * Shut down the orchestrator and cancel all active sub-agents.
     */
    fun shutdown() {
        scope.cancel()
        activeJobs.clear()
        parentChildren.clear()
    }

    data class SubAgentStatus(
        val sessionId: String,
        val profileId: String?,
        val task: String,
        val status: String,
        val response: String?,
        val durationMs: Long?,
    )

    suspend fun getActiveCount(): Int =
        mutex.withLock {
            activeJobs.filter { it.value.isActive }.size
        }

    suspend fun getCompletedCount(): Int =
        mutex.withLock {
            results.size
        }

    suspend fun getSubAgentsStatus(): List<SubAgentStatus> =
        mutex.withLock {
            delegations.map { (sessionId, info) ->
                val result = results[sessionId]
                val job = activeJobs[sessionId]
                val statusStr = when {
                    result != null -> result.status.name
                    job != null && job.isActive -> "RUNNING"
                    else -> "UNKNOWN"
                }
                SubAgentStatus(
                    sessionId = sessionId,
                    profileId = info.profileId,
                    task = info.task,
                    status = statusStr,
                    response = result?.response,
                    durationMs = result?.durationMs ?: (Clock.System.now().toEpochMilliseconds() - info.startTime),
                )
            }
        }

    suspend fun getSubAgentStatus(sessionId: String): SubAgentStatus? =
        mutex.withLock {
            val info = delegations[sessionId] ?: return@withLock null
            val result = results[sessionId]
            val job = activeJobs[sessionId]
            val statusStr = when {
                result != null -> result.status.name
                job != null && job.isActive -> "RUNNING"
                else -> "UNKNOWN"
            }
            SubAgentStatus(
                sessionId = sessionId,
                profileId = info.profileId,
                task = info.task,
                status = statusStr,
                response = result?.response,
                durationMs = result?.durationMs ?: (Clock.System.now().toEpochMilliseconds() - info.startTime),
            )
        }
}
