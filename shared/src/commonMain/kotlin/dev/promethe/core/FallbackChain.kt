package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.delay

/**
 * FallbackChain — Hermes-style model fallback with auto-switch on failure.
 *
 * When the primary model fails (timeout, 429, 500, etc.), the chain
 * automatically tries the next model in priority order.
 *
 * Usage:
 *   val chain = FallbackChain(listOf(
 *       FallbackEntry("openrouter", "anthropic/claude-sonnet-4-20250514", 1),
 *       FallbackEntry("openai", "gpt-4o", 2),
 *       FallbackEntry("google", "gemini-3.5-flash", 3),
 *   ))
 *   val result = chain.executeWithFallback { provider, model -> llm.complete(provider, model, prompt) }
 */
class FallbackChain(
    private val entries: List<FallbackEntry>,
    private val retryDelayMs: Long = 1000L,
) {
    private val logger = Log.create("FallbackChain")

    data class FallbackEntry(
        val provider: String,
        val model: String,
        val priority: Int = 0,
    )

    data class FallbackResult<T>(
        val result: T,
        val usedEntry: FallbackEntry,
        val attemptedEntries: List<FallbackAttempt>,
    )

    data class FallbackAttempt(
        val entry: FallbackEntry,
        val error: String?,
        val durationMs: Long,
    )

    /**
     * Execute [block] with automatic fallback through the chain.
     * Returns the first successful result, or throws if all entries fail.
     */
    suspend fun <T> executeWithFallback(block: suspend (provider: String, model: String) -> T): FallbackResult<T> {
        val sorted = entries.sortedBy { it.priority }
        val attempts = mutableListOf<FallbackAttempt>()

        for ((index, entry) in sorted.withIndex()) {
            val startTime =
                kotlin.time.Clock.System
                    .now()
                    .toEpochMilliseconds()
            try {
                val result = block(entry.provider, entry.model)
                val duration =
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds() - startTime
                attempts.add(FallbackAttempt(entry, null, duration))

                if (index > 0) {
                    logger.info { "Succeeded with fallback: ${entry.provider}/${entry.model} (after $index failures)" }
                }

                return FallbackResult(result, entry, attempts)
            } catch (e: Exception) {
                val duration =
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds() - startTime
                attempts.add(FallbackAttempt(entry, e.message, duration))

                val isRetryable = isRetryableError(e)
                logger.warn { "${entry.provider}/${entry.model} failed: ${e.message} (retryable=$isRetryable)" }

                if (index < sorted.size - 1 && isRetryable) {
                    delay(retryDelayMs * (index + 1)) // Exponential-ish backoff
                    continue
                } else if (!isRetryable) {
                    // Non-retryable errors (auth, invalid request) skip fallback
                    throw e
                }
            }
        }

        // All entries exhausted
        val errorSummary = attempts.joinToString("; ") { "${it.entry.provider}/${it.entry.model}: ${it.error}" }
        throw RuntimeException("FallbackChain exhausted all ${sorted.size} entries: $errorSummary")
    }

    private fun isRetryableError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return true
        return when {
            "401" in msg || "403" in msg -> false

            // Auth errors — don't retry
            "invalid" in msg && "api" in msg -> false

            // Invalid API key
            "429" in msg -> true

            // Rate limit — retry with fallback
            "500" in msg || "502" in msg || "503" in msg -> true

            // Server errors
            "timeout" in msg -> true

            // Timeouts
            "connection" in msg -> true

            // Network errors
            else -> true // Default: retry
        }
    }

    companion object {
        /**
         * Build a fallback chain from a profile's primary + fallback models.
         * Format: "provider/model" (e.g., "openrouter/anthropic/claude-sonnet-4-20250514")
         */
        fun fromProfile(
            primaryProvider: String,
            primaryModel: String,
            fallbackModels: List<String>,
        ): FallbackChain {
            val entries =
                mutableListOf(
                    FallbackEntry(primaryProvider, primaryModel, 0),
                )
            fallbackModels.forEachIndexed { index, fallback ->
                val parts = fallback.split("/", limit = 2)
                if (parts.size == 2) {
                    entries.add(FallbackEntry(parts[0], parts[1], index + 1))
                }
            }
            return FallbackChain(entries)
        }
    }
}
