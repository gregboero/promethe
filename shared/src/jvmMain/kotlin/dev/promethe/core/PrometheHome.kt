package dev.promethe.core

import java.io.File

/**
 * Central reference for the Promethe home directory (`~/.promethe/`).
 *
 * All runtime data (DB, skills, plugins, credentials, MCP config) lives here.
 * This ensures paths are stable regardless of the process working directory.
 *
 * Pattern matches [CredentialsStore] which already uses this directory.
 */
object PrometheHome {
    /**
     * Root directory: `~/.promethe/`.
     *
     * Resolved on every access so tests can redirect the whole runtime data
     * tree via the `promethe.home` system property (absolute path to use as
     * home dir) without touching the real user directory.
     */
    val dir: File
        get() =
            System.getProperty("promethe.home")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".promethe")

    /** Skills directory: `~/.promethe/skills/` */
    val skillsDir: File get() = File(dir, "skills")

    /** Plugins directory: `~/.promethe/plugins/` */
    val pluginsDir: File get() = File(dir, "plugins")

    /** Default agent workspace: `~/.promethe/workspace/`. */
    val workspaceDir: File get() = File(dir, "workspace")

    /** Internal productivity-tool storage, never exposed to sandboxed commands. */
    val productivityDataDir: File get() = File(dir, "productivity")

    /** SQLite database file: `~/.promethe/promethe.db` */
    val dbFile: File get() = File(dir, "promethe.db")

    /** Absolute path string for the home directory. */
    val absolutePath: String get() = dir.absolutePath

    /**
     * Ensure all required directories exist.
     * Safe to call multiple times (idempotent).
     */
    fun ensureDirectories() {
        dir.mkdirs()
        skillsDir.mkdirs()
        pluginsDir.mkdirs()
        workspaceDir.mkdirs()
        productivityDataDir.mkdirs()
    }
}
