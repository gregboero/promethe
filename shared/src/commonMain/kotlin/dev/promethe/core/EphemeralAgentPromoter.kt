package dev.promethe.core

import dev.promethe.db.PrometheDatabaseApi

/**
 * EphemeralAgentPromoter — auto-promotes ephemeral agents based on usage patterns.
 *
 * Integrates with the GEPA feedback loop and TrajectoryEvaluator:
 * - Tracks delegation counts per ephemeral agent
 * - When an ephemeral agent exceeds the usage threshold, auto-promotes it
 * - Called after each successful delegation completes
 *
 * Flow:
 * ```
 * Agent needs a specialist → create_agent(ephemeral=true)
 *   └── delegate_task(profileId="temp-xyz") → success
 *       └── EphemeralAgentPromoter.onDelegationComplete("temp-xyz")
 *           └── usageCount++ → if >= threshold → auto-promote
 * ```
 */
class EphemeralAgentPromoter(
    private val database: PrometheDatabaseApi,
    private val usageThreshold: Int = DEFAULT_USAGE_THRESHOLD,
) {
    private val logger = Log.create("EphemeralAgentPromoter")

    // In-memory usage counter (resets on restart — fine for ephemeral tracking)
    private val usageCounts = mutableMapOf<String, Int>()

    // Track success ratio
    private val successCounts = mutableMapOf<String, Int>()
    private val failureCounts = mutableMapOf<String, Int>()

    /**
     * Called after a delegation to an agent completes.
     * Tracks usage and auto-promotes if thresholds are met.
     *
     * @return true if the agent was auto-promoted
     */
    suspend fun onDelegationComplete(
        profileId: String,
        success: Boolean,
    ): Boolean {
        val profile = database.getAgentProfile(profileId) ?: return false

        // Only track ephemeral agents
        if (!profile.ephemeral) return false

        // Update counters
        val totalUses = usageCounts.merge(profileId, 1) { old, _ -> old + 1 } ?: 1
        if (success) {
            successCounts.merge(profileId, 1) { old, _ -> old + 1 }
        } else {
            failureCounts.merge(profileId, 1) { old, _ -> old + 1 }
        }

        val successes = successCounts[profileId] ?: 0
        val failures = failureCounts[profileId] ?: 0
        val successRate = if (totalUses > 0) successes.toDouble() / totalUses else 0.0

        logger.info {
            "Ephemeral agent '$profileId': uses=$totalUses, success=$successes, fail=$failures, rate=${"%.1f".format(successRate * 100)}%"
        }

        // Auto-promote if:
        // 1. Used at least N times (configurable threshold)
        // 2. Success rate >= 70%
        if (totalUses >= usageThreshold && successRate >= MIN_SUCCESS_RATE) {
            logger.info {
                "🎓 Auto-promoting ephemeral agent '$profileId' (used $totalUses times, ${(successRate * 100).toInt()}% success rate)"
            }

            database.insertAgentProfile(
                profile.copy(
                    ephemeral = false,
                    updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                ),
            )

            // Clean up counters
            usageCounts.remove(profileId)
            successCounts.remove(profileId)
            failureCounts.remove(profileId)

            return true
        }

        return false
    }

    /**
     * Get promotion status for all tracked ephemeral agents.
     * Useful for introspection tools.
     */
    fun getTrackingStatus(): List<EphemeralAgentStatus> =
        usageCounts.map { (id, uses) ->
            val successes = successCounts[id] ?: 0
            val failures = failureCounts[id] ?: 0
            val rate = if (uses > 0) successes.toDouble() / uses else 0.0
            EphemeralAgentStatus(
                profileId = id,
                totalUses = uses,
                successes = successes,
                failures = failures,
                successRate = rate,
                usesUntilPromotion = (usageThreshold - uses).coerceAtLeast(0),
            )
        }

    data class EphemeralAgentStatus(
        val profileId: String,
        val totalUses: Int,
        val successes: Int,
        val failures: Int,
        val successRate: Double,
        val usesUntilPromotion: Int,
    )

    companion object {
        /** Default: promote after 3 successful uses */
        const val DEFAULT_USAGE_THRESHOLD = 3

        /** Minimum 70% success rate required for auto-promotion */
        const val MIN_SUCCESS_RATE = 0.7
    }
}
