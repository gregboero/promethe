package dev.promethe.db

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseBackupServiceTest {
    @Test
    fun `backup is consistent and restore preserves a safety copy`() {
        val directory = createTempDirectory("promethe-backup-test").toFile()
        try {
            val database = File(directory, "promethe.db")
            val url = "jdbc:sqlite:${database.absolutePath}"
            writeValue(url, "before")

            val backup = DatabaseBackupService.backup(url, File(directory, "backup.db"))
            writeValue(url, "after")
            val result = DatabaseBackupService.restore(url, backup)

            assertEquals("before", readValue(url))
            assertTrue(result.previousDatabaseBackup?.isFile == true)
            assertEquals("after", readValue("jdbc:sqlite:${result.previousDatabaseBackup!!.absolutePath}"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects a corrupt backup without replacing the database`() {
        val directory = createTempDirectory("promethe-restore-test").toFile()
        try {
            val database = File(directory, "promethe.db")
            val url = "jdbc:sqlite:${database.absolutePath}"
            writeValue(url, "safe")
            val corrupt = File(directory, "corrupt.db").apply { writeText("not sqlite") }

            assertFailsWith<Exception> { DatabaseBackupService.restore(url, corrupt) }
            assertEquals("safe", readValue(url))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeValue(
        url: String,
        value: String,
    ) {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS sample (value TEXT NOT NULL)")
                statement.execute("DELETE FROM sample")
            }
            connection.prepareStatement("INSERT INTO sample(value) VALUES (?)").use { statement ->
                statement.setString(1, value)
                statement.executeUpdate()
            }
        }
    }

    private fun readValue(url: String): String =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM sample").use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }
}
