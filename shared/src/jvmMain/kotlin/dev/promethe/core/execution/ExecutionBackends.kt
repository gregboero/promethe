package dev.promethe.core.execution

import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Execution backends for running commands in various environments.
 *
 * The agent dispatches to the appropriate backend based on config.executionBackend:
 * - "local"       → Native Promethe sandbox
 * - "docker"      → DockerBackend (container)
 * - "ssh"         → SshBackend (remote)
 * - "singularity" → SingularityBackend (HPC)
 * - "modal"       → ModalBackend (serverless)
 * - "daytona"     → DaytonaBackend (dev environments)

Common interface for all execution backends. */
interface ExecutionBackend {
    val name: String

    suspend fun execute(
        command: String,
        args: List<String>,
        timeoutMs: Long,
        maxOutput: Int,
    ): String

    suspend fun isAvailable(): Boolean
}

/**
 * SingularityBackend — HPC execution via Singularity/Apptainer containers.
 *
 * Used in academic/HPC environments where Docker is not available.
 * Wraps commands in `singularity exec` calls.
 */
class SingularityBackend(
    private val imagePath: String = ConfigProvider.get().get("SINGULARITY_IMAGE", "promethe.sif"),
    private val bindPaths: List<String> = ConfigProvider.get().get("SINGULARITY_BIND", "").split(",").filter { it.isNotBlank() },
    private val commandRunner: SandboxedCommandRunner? = null,
) : ExecutionBackend {
    override val name = "singularity"

    override suspend fun execute(
        command: String,
        args: List<String>,
        timeoutMs: Long,
        maxOutput: Int,
    ): String {
        val bindArgs = bindPaths.flatMap { listOf("--bind", it) }
        val fullCmd = listOf("singularity", "exec") + bindArgs + listOf(imagePath, command) + args

        return runProcess(commandRunner, fullCmd, timeoutMs, maxOutput)
    }

    override suspend fun isAvailable(): Boolean =
        try {
            val result = runProcess(commandRunner, listOf("singularity", "--version"), 5000, 1000)
            result.contains("singularity") || result.contains("apptainer")
        } catch (e: Exception) {
            logger.debug(e) { "Singularity backend not available" }
            false
        }
}

/**
 * ModalBackend — Serverless execution via Modal (modal.com).
 *
 * Spawns ephemeral containers on Modal's GPU/CPU cloud.
 * Requires MODAL_TOKEN_ID and MODAL_TOKEN_SECRET.
 */
class ModalBackend(
    private val tokenId: String = ConfigProvider.get().get("MODAL_TOKEN_ID", ""),
    private val tokenSecret: String = ConfigProvider.get().get("MODAL_TOKEN_SECRET", ""),
    private val appName: String = ConfigProvider.get().get("MODAL_APP_NAME", "promethe-sandbox"),
    private val commandRunner: SandboxedCommandRunner? = null,
) : ExecutionBackend {
    override val name = "modal"

    override suspend fun execute(
        command: String,
        args: List<String>,
        timeoutMs: Long,
        maxOutput: Int,
    ): String {
        if (tokenId.isBlank() || tokenSecret.isBlank()) {
            return "[ERROR] MODAL_TOKEN_ID and MODAL_TOKEN_SECRET not configured"
        }

        // Modal CLI approach: `modal run` with the command
        val fullCmd = listOf(
            "modal",
            "run",
            "--detach",
            "--app",
            appName,
            "--",
            command,
        ) + args

        return runProcess(
            commandRunner = commandRunner,
            cmd = fullCmd,
            timeoutMs = timeoutMs,
            maxOutput = maxOutput,
            environment =
                mapOf(
                    "MODAL_TOKEN_ID" to tokenId,
                    "MODAL_TOKEN_SECRET" to tokenSecret,
                ),
            sensitiveEnvironmentKeys = setOf("MODAL_TOKEN_ID", "MODAL_TOKEN_SECRET"),
        )
    }

    override suspend fun isAvailable(): Boolean =
        try {
            tokenId.isNotBlank() && tokenSecret.isNotBlank() &&
                runProcess(commandRunner, listOf("modal", "--version"), 5000, 1000).contains("modal")
        } catch (e: Exception) {
            logger.debug(e) { "Modal backend not available" }
            false
        }
}

/**
 * DaytonaBackend — Dev environment execution via Daytona.
 * https://www.daytona.io/
 *
 * Creates or reuses a Daytona workspace for persistent execution.
 * Requires DAYTONA_API_KEY and optionally DAYTONA_SERVER_URL.
 */
class DaytonaBackend(
    private val apiKey: String = ConfigProvider.get().get("DAYTONA_API_KEY", ""),
    private val serverUrl: String = ConfigProvider.get().get("DAYTONA_SERVER_URL", "https://app.daytona.io"),
    private val workspaceName: String = ConfigProvider.get().get("DAYTONA_WORKSPACE", "promethe-workspace"),
    private val commandRunner: SandboxedCommandRunner? = null,
) : ExecutionBackend {
    override val name = "daytona"

    override suspend fun execute(
        command: String,
        args: List<String>,
        timeoutMs: Long,
        maxOutput: Int,
    ): String {
        if (apiKey.isBlank()) {
            return "[ERROR] DAYTONA_API_KEY not configured"
        }

        // Use daytona CLI to execute in workspace
        val fullCmd = listOf(
            "daytona",
            "exec",
            workspaceName,
            "--",
            command,
        ) + args

        return runProcess(
            commandRunner = commandRunner,
            cmd = fullCmd,
            timeoutMs = timeoutMs,
            maxOutput = maxOutput,
            environment =
                mapOf(
                    "DAYTONA_API_KEY" to apiKey,
                    "DAYTONA_SERVER_URL" to serverUrl,
                ),
            sensitiveEnvironmentKeys = setOf("DAYTONA_API_KEY"),
        )
    }

    override suspend fun isAvailable(): Boolean =
        try {
            apiKey.isNotBlank() &&
                runProcess(commandRunner, listOf("daytona", "version"), 5000, 1000).isSuccessfulProbe()
        } catch (e: Exception) {
            logger.debug(e) { "Daytona backend not available" }
            false
        }
}

/**
 * Backend factory — resolves a backend name to an implementation.
 */
object ExecutionBackendFactory {
    fun create(
        backendName: String,
        commandRunner: SandboxedCommandRunner? = null,
    ): ExecutionBackend =
        when (backendName) {
            "singularity" -> SingularityBackend(commandRunner = commandRunner)

            "modal" -> ModalBackend(commandRunner = commandRunner)

            "daytona" -> DaytonaBackend(commandRunner = commandRunner)

            else -> throw IllegalArgumentException(
                "Unknown execution backend: $backendName. " +
                    "Supported: local, docker, ssh, singularity, modal, daytona",
            )
        }

    val SUPPORTED_BACKENDS = listOf("local", "docker", "ssh", "singularity", "modal", "daytona")
}

// ── Shared process runner ────────────────────────────────────

internal suspend fun runProcess(
    commandRunner: SandboxedCommandRunner?,
    cmd: List<String>,
    timeoutMs: Long,
    maxOutput: Int,
    environment: Map<String, String> = emptyMap(),
    sensitiveEnvironmentKeys: Set<String> = emptySet(),
): String {
    if (cmd.isEmpty()) return "[SANDBOX INVALID_REQUEST] Executable is required."
    val runner =
        commandRunner
            ?: return "[SANDBOX BACKEND_UNAVAILABLE] Sandboxed execution is unavailable."
    val result =
        runner.execute(
            executable = cmd.first(),
            arguments = cmd.drop(1),
            timeoutMillis = timeoutMs,
            environment = environment,
            sensitiveEnvironmentKeys = sensitiveEnvironmentKeys,
        )
    return result.renderCommandOutput().take(maxOutput)
}

private fun String.isSuccessfulProbe(): Boolean =
    isNotBlank() &&
        !startsWith("[SANDBOX ") &&
        !startsWith("[EXIT ")
