package dev.promethe.core.tools.sys

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ShellArgs(
    @property:LLMDescription("Executable to run in the sandbox, without a shell interpreter (for example: 'ls' or './tool').")
    val executable: String,
    @property:LLMDescription("Literal arguments passed to the executable. Pipes, redirects, and shell syntax are rejected.")
    val arguments: List<String> = emptyList(),
    @property:LLMDescription("Working directory. Optional, defaults to workspace.")
    val cwd: String = ".",
    @property:LLMDescription("Timeout in seconds. Default 30.")
    val timeout: Int = 30,
)

@Serializable
data class ProcessManagerArgs(
    @property:LLMDescription("Action: 'list' or 'kill'. Default 'list'.")
    val action: String = "list",
    @property:LLMDescription("Process ID to kill (unsupported for sandboxed processes).")
    val pid: Long = -1,
    @property:LLMDescription("Filter process list by name substring. Optional.")
    val filter: String = "",
)

@Serializable
data class SystemInfoArgs(
    @property:LLMDescription("What to query: 'all', 'cpu', 'memory', 'disk', 'os'. Default 'all'.")
    val category: String = "all",
)

@Serializable
data class DockerArgs(
    @property:LLMDescription("Docker subcommand: 'ps', 'images', 'run', 'stop', 'rm', 'logs', 'compose'. Default 'ps'.")
    val action: String = "ps",
    @property:LLMDescription("Literal Docker CLI arguments. Shell syntax is not supported.")
    val arguments: List<String> = emptyList(),
)

@Serializable
data class EnvironmentArgs(
    @property:LLMDescription("Action: 'get' or 'list'. Default 'list'.")
    val action: String = "list",
    @property:LLMDescription("Environment variable name (required for 'get').")
    val name: String = "",
    @property:LLMDescription("Filter env vars by name prefix. Optional, for 'list'.")
    val filter: String = "",
)

class ShellTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<ShellArgs>(
        argsType = typeToken<ShellArgs>(),
        name = "shell",
        description = "Execute an executable with literal arguments in the configured sandbox. Requires approval.",
    ) {
    override suspend fun execute(args: ShellArgs): String {
        val workspace = File(workDir).canonicalFile
        if (!workspace.isDirectory) return "[ERROR] Workspace does not exist: ${workspace.path}"
        val requestedDirectory =
            try {
                File(workspace, args.cwd).canonicalFile
            } catch (error: Exception) {
                return "[ERROR] Invalid working directory: ${error.message}"
            }
        if (!requestedDirectory.toPath().startsWith(workspace.toPath())) {
            return "[BLOCKED] Working directory must stay inside the workspace"
        }
        if (!requestedDirectory.isDirectory) return "[ERROR] Working directory does not exist: ${args.cwd}"
        ShellInvocationPolicy.validate(args.executable, args.arguments)?.let { return "[BLOCKED] $it" }

        return sandboxRunner
            .execute(
                executable = args.executable,
                arguments = args.arguments,
                workingDirectory = requestedDirectory.path,
                timeoutMillis = args.timeout.coerceIn(1, 30) * 1_000L,
            ).renderCommandOutput()
    }
}

object ShellInvocationPolicy {
    fun validate(
        executable: String,
        arguments: List<String>,
    ): String? {
        if (executable.isBlank()) return "Executable is required"
        if (
            executable.startsWith('/') ||
            executable.startsWith('\\') ||
            WINDOWS_ABSOLUTE_PATH.matches(executable) ||
            executable.split('/', '\\').any { it == ".." }
        ) {
            return "Executable must be relative to the sandbox or a bare executable name"
        }
        if (executable.substringAfterLast('/').substringAfterLast('\\').lowercase() in SHELL_INTERPRETERS) {
            return "Shell interpreters are not allowed"
        }
        if (containsShellSyntax(executable) || arguments.any(::containsShellSyntax)) {
            return "Pipes, redirections, and shell syntax are not allowed"
        }
        if (arguments.any(::containsEscapingPath)) {
            return "Absolute paths and parent-directory traversal are not allowed"
        }
        return null
    }
}

private val SHELL_INTERPRETERS = setOf("cmd", "cmd.exe", "sh", "bash", "zsh", "fish", "powershell", "pwsh")

private fun containsShellSyntax(value: String): Boolean = listOf("|", ">", "<", ";", "&&", "||", "`", "$(").any(value::contains)

private fun containsEscapingPath(value: String): Boolean =
    value.startsWith('/') ||
        value.startsWith('\\') ||
        WINDOWS_ABSOLUTE_PATH.matches(value) ||
        value.split('/', '\\').any { it == ".." }

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

class ProcessManagerTool(
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<ProcessManagerArgs>(
        argsType = typeToken<ProcessManagerArgs>(),
        name = "process_manager",
        description = "List processes visible to the configured sandbox. Host process termination is not supported.",
    ) {
    override suspend fun execute(args: ProcessManagerArgs): String =
        when (args.action.lowercase()) {
            "list" -> {
                val command = if (isWindows()) "tasklist" else "ps"
                val commandArgs = if (isWindows()) listOf("/FO", "TABLE", "/NH") else listOf("-eo", "pid,comm")
                val result = sandboxRunner.execute(executable = command, arguments = commandArgs).renderCommandOutput()
                if (args.filter.isBlank() || result.startsWith("[")) {
                    result
                } else {
                    result.lineSequence().filter { it.contains(args.filter, ignoreCase = true) }.joinToString("\n")
                }
            }

            "kill" -> {
                "[BLOCKED] Host process termination is unavailable from the sandbox."
            }

            else -> {
                "[ERROR] Unknown action '${args.action}'. Use: list, kill."
            }
        }
}

class SystemInfoTool(
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<SystemInfoArgs>(
        argsType = typeToken<SystemInfoArgs>(),
        name = "system_info",
        description = "Get operating-system information visible from inside the configured sandbox.",
    ) {
    override suspend fun execute(args: SystemInfoArgs): String {
        val category = args.category.lowercase()
        if (category !in setOf("all", "cpu", "memory", "disk", "os")) {
            return "[ERROR] Unknown category '${args.category}'."
        }
        val (command, commandArgs) =
            when {
                isWindows() -> {
                    "systeminfo" to emptyList()
                }

                category == "disk" -> {
                    "df" to listOf("-h", ".")
                }

                category == "memory" && System.getProperty("os.name").lowercase().contains("linux") -> {
                    "free" to listOf("-h")
                }

                else -> {
                    "uname" to listOf("-a")
                }
            }
        return sandboxRunner.execute(executable = command, arguments = commandArgs).renderCommandOutput()
    }
}

class DockerTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<DockerArgs>(
        argsType = typeToken<DockerArgs>(),
        name = "docker",
        description = "Run Docker CLI commands through the configured sandbox.",
    ) {
    override suspend fun execute(args: DockerArgs): String {
        val action = args.action.lowercase()
        if (action !in DOCKER_ACTIONS) return "[ERROR] Unsupported Docker action '${args.action}'."
        if (args.arguments.any(::containsShellSyntax)) return "[BLOCKED] Pipes, redirections, and shell syntax are not allowed"
        val commandArguments = if (action == "compose") listOf("compose") + args.arguments else listOf(action) + args.arguments
        return sandboxRunner
            .execute(executable = "docker", arguments = commandArguments, workingDirectory = workDir, timeoutMillis = 60_000)
            .renderCommandOutput()
    }

    private companion object {
        val DOCKER_ACTIONS = setOf("ps", "images", "run", "stop", "rm", "logs", "compose")
    }
}

class EnvironmentTool :
    SimpleTool<EnvironmentArgs>(
        argsType = typeToken<EnvironmentArgs>(),
        name = "environment",
        description = "Report whether a host environment variable is exposed to tools.",
    ) {
    override suspend fun execute(args: EnvironmentArgs): String {
        if (args.action.lowercase() !in setOf("get", "list")) {
            return "[ERROR] Unknown action '${args.action}'. Use: get, list."
        }
        return "[BLOCKED] Host environment variables are not exposed to agent tools."
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
