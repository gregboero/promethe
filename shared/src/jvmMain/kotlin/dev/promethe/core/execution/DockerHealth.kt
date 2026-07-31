package dev.promethe.core.execution

import dev.promethe.core.sandbox.SandboxedCommandRunner

/**
 * Lightweight Docker availability check.
 *
 * Runs `docker version --format json` with a short timeout.
 * Returns `true` if Docker daemon is reachable and responsive.
 */
object DockerHealth {
    /**
     * Legacy synchronous probe. It deliberately fails closed until the caller
     * supplies the shared sandbox runner through the suspending overload.
     */
    fun isAvailable(timeoutMs: Long = 3_000): Boolean = false

    /**
     * Check if Docker is installed and the daemon is running.
     * @param timeoutMs Maximum wait time in milliseconds (default: 3000ms)
     * @return true if `docker version` succeeds with exit code 0
     */
    suspend fun isAvailable(
        commandRunner: SandboxedCommandRunner?,
        timeoutMs: Long = 3_000,
    ): Boolean =
        try {
            val runner = commandRunner ?: return false
            val result =
                runner.execute(
                    executable = "docker",
                    arguments = listOf("version", "--format", "json"),
                    timeoutMillis = timeoutMs,
                )
            result.errorCode == null && !result.timedOut && result.exitCode == 0
        } catch (_: Exception) {
            false
        }
}
