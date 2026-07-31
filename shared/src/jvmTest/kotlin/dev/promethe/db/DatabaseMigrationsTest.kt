package dev.promethe.db

import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseMigrationsTest {
    @Test
    fun `persistent SQLite databases record security and profile migrations`() {
        val directory = createTempDirectory("promethe-migration-test").toFile()
        val dbFile = java.io.File(directory, "promethe.db")
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            DatabaseFactory.create(url)
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT version FROM flyway_schema_history WHERE version = '3'").use { rows ->
                        assertTrue(rows.next(), "V3 gateway security migration should be recorded")
                    }
                    statement.executeQuery("SELECT version FROM flyway_schema_history WHERE version = '4'").use { rows ->
                        assertTrue(rows.next(), "V4 reasoning effort migration should be recorded")
                    }
                    statement.executeQuery("SELECT reasoning_effort FROM agent_profiles WHERE id = 'main'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("AUTO", rows.getString(1))
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `representative 0_1 database upgrades additively and preserves conversations`() {
        val directory = createTempDirectory("promethe-0_1-upgrade-test").toFile()
        val dbFile = java.io.File(directory, "promethe.db")
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE sessions (id VARCHAR(255) PRIMARY KEY, created_at BIGINT NOT NULL, metadata TEXT)",
                    )
                    statement.execute(
                        "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id VARCHAR(255) NOT NULL, " +
                            "role VARCHAR(50) NOT NULL, content TEXT NOT NULL, timestamp BIGINT NOT NULL)",
                    )
                    statement.execute("INSERT INTO sessions(id, created_at, metadata) VALUES ('legacy-session', 1, '{}')")
                    statement.execute(
                        "INSERT INTO messages(session_id, role, content, timestamp) " +
                            "VALUES ('legacy-session', 'user', 'legacy message', 2)",
                    )
                }
            }

            DatabaseFactory.create(url)

            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT content FROM messages WHERE session_id = 'legacy-session'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("legacy message", rows.getString(1))
                    }
                    statement.executeQuery("PRAGMA table_info(sessions)").use { rows ->
                        val columns = mutableSetOf<String>()
                        while (rows.next()) columns += rows.getString("name")
                        assertTrue("title" in columns, "1.0 should add the session title column")
                    }
                    statement.executeQuery("SELECT version FROM flyway_schema_history WHERE version = '3'").use { rows ->
                        assertTrue(rows.next(), "V3 must be applied after the legacy schema is preserved")
                    }
                    statement.executeQuery("PRAGMA table_info(agent_profiles)").use { rows ->
                        val columns = mutableSetOf<String>()
                        while (rows.next()) columns += rows.getString("name")
                        assertTrue("reasoning_effort" in columns, "V4 must preserve the reasoning effort column")
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
