package dev.promethe.db

import dev.promethe.core.PrometheHome
import dev.promethe.core.config.ConfigProvider
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.DriverManager

/**
 * Factory for creating [PrometheDatabase] instances.
 *
 * Configuration priority:
 * 1. Explicit [url] parameter
 * 2. `PROMETHE_DB_URL` environment variable
 * 3. Default: `jdbc:sqlite:~/.promethe/promethe.db` (persistent)
 */
object DatabaseFactory {
    /**
     * Create a persistent database connection.
     * Creates the parent directory if using SQLite file storage.
     */
    fun create(url: String? = null): PrometheDatabase {
        val dbUrl = resolveUrl(url)

        // Create data directory for file-based SQLite
        if (dbUrl.startsWith("jdbc:sqlite:") && !dbUrl.contains(":memory:")) {
            val dbPath = dbUrl.removePrefix("jdbc:sqlite:")
            java.io
                .File(dbPath)
                .parentFile
                ?.mkdirs()
        }

        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        return PrometheDatabase(db).also {
            it.initialize()
            DatabaseMigrations.migrate(dbUrl)
        }
    }

    fun resolveUrl(url: String? = null): String =
        url
            ?: ConfigProvider.get().get("PROMETHE_DB_URL")
            ?: "jdbc:sqlite:${PrometheHome.dbFile.absolutePath}"

    /**
     * Create an in-memory database — for tests only.
     *
     * SQLite :memory: DBs are per-connection and destroyed on close.
     * Exposed closes connections after each transaction.
     * Fix: wrap the real connection so close() is a no-op, keeping
     * the in-memory DB alive across transactions.
     */
    fun createInMemory(): PrometheDatabase {
        val realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Delegate everything except close() — keep the connection alive
        val keepAliveConn =
            object : Connection by realConn {
                override fun close() { /* no-op */ }
            }
        val db = Database.connect({ keepAliveConn })
        return PrometheDatabase(db).also { it.initialize() }
    }
}
