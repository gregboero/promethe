package dev.promethe.core

import dev.promethe.db.PrometheDatabaseApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * CheckpointManager — saves and restores agent execution state mid-loop.
 *
 * After each successful tool call, a checkpoint is saved to the DB.
 * If the agent crashes, it can resume from the last checkpoint.
 */
class CheckpointManager(
    private val database: PrometheDatabaseApi,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Serializable
    data class AgentCheckpoint(
        val sessionId: String,
        val stepIndex: Int,
        val iteration: Int,
        val currentInput: String,
        val isComplete: Boolean,
        val escalationLevel: String? = null,
        val escalationAttempt: Int = 0,
    )

    /**
     * Save a checkpoint after a successful step.
     */
    suspend fun save(checkpoint: AgentCheckpoint) {
        val stateJson = json.encodeToString(checkpoint)
        database.insertCheckpoint(
            sessionId = checkpoint.sessionId,
            stepIndex = checkpoint.stepIndex,
            stateJson = stateJson,
        )
    }

    /**
     * Load the latest checkpoint for a session.
     * Returns null if no checkpoint exists (fresh session).
     */
    suspend fun load(sessionId: String): AgentCheckpoint? {
        val row = database.getLatestCheckpoint(sessionId) ?: return null
        return try {
            json.decodeFromString<AgentCheckpoint>(row)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to deserialize checkpoint for session $sessionId" }
            null
        }
    }

    /**
     * Clear checkpoints for a completed session.
     */
    suspend fun clear(sessionId: String) {
        database.clearCheckpoints(sessionId)
    }
}
