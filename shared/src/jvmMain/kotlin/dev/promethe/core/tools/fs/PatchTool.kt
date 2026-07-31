package dev.promethe.core.tools.fs

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.WorkspaceFileReader
import dev.promethe.core.WorkspaceFileWriter
import dev.promethe.core.WorkspacePathPolicy
import kotlinx.serialization.Serializable
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// ══════════════════════════════════════════════════════════════
// PatchTool — apply unified diff patches to files
//
// Hermes "patch" tool parity: apply a unified diff (git-style)
// to one or more files.
// ══════════════════════════════════════════════════════════════

@Serializable
data class PatchArgs(
    @property:LLMDescription("The unified diff (patch) content to apply. Use standard git diff format.")
    val patch: String,
    @property:LLMDescription("Working directory for relative paths. Default is agent workDir.")
    val workDir: String = "",
)

class PatchTool(
    private val defaultWorkDir: String,
    private val secureReader: WorkspaceFileReader?,
    private val secureWriter: WorkspaceFileWriter?,
) : SimpleTool<PatchArgs>(
        argsType = typeToken<PatchArgs>(),
        name = "patch",
        description = "Apply a unified diff (patch) to files. Accepts standard git diff format. Returns a summary of applied changes.",
    ) {
    override suspend fun execute(args: PatchArgs): String {
        val wd = WorkspacePathPolicy.resolve(defaultWorkDir, args.workDir.ifBlank { "." })
            ?: return "[ERROR] Access denied: working directory must be inside the workspace"
        val reader = secureReader ?: return "[ERROR] Secure workspace reads are unavailable"
        val writer = secureWriter ?: return "[ERROR] Secure workspace writes are unavailable"
        val root = Path.of(defaultWorkDir).toRealPath()
        val relativeWorkingDirectory = root.relativize(wd.toPath()).toString()
        return try {
            val hunks = parsePatch(args.patch)
            if (hunks.isEmpty()) return "[ERROR] No valid hunks found in patch."

            val results = mutableListOf<String>()
            for (hunk in hunks) {
                val targetPath =
                    Path
                        .of(relativeWorkingDirectory)
                        .resolve(hunk.filePath)
                        .normalize()
                        .toString()
                val original =
                    try {
                        reader.read(targetPath)
                    } catch (_: NoSuchFileException) {
                        if (hunk.isNewFile) {
                            writer.write(targetPath, hunk.additions.joinToString("\n"))
                            results.add("✅ Created ${hunk.filePath} (+${hunk.additions.size} lines)")
                            continue
                        }
                        results.add("❌ File not found: ${hunk.filePath}")
                        continue
                    }

                val originalLines = original.lines().toMutableList()
                var offset = 0
                for (change in hunk.changes) {
                    val lineIdx = change.lineNumber - 1 + offset
                    when (change.type) {
                        ChangeType.REMOVE -> {
                            if (lineIdx in originalLines.indices &&
                                originalLines[lineIdx].trim() == change.content.trim()
                            ) {
                                originalLines.removeAt(lineIdx)
                                offset--
                            }
                        }

                        ChangeType.ADD -> {
                            val insertIdx = (lineIdx).coerceIn(0, originalLines.size)
                            originalLines.add(insertIdx, change.content)
                            offset++
                        }
                    }
                }
                writer.write(targetPath, originalLines.joinToString("\n"))
                val adds = hunk.changes.count { it.type == ChangeType.ADD }
                val removes = hunk.changes.count { it.type == ChangeType.REMOVE }
                results.add("✅ Patched ${hunk.filePath} (+$adds -$removes)")
            }
            results.joinToString("\n")
        } catch (e: Exception) {
            "[ERROR] Patch failed: ${e.message}"
        }
    }

    private data class PatchHunk(
        val filePath: String,
        val isNewFile: Boolean,
        val additions: List<String>,
        val changes: List<LineChange>,
    )

    private data class LineChange(
        val lineNumber: Int,
        val type: ChangeType,
        val content: String,
    )

    private enum class ChangeType { ADD, REMOVE }

    private fun parsePatch(patchText: String): List<PatchHunk> {
        val hunks = mutableListOf<PatchHunk>()
        val lines = patchText.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            // Look for --- a/file or +++ b/file
            if (line.startsWith("--- ") || line.startsWith("+++ b/")) {
                var filePath = ""
                val isNew = i > 0 && lines[i - 1].contains("new file")

                // Find +++ line for target file
                if (line.startsWith("--- ")) {
                    i++
                    if (i < lines.size && lines[i].startsWith("+++ ")) {
                        filePath = lines[i].removePrefix("+++ b/").removePrefix("+++ ")
                    }
                } else {
                    filePath = line.removePrefix("+++ b/").removePrefix("+++ ")
                }

                if (filePath.isBlank() || filePath == "/dev/null") {
                    i++
                    continue
                }

                i++
                val changes = mutableListOf<LineChange>()
                val additions = mutableListOf<String>()
                var currentLine = 0

                while (i < lines.size) {
                    val cl = lines[i]
                    when {
                        cl.startsWith("@@") -> {
                            // Parse @@ -X,Y +A,B @@
                            val match = Regex("""@@ -(\d+)""").find(cl)
                            currentLine = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        }

                        cl.startsWith("-") && !cl.startsWith("---") -> {
                            changes.add(LineChange(currentLine, ChangeType.REMOVE, cl.removePrefix("-")))
                            currentLine++
                        }

                        cl.startsWith("+") && !cl.startsWith("+++") -> {
                            changes.add(LineChange(currentLine, ChangeType.ADD, cl.removePrefix("+")))
                            additions.add(cl.removePrefix("+"))
                        }

                        cl.startsWith(" ") || cl.isEmpty() -> {
                            currentLine++
                        }

                        cl.startsWith("diff ") || cl.startsWith("--- ") -> {
                            break
                        }

                        else -> {
                            currentLine++
                        }
                    }
                    i++
                }

                hunks.add(PatchHunk(filePath, isNew, additions, changes))
                continue
            }
            i++
        }
        return hunks
    }
}
