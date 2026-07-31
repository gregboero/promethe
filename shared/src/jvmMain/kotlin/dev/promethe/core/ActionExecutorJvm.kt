package dev.promethe.core

import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import kotlinx.coroutines.runBlocking

/**
 * Process execution is unavailable until bootstrap installs the native sandbox runner.
 * This bridge exists for legacy call sites; it never falls back to a host process.
 */
internal object LocalProcessSandbox {
    @Volatile
    private var commandRunner: SandboxedCommandRunner? = null

    fun install(runner: SandboxedCommandRunner) {
        commandRunner = runner
    }

    fun clear() {
        commandRunner = null
    }

    fun execute(
        command: String,
        args: List<String>,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): String {
        val runner = commandRunner ?: return "[SANDBOX BACKEND_UNAVAILABLE] Native sandbox is not configured."
        return runBlocking {
            runner
                .execute(
                    executable = command,
                    arguments = args,
                    timeoutMillis = timeoutMs,
                ).renderCommandOutput()
                .let { output -> truncateOutput(output, maxOutputBytes) }
        }
    }
}

/** JVM actual implementation routed exclusively through [LocalProcessSandbox]. */
actual fun runLocalProcess(
    command: String,
    args: List<String>,
    timeoutMs: Long,
    maxOutputBytes: Int,
): String {
    if (isBlockedCommand(command, args)) {
        return "[BLOCKED] Command rejected by security policy: $command ${args.joinToString(" ")}"
    }
    if (isShellInterpreter(command) || args.any(::containsShellSyntax)) {
        return "[BLOCKED] Shell interpreters, pipes, and redirections are not allowed"
    }
    return LocalProcessSandbox.execute(command, args, timeoutMs, maxOutputBytes)
}

private fun isShellInterpreter(command: String): Boolean =
    command.trim().substringAfterLast('/').substringAfterLast('\\').lowercase() in
        setOf("cmd", "cmd.exe", "sh", "bash", "zsh", "fish", "powershell", "pwsh")

private fun containsShellSyntax(value: String): Boolean = listOf("|", ">", "<", ";", "&&", "||", "`", "$(").any(value::contains)
