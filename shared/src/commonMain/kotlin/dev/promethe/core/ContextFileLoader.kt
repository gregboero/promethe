package dev.promethe.core

/**
 * Loads project context files (.promethe.md, SOUL.md, AGENTS.md, etc.)
 * from the working directory and returns their contents for injection
 * into the system prompt.
 */
expect object ContextFileLoader {
    /** Standard context file names, checked in priority order. */
    val CONTEXT_FILES: List<String>

    /**
     * Load all found context files from the given directory.
     * @param workingDir directory to scan; defaults to the process working directory on JVM.
     * @return a map of filename → content for every file that exists and is under 50 KB.
     */
    fun loadContextFiles(workingDir: String? = null): Map<String, String>
}
