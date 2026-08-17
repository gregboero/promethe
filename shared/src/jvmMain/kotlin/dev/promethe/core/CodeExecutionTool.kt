package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.execution.ExecutionBackendFactory
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ══════════════════════════════════════════════════════════════
//  Code Execution Tool — Run code snippets in the configured backend
//  Supports Python, JavaScript (Node.js), and Kotlin Script.
//  Writes to temp files, executes with timeout, captures output.
//  Approval is enforced once at the dispatch layer (ActionExecutor).
//  Sandboxing: never falls back to unsandboxed local execution —
//  if the configured backend can't run code, the call is refused.
// ══════════════════════════════════════════════════════════════

/** Extra seconds granted on top of the script timeout for container startup / image pull. */
private const val DOCKER_STARTUP_GRACE_SECS = 30L

/**
 * Default Docker image per language, overridable via DOCKER_IMAGE config.
 * Returns null when no safe default exists for the language.
 */
internal fun dockerImageFor(
    language: String,
    override: String = ConfigProvider.get().get("DOCKER_IMAGE", ""),
): String? {
    if (override.isNotBlank()) return override
    return when (language.lowercase()) {
        "python", "py" -> "python:3.12-slim"
        "javascript", "js", "node" -> "node:22-slim"
        else -> null
    }
}

/**
 * Build the `docker run` command for executing a script inside an isolated container.
 * The script's parent directory is mounted read-only at /sandbox; the container has
 * no network access and bounded resources.
 */
internal fun buildDockerCommand(
    image: String,
    containerName: String,
    interpreter: List<String>,
    scriptFileName: String,
    extraArgs: List<String>,
    hostMountDir: String,
): List<String> =
    listOf(
        "docker",
        "run",
        "--rm",
        "--name",
        containerName,
        "--network",
        "none",
        "--memory",
        "512m",
        "--cpus",
        "1",
        "-v",
        "$hostMountDir:/sandbox:ro",
        "-w",
        "/sandbox",
        image,
    ) + interpreter + listOf("/sandbox/$scriptFileName") + extraArgs

@Serializable
data class CodeExecArgs(
    @property:LLMDescription("Programming language: 'python', 'javascript', or 'kotlin'.")
    val language: String,
    @property:LLMDescription("Code to execute.")
    val code: String,
    @property:LLMDescription("Timeout in seconds (max 30).")
    val timeout: Int = 10,
    @property:LLMDescription("Arguments to pass to the script.")
    val args: String = "",
)

class CodeExecutionTool(
    private val workDir: String = ".",
    private val maxOutputBytes: Int = 50_000,
    private val config: AgentConfig? = null,
    private val commandRunner: SandboxedCommandRunner? = null,
    private val workspaceFileWriter: WorkspaceFileWriter? = null,
) : SimpleTool<CodeExecArgs>(
        argsType = typeToken<CodeExecArgs>(),
        name = "execute_code",
        description = "Execute code snippets in Python, JavaScript (Node.js), or Kotlin. Returns stdout/stderr.",
    ) {
    override suspend fun execute(args: CodeExecArgs): String {
        val timeoutSecs = args.timeout.coerceIn(1, 30).toLong()
        val backend = config?.executionBackend ?: "local"
        if (backend == "ssh") {
            return "[ERROR] Execution backend 'ssh' does not support structured code execution; " +
                "refusing to run unsandboxed."
        }
        if (backend !in setOf("local", "docker", "singularity", "modal", "daytona")) {
            return "[ERROR] Execution backend '$backend' does not support code execution; " +
                "refusing to run unsandboxed. Configure a supported sandbox backend."
        }

        val (extension, interpreter) =
            when (args.language.lowercase()) {
                "python", "py" -> {
                    ".py" to listOf("python3", "-u")
                }

                "javascript", "js", "node" -> {
                    ".js" to listOf("node")
                }

                "kotlin", "kts" -> {
                    ".kts" to listOf("kotlin", "-script")
                }

                else -> {
                    return "[ERROR] Unsupported language: ${args.language}. Use: python, javascript, kotlin"
                }
            }

        val runner =
            commandRunner
                ?: return "[ERROR] Sandboxed execution is unavailable; refusing to execute code."

        val writer =
            workspaceFileWriter
                ?: return "[ERROR] Secure code staging is unavailable; refusing to execute code."
        val scopedWorkDir = projectScopedPath(".")
        val executionWorkDir = File(workDir, scopedWorkDir).canonicalFile
        val workspace = File(workDir).canonicalFile
        if (!executionWorkDir.toPath().startsWith(workspace.toPath()) || !executionWorkDir.isDirectory) {
            return "[ERROR] Active project workspace is unavailable"
        }
        val tempName = ".promethe-exec-${UUID.randomUUID()}$extension"
        val tempFile = File(executionWorkDir, tempName)

        return try {
            val stagedPath = if (scopedWorkDir == ".") tempName else "$scopedWorkDir/$tempName"
            writer.write(stagedPath, args.code)
            val extraArgs = if (args.args.isNotBlank()) args.args.split(" ") else emptyList()

            when (backend) {
                "local" -> {
                    executeLocal(runner, interpreter + listOf(tempFile.absolutePath) + extraArgs, executionWorkDir.path, timeoutSecs)
                }

                "docker" -> {
                    executeDocker(runner, args.language, interpreter, tempFile, extraArgs, executionWorkDir.path, timeoutSecs)
                }

                "singularity", "modal", "daytona" -> {
                    executeViaBackend(runner, backend, interpreter, tempFile, extraArgs, timeoutSecs)
                }

                else -> {
                    "[ERROR] Execution backend '$backend' is unavailable."
                }
            }
        } catch (e: Exception) {
            "[ERROR] Code execution failed: ${e.message}"
        } finally {
            try {
                tempFile.delete()
            } catch (e: Exception) {
                logger.debug(e) { "Failed to delete temp file: ${tempFile.absolutePath}" }
            }
        }
    }

    private suspend fun executeLocal(
        runner: SandboxedCommandRunner,
        fullCommand: List<String>,
        executionWorkDir: String,
        timeoutSecs: Long,
    ): String {
        // Parity with runLocalProcess: the interpreter invocation goes through the blocklist too
        if (isBlockedCommand(fullCommand.first(), fullCommand.drop(1))) {
            return "[BLOCKED] Command rejected by security policy"
        }

        val result =
            runner.execute(
                executable = fullCommand.first(),
                arguments = fullCommand.drop(1),
                workingDirectory = executionWorkDir,
                timeoutMillis = timeoutSecs * 1_000,
            )
        return truncateOutput(result.renderCommandOutput(), maxOutputBytes)
    }

    private suspend fun executeDocker(
        runner: SandboxedCommandRunner,
        language: String,
        interpreter: List<String>,
        tempFile: File,
        extraArgs: List<String>,
        executionWorkDir: String,
        timeoutSecs: Long,
    ): String {
        val image = dockerImageFor(language)
            ?: return "[ERROR] No default Docker image for language '$language' — " +
                "set DOCKER_IMAGE to an image providing its runtime, or set EXEC_BACKEND=local explicitly."

        val containerName = "promethe-exec-${System.currentTimeMillis()}"
        val cmd =
            buildDockerCommand(
                image = image,
                containerName = containerName,
                interpreter = interpreter,
                scriptFileName = tempFile.name,
                extraArgs = extraArgs,
                hostMountDir = tempFile.parentFile.absolutePath,
            )

        val result =
            runner.execute(
                executable = cmd.first(),
                arguments = cmd.drop(1),
                workingDirectory = executionWorkDir,
                timeoutMillis = (timeoutSecs + DOCKER_STARTUP_GRACE_SECS) * 1_000,
            )

        if (result.timedOut) {
            val cleanup =
                runner.execute(
                    executable = "docker",
                    arguments = listOf("kill", containerName),
                    workingDirectory = executionWorkDir,
                    timeoutMillis = 5_000,
                )
            if (cleanup.exitCode != 0) {
                logger.debug { "Sandboxed Docker cleanup failed for $containerName: ${cleanup.errorCode}" }
            }
        }
        return truncateOutput(result.renderCommandOutput(), maxOutputBytes)
    }

    private suspend fun executeViaBackend(
        runner: SandboxedCommandRunner,
        backendName: String,
        interpreter: List<String>,
        tempFile: File,
        extraArgs: List<String>,
        timeoutSecs: Long,
    ): String {
        val backend = ExecutionBackendFactory.create(backendName, runner)
        if (!backend.isAvailable()) {
            return "[ERROR] Execution backend '$backendName' is not available — refusing to run code unsandboxed. " +
                "Use EXEC_BACKEND=local for the native sandbox."
        }
        val args = interpreter.drop(1) + listOf(tempFile.absolutePath) + extraArgs
        val rawOutput = backend.execute(interpreter.first(), args, timeoutSecs * 1000, maxOutputBytes)
        return rawOutput.trim().ifBlank { "(no output)" }
    }
}
