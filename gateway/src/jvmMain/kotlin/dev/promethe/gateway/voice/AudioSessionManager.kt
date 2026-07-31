package dev.promethe.gateway.voice

import dev.promethe.core.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * AudioSessionManager — manages voice session lifecycle.
 *
 * Tracks active sessions, enforces concurrency limits,
 * handles timeouts, and provides metrics.
 */
class AudioSessionManager(
    private val maxConcurrentSessions: Int = 5,
    private val sessionTimeout: kotlin.time.Duration = 15.minutes,
) {
    private val logger = Log.create("AudioSessionManager")
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, VoiceSessionInfo>()

    /**
     * Register a new voice session. Returns false if limit reached.
     */
    suspend fun createSession(
        sessionId: String,
        userId: String,
        relay: VoiceRelay,
    ): Boolean =
        mutex.withLock {
            if (sessions.size >= maxConcurrentSessions) {
                logger.warn { "Max concurrent voice sessions reached ($maxConcurrentSessions)" }
                return false
            }

            sessions[sessionId] = VoiceSessionInfo(
                sessionId = sessionId,
                userId = userId,
                relay = relay,
                startedAt = Clock.System.now().epochSeconds,
                lastActivityAt = Clock.System.now().epochSeconds,
            )
            logger.info { "Voice session created: $sessionId (active=${sessions.size})" }
            return true
        }

    /**
     * Get a session's relay.
     */
    suspend fun getRelay(sessionId: String): VoiceRelay? =
        mutex.withLock {
            sessions[sessionId]?.relay
        }

    /**
     * Update session activity timestamp.
     */
    suspend fun touch(sessionId: String) =
        mutex.withLock {
            sessions[sessionId]?.lastActivityAt = Clock.System.now().epochSeconds
        }

    /**
     * Remove a session.
     */
    suspend fun removeSession(sessionId: String) =
        mutex.withLock {
            sessions.remove(sessionId)?.also {
                logger.info { "Voice session removed: $sessionId (active=${sessions.size})" }
            }
        }

    /**
     * Clean up timed-out sessions.
     */
    suspend fun cleanupExpired(): List<String> =
        mutex.withLock {
            val now = Clock.System.now().epochSeconds
            val expiredIds = sessions.entries
                .filter { now - it.value.lastActivityAt > sessionTimeout.inWholeSeconds }
                .map { it.key }

            expiredIds.forEach { id ->
                sessions.remove(id)?.let { info ->
                    try {
                        info.relay.disconnect()
                    } catch (_: Exception) {
                    }
                    logger.info { "Voice session expired: $id" }
                }
            }
            expiredIds
        }

    /**
     * Get session metrics.
     */
    suspend fun getMetrics(): VoiceMetrics =
        mutex.withLock {
            VoiceMetrics(
                activeSessions = sessions.size,
                maxSessions = maxConcurrentSessions,
                sessionIds = sessions.keys.toList(),
            )
        }
}

data class VoiceSessionInfo(
    val sessionId: String,
    val userId: String,
    val relay: VoiceRelay,
    val startedAt: Long,
    var lastActivityAt: Long,
)

data class VoiceMetrics(
    val activeSessions: Int,
    val maxSessions: Int,
    val sessionIds: List<String>,
)
