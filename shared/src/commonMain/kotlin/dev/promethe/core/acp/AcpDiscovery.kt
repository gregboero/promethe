package dev.promethe.core.acp

import dev.promethe.core.Log

import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ACP Discovery — service registry for remote ACP agents.
 *
 * Maintains a registry of known ACP agents with health checks.
 * Supports:
 *   - Manual registration (config-driven)
 *   - HTTP-based discovery (polling /.well-known/acp.json)
 *   - Health check with auto-removal of stale agents
 */
class AcpDiscovery(
    private val httpClient: HttpClient,
    private val apiKey: String = "",
) {
    private val logger = Log.create("AcpDiscovery")
    private val registry = mutableMapOf<String, AcpRegistryEntry>()
    private val mutex = Mutex()
    private val client = AcpClient(httpClient, apiKey)

    /**
     * Register a known agent URL.
     * Attempts discovery to fetch the agent card.
     */
    suspend fun register(agentUrl: String): AcpRegistryEntry? {
        val card = client.discover(agentUrl) ?: return null
        val entry = AcpRegistryEntry(
            agentId = card.id,
            url = agentUrl,
            lastSeen = System.currentTimeMillis(),
            healthy = true,
            card = card,
        )
        mutex.withLock {
            registry[card.id] = entry
        }
        logger.info { "Registered agent '${card.name}' (${card.capabilities.size} capabilities) at $agentUrl" }
        return entry
    }

    /**
     * Register multiple agent URLs (e.g., from config file).
     */
    suspend fun registerAll(urls: List<String>): List<AcpRegistryEntry> = urls.mapNotNull { register(it) }

    /**
     * Get all registered agents.
     */
    suspend fun getAgents(): List<AcpRegistryEntry> = mutex.withLock { registry.values.toList() }

    /**
     * Get a specific agent by ID.
     */
    suspend fun getAgent(agentId: String): AcpRegistryEntry? = mutex.withLock { registry[agentId] }

    /**
     * Remove an agent from the registry.
     */
    suspend fun unregister(agentId: String) {
        mutex.withLock { registry.remove(agentId) }
    }

    /**
     * Health-check all registered agents.
     * Marks agents as unhealthy if discovery fails.
     */
    suspend fun healthCheck() {
        val agents = mutex.withLock { registry.values.toList() }
        for (agent in agents) {
            val card = client.discover(agent.url)
            mutex.withLock {
                if (card != null) {
                    registry[agent.agentId] = agent.copy(
                        lastSeen = System.currentTimeMillis(),
                        healthy = true,
                        card = card,
                    )
                } else {
                    registry[agent.agentId] = agent.copy(healthy = false)
                }
            }
        }
    }
}
