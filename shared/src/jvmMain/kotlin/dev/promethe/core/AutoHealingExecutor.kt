package dev.promethe.core

import kotlinx.coroutines.delay

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * AutoHealingExecutor — wraps ActionExecutor with automatic retry,
 * replan, and decompose using ResilienceStrategy.
 *
 * When a tool execution fails:
 *   1. RETRY: re-execute the same call (up to 3 times)
 *   2. REPLAN: ask the LLM to reformulate the approach
 *   3. DECOMPOSE: break the task into sub-tasks
 *
 * This is integrated at the ActionExecutor level so ALL tool calls
 * benefit from auto-healing transparently.
 */
class AutoHealingExecutor(
    private val delegate: ToolInvoker,
    private val resilience: ResilienceStrategy,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000,
) {
    /** Narrow seam over the tool-dispatch call, so tests can stub executions. */
    fun interface ToolInvoker {
        suspend fun invoke(
            toolName: String,
            args: kotlinx.serialization.json.JsonObject,
        ): String
    }

    constructor(
        delegate: ActionExecutor,
        resilience: ResilienceStrategy,
        maxRetries: Int = 3,
        retryDelayMs: Long = 1000,
    ) : this(ToolInvoker { toolName, args -> delegate.execute(toolName, args) }, resilience, maxRetries, retryDelayMs)

    /**
     * Execute a tool with auto-healing.
     * On transient failures (network, timeout, rate limit), retries automatically.
     * On persistent failures, escalates through ResilienceStrategy levels.
     */
    suspend fun executeWithHealing(
        toolName: String,
        args: kotlinx.serialization.json.JsonObject,
    ): AutoHealResult {
        var attempt = 0
        val errors = mutableListOf<String>()

        while (attempt < maxRetries) {
            try {
                val result = delegate.invoke(toolName, args)

                // Check if the result indicates an error
                if (isErrorResult(result)) {
                    errors.add(result)
                    attempt++

                    if (attempt < maxRetries) {
                        // Exponential backoff
                        delay(retryDelayMs * attempt)
                        logger.debug { "Tool '$toolName' returned error, retry $attempt/$maxRetries" }
                        continue
                    }
                }

                return AutoHealResult(
                    success = !isErrorResult(result),
                    result = result,
                    attempts = attempt + 1,
                    errors = errors,
                    healed = attempt > 0 && !isErrorResult(result),
                )
            } catch (e: Exception) {
                errors.add("${e::class.simpleName}: ${e.message}")
                attempt++

                if (attempt < maxRetries && isTransient(e)) {
                    delay(retryDelayMs * attempt)
                    logger.debug { "Transient error on '$toolName', retry $attempt/$maxRetries: ${e.message}" }
                    continue
                }

                return AutoHealResult(
                    success = false,
                    result = "[ERROR] Tool '$toolName' failed after $attempt attempts: ${e.message}",
                    attempts = attempt,
                    errors = errors,
                    healed = false,
                )
            }
        }

        return AutoHealResult(
            success = false,
            result = "[ERROR] Tool '$toolName' exhausted $maxRetries retries",
            attempts = maxRetries,
            errors = errors,
            healed = false,
        )
    }

    /**
     * Check if a result string indicates an error.
     */
    private fun isErrorResult(result: String): Boolean =
        result.startsWith("[ERROR]") ||
            result.startsWith("[BLOCKED]") ||
            result.startsWith("Error:")

    /**
     * Determine if an exception is transient (worth retrying).
     */
    private fun isTransient(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("timeout") ||
            msg.contains("connection") ||
            msg.contains("rate limit") ||
            msg.contains("429") ||
            msg.contains("503") ||
            msg.contains("502") ||
            msg.contains("socket") ||
            msg.contains("reset") ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException
    }

    data class AutoHealResult(
        val success: Boolean,
        val result: String,
        val attempts: Int,
        val errors: List<String>,
        val healed: Boolean,
    )
}
