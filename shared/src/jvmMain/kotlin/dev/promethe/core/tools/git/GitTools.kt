package dev.promethe.core.tools.git

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.WorkspacePathPolicy
import dev.promethe.core.projectScopedPath
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import kotlinx.serialization.Serializable

@Serializable
data class GitStatusArgs(
    @property:LLMDescription("Working directory (defaults to current). Optional.")
    val cwd: String = ".",
)

@Serializable
data class GitDiffArgs(
    @property:LLMDescription("What to diff: 'staged', 'unstaged', or a commit ref like 'HEAD~1'. Default 'unstaged'.")
    val target: String = "unstaged",
    @property:LLMDescription("Optional path filter (e.g. 'src/').")
    val path: String = "",
    @property:LLMDescription("Working directory. Optional.")
    val cwd: String = ".",
)

@Serializable
data class GitCommitArgs(
    @property:LLMDescription("Commit message.")
    val message: String,
    @property:LLMDescription("If true, stage all modified/deleted files before committing (git add -A). Default true.")
    val stageAll: Boolean = true,
    @property:LLMDescription("Working directory. Optional.")
    val cwd: String = ".",
)

@Serializable
data class GitLogArgs(
    @property:LLMDescription("Maximum number of commits. Default 10.")
    val limit: Int = 10,
    @property:LLMDescription("Filter by author name/email. Optional.")
    val author: String = "",
    @property:LLMDescription("Filter by file path. Optional.")
    val path: String = "",
    @property:LLMDescription("Format: 'oneline', 'short', or 'full'. Default 'oneline'.")
    val format: String = "oneline",
    @property:LLMDescription("Working directory. Optional.")
    val cwd: String = ".",
)

@Serializable
data class GitBranchArgs(
    @property:LLMDescription("Action: 'list', 'create', 'switch', 'delete'. Default 'list'.")
    val action: String = "list",
    @property:LLMDescription("Branch name (required for create/switch/delete).")
    val name: String = "",
    @property:LLMDescription("Working directory. Optional.")
    val cwd: String = ".",
)

private suspend fun runGit(
    sandboxRunner: SandboxedCommandRunner,
    workDir: String,
    vararg args: String,
): String = sandboxRunner.execute(executable = "git", arguments = args.toList(), workingDirectory = workDir).renderCommandOutput()

private suspend fun resolveGitDirectory(
    workspace: String,
    requested: String,
): String? = WorkspacePathPolicy.resolve(workspace, projectScopedPath(requested))?.takeIf { it.isDirectory }?.path

private fun isSafePath(value: String): Boolean =
    value.isBlank() ||
        (!value.startsWith('/') && !value.startsWith('\\') && !WINDOWS_ABSOLUTE_PATH.matches(value) && value.split('/', '\\').none { it == ".." })

private fun isSafeRef(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/@~^{}-]*")) && !value.contains("..")

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

class GitStatusTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<GitStatusArgs>(
        argsType = typeToken<GitStatusArgs>(),
        name = "git_status",
        description = "Show git status: branch, staged/unstaged changes, untracked files.",
    ) {
    override suspend fun execute(args: GitStatusArgs): String {
        val dir = resolveGitDirectory(workDir, args.cwd) ?: return "[ERROR] Working directory must exist inside the workspace"
        return runGit(sandboxRunner, dir, "status", "--short", "--branch")
    }
}

class GitDiffTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<GitDiffArgs>(
        argsType = typeToken<GitDiffArgs>(),
        name = "git_diff",
        description = "Show git diff: staged, unstaged, or between commits.",
    ) {
    override suspend fun execute(args: GitDiffArgs): String {
        val dir = resolveGitDirectory(workDir, args.cwd) ?: return "[ERROR] Working directory must exist inside the workspace"
        if (!isSafePath(args.path)) return "[BLOCKED] Path must stay inside the workspace"
        val diffArgs = mutableListOf("diff", "--stat")
        val target = args.target.lowercase()
        if (target == "staged") {
            diffArgs.add("--cached")
        } else if (target != "unstaged") {
            if (!isSafeRef(args.target)) return "[BLOCKED] Invalid git reference"
            diffArgs.add(args.target)
        }
        if (args.path.isNotBlank()) {
            diffArgs.add("--")
            diffArgs.add(args.path)
        }
        return runGit(sandboxRunner, dir, *diffArgs.toTypedArray())
    }
}

class GitCommitTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<GitCommitArgs>(
        argsType = typeToken<GitCommitArgs>(),
        name = "git_commit",
        description = "Stage and commit changes with a message.",
    ) {
    override suspend fun execute(args: GitCommitArgs): String {
        val dir = resolveGitDirectory(workDir, args.cwd) ?: return "[ERROR] Working directory must exist inside the workspace"
        if (args.message.isBlank()) return "[ERROR] Commit message is required."
        if (args.stageAll) {
            val addOutput = runGit(sandboxRunner, dir, "add", "-A")
            if (addOutput.startsWith("[EXIT") || addOutput.startsWith("[SANDBOX")) return "[ERROR] git add failed: $addOutput"
        }
        return runGit(sandboxRunner, dir, "commit", "-m", args.message)
    }
}

class GitLogTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<GitLogArgs>(
        argsType = typeToken<GitLogArgs>(),
        name = "git_log",
        description = "Show git commit history with optional filters.",
    ) {
    override suspend fun execute(args: GitLogArgs): String {
        val dir = resolveGitDirectory(workDir, args.cwd) ?: return "[ERROR] Working directory must exist inside the workspace"
        if (!isSafePath(args.path)) return "[BLOCKED] Path must stay inside the workspace"
        val logArgs = mutableListOf("log", "-n", args.limit.coerceIn(1, 100).toString())
        when (args.format.lowercase()) {
            "oneline" -> logArgs.add("--oneline")
            "short" -> logArgs.add("--format=short")
            "full" -> logArgs.add("--format=fuller")
            else -> return "[ERROR] Unknown format '${args.format}'. Use: oneline, short, full."
        }
        if (args.author.isNotBlank()) logArgs.add("--author=${args.author}")
        if (args.path.isNotBlank()) {
            logArgs.add("--")
            logArgs.add(args.path)
        }
        return runGit(sandboxRunner, dir, *logArgs.toTypedArray())
    }
}

class GitBranchTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<GitBranchArgs>(
        argsType = typeToken<GitBranchArgs>(),
        name = "git_branch",
        description = "Manage git branches: list, create, switch, or delete.",
    ) {
    override suspend fun execute(args: GitBranchArgs): String {
        val dir = resolveGitDirectory(workDir, args.cwd) ?: return "[ERROR] Working directory must exist inside the workspace"
        return when (args.action.lowercase()) {
            "list" -> {
                runGit(sandboxRunner, dir, "branch", "-a")
            }

            "create", "switch", "delete" -> {
                if (!isSafeRef(args.name)) return "[ERROR] A valid branch name is required."
                val command = if (args.action.lowercase() == "switch") "checkout" else "branch"
                val deleteFlag = if (args.action.lowercase() == "delete") listOf("-d") else emptyList()
                val output = runGit(sandboxRunner, dir, command, *deleteFlag.toTypedArray(), args.name)
                if (output.startsWith("[EXIT") || output.startsWith("[SANDBOX")) {
                    output
                } else {
                    "${branchActionMessage(args.action)}: ${args.name}\n$output"
                }
            }

            else -> {
                "[ERROR] Unknown action '${args.action}'. Use: list, create, switch, delete."
            }
        }
    }
}

private fun branchActionMessage(action: String): String =
    when (action.lowercase()) {
        "create" -> "Created branch"
        "switch" -> "Switched to branch"
        "delete" -> "Deleted branch"
        else -> "Updated branch"
    }
