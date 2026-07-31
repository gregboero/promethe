package dev.promethe.core

import java.io.File

/**
 * JVM actual implementation of [ContextFileLoader].
 * Scans the working directory for well-known project context files
 * and returns their contents (capped at 50 KB each to avoid prompt bloat).
 */
actual object ContextFileLoader {
    actual val CONTEXT_FILES: List<String> =
        listOf(
            ".promethe.md",
            "SOUL.md",
            "AGENTS.md",
            ".promethe/context.md",
            "CONTEXT.md",
        )

    actual fun loadContextFiles(workingDir: String?): Map<String, String> {
        val dir = if (workingDir != null) File(workingDir) else File(System.getProperty("user.dir"))
        if (!dir.exists()) return emptyMap()

        return buildMap {
            for (name in CONTEXT_FILES) {
                val file = File(dir, name)
                if (file.exists() && file.isFile && file.length() < 50_000) {
                    put(name, file.readText().trim())
                }
            }
        }
    }
}
