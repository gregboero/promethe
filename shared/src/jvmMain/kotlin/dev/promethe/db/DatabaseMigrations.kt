package dev.promethe.db

import org.flywaydb.core.Flyway

/** Runs versioned migrations after Exposed has preserved compatibility with legacy schemas. */
object DatabaseMigrations {
    fun migrate(sqliteUrl: String) {
        if (!sqliteUrl.startsWith("jdbc:sqlite:") || sqliteUrl.contains(":memory:")) return
        Flyway
            .configure()
            .dataSource(sqliteUrl, null, null)
            .locations("classpath:db/migration")
            // Existing Promethe databases were historically created by Exposed.
            // Mark the legacy V1/V2 state before applying current migrations.
            .baselineOnMigrate(true)
            .baselineVersion("2")
            .load()
            .migrate()
    }
}
