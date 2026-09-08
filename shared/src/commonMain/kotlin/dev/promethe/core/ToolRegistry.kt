package dev.promethe.core

import ai.koog.agents.core.tools.ToolBase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Registry for Koog-native typed tools.
 * Stores [ToolBase] instances (the common supertype of Tool<I, O> / SimpleTool<I>),
 * keyed by their descriptor name.
 *
 * Uses star-projection `ToolBase<*, *>` because the registry is heterogeneous:
 * each tool has its own TArgs/TResult types.
 */
object ToolRegistry {
    private val mutex = Mutex()
    private val registeredTools = mutableMapOf<String, ToolBase<*, *>>()

    suspend fun register(tool: ToolBase<*, *>) =
        mutex.withLock {
            registeredTools[tool.name] = tool
        }

    suspend fun getTool(name: String): ToolBase<*, *>? =
        mutex.withLock {
            registeredTools[name]
        }

    /** Remove a dynamically registered tool, notably one owned by an MCP server. */
    suspend fun unregister(name: String): Boolean =
        mutex.withLock {
            registeredTools.remove(name) != null
        }

    suspend fun listTools(): List<ToolBase<*, *>> =
        mutex.withLock {
            registeredTools.values.toList()
        }

    suspend fun contractCoverage(): ToolContractCoverageReport =
        mutex.withLock {
            ToolContractRegistry.audit(registeredTools.keys)
        }

    suspend fun requireCompleteContractCoverage(): ToolContractCoverageReport =
        mutex.withLock {
            ToolContractRegistry.requireCompleteCoverage(registeredTools.keys)
        }

    /** Non-suspend snapshot — safe for reads at startup after tools are registered. */
    fun toolsSnapshot(): List<ToolBase<*, *>> = registeredTools.values.toList()

    suspend fun clear() =
        mutex.withLock {
            registeredTools.clear()
        }
}
