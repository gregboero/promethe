package dev.promethe.core.hooks

import dev.promethe.core.Log

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central registry for lifecycle hooks.
 *
 * Hooks are executed in priority order (lower = earlier).
 * Multiple hooks can listen to the same event.
 *
 * Thread-safe: all mutations use a Mutex.
 */
class HookManager {
    private val logger = Log.create("HookManager")
    private val mutex = Mutex()
    private val hooks = mutableListOf<Hook>()

    // ── Registration ─────────────────────────────────────────

    /** Register a hook. Duplicates (by id) are replaced. */
    suspend fun register(hook: Hook) =
        mutex.withLock {
            hooks.removeAll { it.id == hook.id }
            hooks.add(hook)
            hooks.sortBy { it.priority }
        }

    /** Unregister a hook by id. */
    suspend fun unregister(hookId: String) =
        mutex.withLock {
            hooks.removeAll { it.id == hookId }
        }

    /** List all registered hook ids. */
    suspend fun listHooks(): List<String> =
        mutex.withLock {
            hooks.map { it.id }
        }

    /** Retrieve a registered hook by its id, or null if not found. */
    suspend fun getHookById(hookId: String): Hook? =
        mutex.withLock {
            hooks.find { it.id == hookId }
        }

    // ── Execution ────────────────────────────────────────────

    /**
     * Fire an event through all matching hooks.
     *
     * Returns [HookResult.Continue] if all hooks pass,
     * or the first [HookResult.Abort] / [HookResult.Modify] encountered.
     */
    suspend fun fire(context: HookContext): HookResult {
        val matching =
            mutex.withLock {
                hooks.filter { context.event in it.events }.toList()
            }

        for (hook in matching) {
            try {
                val result = hook.execute(context)
                when (result) {
                    is HookResult.Continue -> { /* keep going */ }

                    is HookResult.Abort -> {
                        logger.warn { "Hook '${hook.id}' aborted: ${result.reason}" }
                        return result
                    }

                    is HookResult.Modify -> {
                        logger.info { "Hook '${hook.id}' modified output" }
                        return result
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Hook '${hook.id}' threw" }
                // Hooks should not crash the agent — swallow and continue
            }
        }

        return HookResult.Continue
    }

    /**
     * Convenience: fire and return true if execution should proceed.
     */
    suspend fun shouldProceed(context: HookContext): Boolean = fire(context) !is HookResult.Abort
}
