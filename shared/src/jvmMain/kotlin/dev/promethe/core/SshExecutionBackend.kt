package dev.promethe.core

import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SshExecutionBackend — executes commands on a remote server via SSH.
 *
 * Uses the system `ssh` command (available on all modern OS) rather than
 * pulling in a Java SSH library. This keeps dependencies minimal and
 * leverages the user's existing SSH config (~/.ssh/config, agent forwarding, etc.).
 *
 * Configure via AgentConfig:
 * - sshHost: hostname or IP
 * - sshUser: remote username
 * - sshKeyPath: path to private key (optional if ssh-agent is running)
 * - sshPort: port (default 22)
 */
class SshExecutionBackend(
    private val host: String,
    private val user: String,
    private val keyPath: String = "",
    private val port: Int = 22,
    private val timeoutMs: Long = 30_000,
    private val maxOutputBytes: Int = 50_000,
    private val commandRunner: SandboxedCommandRunner? = null,
) {
    /**
     * Execute a command on the remote host via SSH.
     * Returns stdout + stderr combined output.
     */
    suspend fun execute(command: String): String =
        "[SANDBOX POLICY_DENIED] SSH command strings are unavailable because the remote shell " +
            "would reinterpret unstructured input. Use a structured remote execution integration."

    /**
     * Upload a file to the remote host via SCP.
     */
    suspend fun upload(
        localPath: String,
        remotePath: String,
    ): String =
        commandRunner?.let { runner ->
            val result =
                runner.execute(
                    executable = "scp",
                    arguments =
                        buildList {
                            add("-o")
                            add("StrictHostKeyChecking=accept-new")
                            add("-P")
                            add(port.toString())
                            if (keyPath.isNotBlank()) {
                                add("-i")
                                add(keyPath)
                            }
                            add(localPath)
                            add("$user@$host:$remotePath")
                        },
                    timeoutMillis = timeoutMs * 2,
                )
            result.renderCommandOutput().take(maxOutputBytes)
        } ?: "[SANDBOX BACKEND_UNAVAILABLE] Sandboxed execution is unavailable."

    /**
     * Test SSH connectivity.
     */
    suspend fun testConnection(): Boolean = false
}
