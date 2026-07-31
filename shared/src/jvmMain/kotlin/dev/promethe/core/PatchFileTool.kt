package dev.promethe.core

/**
 * PatchFileTool — diff-based file editing for the agent.
 *
 * Supports:
 *   - Search & replace in a file
 *   - Insert at line number
 *   - Delete lines
 *   - Apply unified diff patches
 *
 * More surgical than full file rewrites — reduces token usage and errors.
 */
class PatchFileTool {
    data class PatchResult(
        val success: Boolean,
        val message: String,
        val linesModified: Int = 0,
    )

    /**
     * Search and replace in a file.
     * If [allOccurrences] is true, replaces all matches; otherwise only the first.
     */
    fun searchReplace(
        filePath: String,
        search: String,
        replace: String,
        allOccurrences: Boolean = false,
    ): PatchResult {
        val file = java.io.File(filePath)
        if (!file.exists()) return PatchResult(false, "File not found: $filePath")

        val content = file.readText()
        if (search !in content) return PatchResult(false, "Search string not found in $filePath")

        val count =
            if (allOccurrences) {
                content.split(search).size - 1
            } else {
                1
            }

        val newContent =
            if (allOccurrences) {
                content.replace(search, replace)
            } else {
                content.replaceFirst(search, replace)
            }

        file.writeText(newContent)
        return PatchResult(true, "Replaced $count occurrence(s) in $filePath", count)
    }

    /**
     * Insert text at a specific line number (1-indexed).
     */
    fun insertAtLine(
        filePath: String,
        lineNumber: Int,
        text: String,
    ): PatchResult {
        val file = java.io.File(filePath)
        if (!file.exists()) return PatchResult(false, "File not found: $filePath")

        val lines = file.readLines().toMutableList()
        val insertIndex = (lineNumber - 1).coerceIn(0, lines.size)
        val newLines = text.split("\n")
        lines.addAll(insertIndex, newLines)

        file.writeText(lines.joinToString("\n"))
        return PatchResult(true, "Inserted ${newLines.size} line(s) at line $lineNumber", newLines.size)
    }

    /**
     * Delete lines from a file (1-indexed, inclusive range).
     */
    fun deleteLines(
        filePath: String,
        startLine: Int,
        endLine: Int,
    ): PatchResult {
        val file = java.io.File(filePath)
        if (!file.exists()) return PatchResult(false, "File not found: $filePath")

        val lines = file.readLines().toMutableList()
        val start = (startLine - 1).coerceIn(0, lines.size)
        val end = endLine.coerceIn(start, lines.size)
        val count = end - start

        if (count <= 0) return PatchResult(false, "Invalid line range: $startLine-$endLine")

        for (i in 0 until count) {
            if (start < lines.size) lines.removeAt(start)
        }

        file.writeText(lines.joinToString("\n"))
        return PatchResult(true, "Deleted $count line(s) ($startLine-$endLine)", count)
    }

    /**
     * Apply a unified diff patch to a file.
     * Simplified: handles +/- lines but not complex hunks.
     */
    fun applyPatch(
        filePath: String,
        patch: String,
    ): PatchResult {
        val file = java.io.File(filePath)
        if (!file.exists()) return PatchResult(false, "File not found: $filePath")

        val lines = file.readLines().toMutableList()
        var modified = 0

        val patchLines = patch.lines()
        var currentLine = 0

        for (patchLine in patchLines) {
            when {
                patchLine.startsWith("@@") -> {
                    // Parse hunk header: @@ -start,count +start,count @@
                    val match = Regex("""@@ -(\d+)""").find(patchLine)
                    currentLine = (match?.groupValues?.get(1)?.toIntOrNull() ?: 1) - 1
                }

                patchLine.startsWith("-") && !patchLine.startsWith("---") -> {
                    // Remove line
                    if (currentLine < lines.size) {
                        lines.removeAt(currentLine)
                        modified++
                    }
                }

                patchLine.startsWith("+") && !patchLine.startsWith("+++") -> {
                    // Add line
                    lines.add(currentLine, patchLine.substring(1))
                    currentLine++
                    modified++
                }

                patchLine.startsWith(" ") -> {
                    currentLine++ // Context line — skip
                }
            }
        }

        file.writeText(lines.joinToString("\n"))
        return PatchResult(true, "Applied patch: $modified line(s) modified", modified)
    }
}
