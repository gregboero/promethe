package dev.promethe.core

import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SingularityExecutionBackend — run commands inside Singularity/Apptainer containers.
 *
 * Used for HPC environments where Docker is unavailable but Singularity is.
 * Wraps the system `singularity exec` command.
 *
 * Configure via environment:
 * - SINGULARITY_IMAGE: path to .sif file (e.g., "agent.sif")
 * - SINGULARITY_BIND_DIRS: comma-separated bind dirs (e.g., "/data,/scratch")
 */
class SingularityExecutionBackend(
    private val imagePath: String,
    private val bindDirs: List<String> = emptyList(),
    private val timeoutMs: Long = 30_000,
    private val maxOutputBytes: Int = 50_000,
    private val commandRunner: SandboxedCommandRunner? = null,
) {
    /**
     * Execute a command inside the Singularity container.
     */
    suspend fun execute(command: String): String =
        "[SANDBOX POLICY_DENIED] Singularity command strings are unavailable; " +
            "use execute(executable, arguments)."

    suspend fun execute(
        executable: String,
        arguments: List<String>,
    ): String {
        val runner =
            commandRunner
                ?: return "[SANDBOX BACKEND_UNAVAILABLE] Sandboxed execution is unavailable."
        val result =
            runner.execute(
                executable = "singularity",
                arguments =
                    buildList {
                        add("exec")
                        for (dir in bindDirs) {
                            add("--bind")
                            add(dir)
                        }
                        add(imagePath)
                        add(executable)
                        addAll(arguments)
                    },
                timeoutMillis = timeoutMs,
            )
        return result.renderCommandOutput().take(maxOutputBytes)
    }

    /**
     * Test that Singularity is available and the image is valid.
     */
    suspend fun testConnection(): Boolean =
        try {
            val result = execute("/bin/echo", listOf("ok"))
            result.trim() == "ok"
        } catch (e: Exception) {
            logger.warn(e) { "Singularity connection test failed for image: $imagePath" }
            false
        }
}
