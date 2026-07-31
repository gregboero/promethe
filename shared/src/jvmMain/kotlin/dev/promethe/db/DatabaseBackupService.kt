package dev.promethe.db

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

data class RestoreResult(
    val restoredDatabase: File,
    val previousDatabaseBackup: File?,
)

object DatabaseBackupService {
    fun backup(
        databaseUrl: String,
        output: File,
    ): File {
        val source = sqliteFile(databaseUrl)
        require(source.isFile) { "SQLite database does not exist: ${source.absolutePath}" }
        val destination = output.absoluteFile
        require(!destination.exists()) { "Backup destination already exists: ${destination.absolutePath}" }
        destination.parentFile?.mkdirs()

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("VACUUM INTO '${destination.absolutePath.replace("'", "''")}'")
            }
        }
        requireIntegrity(destination)
        return destination
    }

    fun restore(
        databaseUrl: String,
        input: File,
    ): RestoreResult {
        val target = sqliteFile(databaseUrl)
        val source = input.absoluteFile
        require(source.isFile) { "Backup file does not exist: ${source.absolutePath}" }
        require(source.canonicalFile != target.canonicalFile) { "Backup and active database must be different files" }
        requireIntegrity(source)

        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.restore-${UUID.randomUUID()}.tmp")
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        requireIntegrity(temporary)

        val previous =
            if (target.exists()) {
                val safetyCopy = File(target.parentFile, "${target.name}.pre-restore-${Instant.now().toEpochMilli()}.bak")
                move(target, safetyCopy, replace = false)
                safetyCopy
            } else {
                null
            }

        try {
            move(temporary, target, replace = true)
        } catch (error: Exception) {
            if (previous != null && previous.exists() && !target.exists()) {
                move(previous, target, replace = true)
            }
            throw error
        } finally {
            temporary.delete()
        }
        requireIntegrity(target)
        return RestoreResult(target, previous)
    }

    fun requireIntegrity(database: File) {
        val url = "jdbc:sqlite:${database.absolutePath}"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    require(rows.next() && rows.getString(1).equals("ok", ignoreCase = true)) {
                        "SQLite integrity check failed for ${database.absolutePath}"
                    }
                }
            }
        }
    }

    private fun sqliteFile(databaseUrl: String): File {
        require(databaseUrl.startsWith(SQLITE_PREFIX) && !databaseUrl.contains(":memory:")) {
            "Backup and restore require a file-backed SQLite PROMETHE_DB_URL"
        }
        val path = databaseUrl.removePrefix(SQLITE_PREFIX)
        require(path.isNotBlank()) { "SQLite database path is empty" }
        return File(path).absoluteFile
    }

    private fun move(
        source: File,
        destination: File,
        replace: Boolean,
    ) {
        val options =
            if (replace) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            val fallback = if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            Files.move(source.toPath(), destination.toPath(), *fallback)
        }
    }

    private const val SQLITE_PREFIX = "jdbc:sqlite:"
}
