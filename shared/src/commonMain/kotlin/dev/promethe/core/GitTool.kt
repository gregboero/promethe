package dev.promethe.core

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GitTool — git operations for the agent.
 *
 * Provides: status, diff, log, commit, branch, checkout.
 * Executes structured arguments through ActionExecutor and the native sandbox.
 */
class GitTool(
    private val actionExecutor: ActionExecutor,
    private val workingDir: String = ".",
) {
    /**
     * Execute a git command and return output.
     */
    suspend fun execute(arguments: List<String>): String =
        try {
            actionExecutor.execute(
                "execute_command",
                buildJsonObject {
                    put("executable", "git")
                    put(
                        "arguments",
                        buildJsonArray {
                            arguments.forEach { add(it) }
                        },
                    )
                    put("cwd", workingDir)
                },
            )
        } catch (e: Exception) {
            "Git error: ${e.message}"
        }

    suspend fun status(): String = execute(listOf("status", "--porcelain"))

    suspend fun diff(staged: Boolean = false): String = execute(if (staged) listOf("diff", "--staged") else listOf("diff"))

    suspend fun log(count: Int = 10): String = execute(listOf("log", "--oneline", "-n", count.coerceIn(1, 100).toString()))

    suspend fun add(files: String = "."): String = execute(listOf("add", files))

    suspend fun commit(message: String): String = execute(listOf("commit", "-m", message))

    suspend fun branch(): String = execute(listOf("branch"))

    suspend fun checkout(branch: String): String = execute(listOf("checkout", branch))

    suspend fun currentBranch(): String = execute(listOf("rev-parse", "--abbrev-ref", "HEAD"))
}
