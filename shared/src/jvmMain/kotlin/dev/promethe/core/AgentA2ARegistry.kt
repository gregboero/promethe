package dev.promethe.core

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * AgentA2ARegistry — central catalog of all agents (local + remote).
 *
 * Every agent in the system is addressable via A2A protocol.
 * Local agents use A2ALoopbackTransport (in-process, <0.5ms).
 * Remote agents use A2AClientTool over HTTP/HTTPS.
 *
 * This registry is the ONLY way to communicate with agents.
 * Gateway, Orchestrator, DelegateTaskTool all go through here.
 */
class AgentA2ARegistry {
    private val mutex = Mutex()

    // Local agents: in-process, zero overhead
    private val localAgents = mutableMapOf<String, LocalAgent>()

    // Remote agents: HTTPS A2A endpoints
    private val remoteAgents = mutableMapOf<String, RemoteAgent>()

    // Shared HTTP client for remote A2A calls
    private val remoteClient: A2AClientTool by lazy {
        A2AClientTool(
            HttpClient {
                install(io.ktor.client.plugins.HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 5_000
                }
            },
        )
    }

    data class LocalAgent(
        val card: AgentCard,
        val executor: AgentExecutor,
        val transport: A2ALoopbackTransport,
    )

    data class RemoteAgent(
        val card: AgentCard,
        val url: String,
    )

    // ── Registration ──────────────────────────────────────────

    /**
     * Register a local agent (in-process, loopback transport).
     * This is the standard way to register agents at boot time.
     */
    suspend fun registerLocal(
        agentId: String,
        card: AgentCard,
        executor: AgentExecutor,
    ) {
        mutex.withLock {
            val transport = A2ALoopbackTransport(executor)
            localAgents[agentId] = LocalAgent(card, executor, transport)
        }
    }

    /**
     * Register a remote agent by its A2A endpoint URL.
     * The agent card is discovered via /.well-known/agent-card.json.
     */
    suspend fun registerRemote(
        agentId: String,
        url: String,
        card: AgentCard,
    ) {
        mutex.withLock {
            remoteAgents[agentId] = RemoteAgent(card, url)
        }
    }

    /**
     * Unregister an agent (local or remote).
     */
    suspend fun unregister(agentId: String) {
        mutex.withLock {
            localAgents.remove(agentId)
            remoteAgents.remove(agentId)
        }
    }

    // ── Task Execution ────────────────────────────────────────

    /**
     * Send a task to an agent and get the final result.
     * Automatically routes to loopback (local) or HTTP (remote).
     */
    suspend fun sendTask(
        agentId: String,
        userMessage: String,
        taskId: String = "task-${UUID.randomUUID()}",
    ): A2ATaskResult {
        val local = mutex.withLock { localAgents[agentId] }
        if (local != null) {
            val message =
                Message(
                    messageId = "msg-${UUID.randomUUID()}",
                    role = Role.User,
                    parts = listOf(TextPart(text = userMessage)),
                )
            return local.transport.sendTask(taskId, message)
        }

        val remote = mutex.withLock { remoteAgents[agentId] }
        if (remote != null) {
            val result =
                remoteClient.sendTask(
                    agentUrl = remote.url,
                    taskDescription = userMessage,
                    sessionId = taskId,
                )
            val status = if (result.success) "completed" else "failed"
            val responseText = if (result.success) result.response else "[Error] ${result.error}"
            return A2ATaskResult(
                taskId = taskId,
                response = responseText,
                events = listOf(A2AStreamEvent.StatusEvent(status, result.error)),
            )
        }

        throw IllegalArgumentException("Agent '$agentId' not found in registry. Available: ${listAgentIds()}")
    }

    /**
     * Send a task with streaming — returns a Flow of A2AStreamEvents.
     * The caller can collect events as they arrive (thoughts, actions, responses).
     */
    fun sendTaskStreaming(
        agentId: String,
        userMessage: String,
        taskId: String = "task-${UUID.randomUUID()}",
    ): Flow<A2AStreamEvent> {
        val local =
            localAgents[agentId]
                ?: throw IllegalArgumentException("Agent '$agentId' not found. Available: ${listAgentIds()}")

        val message =
            Message(
                messageId = "msg-${UUID.randomUUID()}",
                role = Role.User,
                parts = listOf(TextPart(text = userMessage)),
            )

        return local.transport.sendTaskStreaming(taskId, message)
    }

    // ── Discovery ─────────────────────────────────────────────

    /**
     * Get the AgentCard for a specific agent.
     */
    suspend fun getAgentCard(agentId: String): AgentCard? =
        mutex.withLock {
            localAgents[agentId]?.card ?: remoteAgents[agentId]?.card
        }

    /**
     * List all registered agent IDs.
     */
    fun listAgentIds(): List<String> = localAgents.keys.toList() + remoteAgents.keys.toList()

    /**
     * List all agent cards (for discovery / agent-card.json).
     */
    suspend fun listAgentCards(): List<AgentCard> =
        mutex.withLock {
            localAgents.values.map { it.card } + remoteAgents.values.map { it.card }
        }

    /**
     * Find agents that have a specific skill.
     */
    suspend fun findBySkill(skillId: String): List<Pair<String, AgentCard>> =
        mutex.withLock {
            val results = mutableListOf<Pair<String, AgentCard>>()
            for ((id, agent) in localAgents) {
                if (agent.card.skills.any { it.id == skillId }) {
                    results.add(id to agent.card)
                }
            }
            for ((id, agent) in remoteAgents) {
                if (agent.card.skills.any { it.id == skillId }) {
                    results.add(id to agent.card)
                }
            }
            results
        }

    /**
     * Check if an agent is local (loopback) or remote (HTTP).
     */
    suspend fun isLocal(agentId: String): Boolean =
        mutex.withLock {
            agentId in localAgents
        }

    /**
     * Get the main agent (first registered, or "main" by convention).
     */
    suspend fun mainAgent(): LocalAgent? =
        mutex.withLock {
            localAgents["main"] ?: localAgents.values.firstOrNull()
        }
}
