package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Dedicated, versioned LAB journal. Transactions also coordinate separate processes. */
class HarnessStore(
    path: Path,
) {
    private val url: String

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        url = "jdbc:sqlite:${path.toAbsolutePath()}"
        transaction { db ->
            db.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS harness_schema(version INTEGER NOT NULL)")
                s.execute("INSERT INTO harness_schema SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM harness_schema)")
                s.executeQuery("SELECT version FROM harness_schema").use { r -> check(r.next() && r.getInt(1) == 1) }
                s.execute("CREATE TABLE IF NOT EXISTS harness_revisions(id TEXT PRIMARY KEY, session TEXT NOT NULL, body TEXT NOT NULL, validated INTEGER NOT NULL DEFAULT 0)")
                s.execute("CREATE TABLE IF NOT EXISTS harness_sessions(session TEXT PRIMARY KEY, active TEXT, previous TEXT, pending TEXT, run TEXT)")
                s.execute("CREATE TABLE IF NOT EXISTS harness_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, run TEXT, revision TEXT, event TEXT NOT NULL, created INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS harness_budget(id TEXT PRIMARY KEY, reserved INTEGER NOT NULL CHECK(reserved>=0), charged INTEGER, settled INTEGER NOT NULL DEFAULT 0)")
            }
        }
    }

    fun <T> transaction(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use { db ->
            db.createStatement().use {
                it.execute("PRAGMA busy_timeout=10000")
                it.execute("BEGIN IMMEDIATE")
            }
            try {
                val value = block(db)
                db.createStatement().use { it.execute("COMMIT") }
                value
            } catch (error: Throwable) {
                runCatching { db.createStatement().use { it.execute("ROLLBACK") } }
                throw error
            }
        }

    fun put(revision: HarnessRevision) =
        transaction { db ->
            db.prepareStatement("INSERT INTO harness_revisions(id,session,body) VALUES(?,?,?)").use {
                it.setString(1, revision.id)
                it.setString(2, revision.sessionId)
                it.setString(3, Json.encodeToString(revision))
                it.executeUpdate()
            }
        }

    fun get(
        session: String,
        id: String,
    ): HarnessRevision =
        transaction { db ->
            db.prepareStatement("SELECT body,validated FROM harness_revisions WHERE id=? AND session=?").use {
                it.setString(1, id)
                it.setString(2, session)
                it.executeQuery().use { rows ->
                    require(rows.next()) { "Unknown revision for this session" }
                    Json.decodeFromString<HarnessRevision>(rows.getString(1)).copy(validated = rows.getBoolean(2))
                }
            }
        }

    fun validate(
        session: String,
        id: String,
        passed: Boolean,
    ) = transaction { db ->
        db.prepareStatement("UPDATE harness_revisions SET validated=? WHERE id=? AND session=?").use {
            it.setBoolean(1, passed)
            it.setString(2, id)
            it.setString(3, session)
            it.executeUpdate()
        }
    }

    data class State(
        val active: String? = null,
        val previous: String? = null,
        val pending: String? = null,
        val run: String? = null,
    )

    fun state(session: String): State = transaction { readState(it, session) }

    private fun readState(
        db: Connection,
        session: String,
    ): State =
        db.prepareStatement("SELECT active,previous,pending,run FROM harness_sessions WHERE session=?").use {
            it.setString(1, session)
            it.executeQuery().use { r -> if (r.next()) State(r.getString(1), r.getString(2), r.getString(3), r.getString(4)) else State() }
        }

    fun update(
        session: String,
        expected: State,
        next: State,
        event: String,
        revision: String? = next.active,
    ) = transaction { db ->
        check(readState(db, session) == expected) { "Session changed; inspect again" }
        db.prepareStatement("INSERT INTO harness_sessions VALUES(?,?,?,?,?) ON CONFLICT(session) DO UPDATE SET active=excluded.active,previous=excluded.previous,pending=excluded.pending,run=excluded.run").use {
            it.setString(1, session)
            it.setString(2, next.active)
            it.setString(3, next.previous)
            it.setString(4, next.pending)
            it.setString(5, next.run)
            it.executeUpdate()
        }
        append(db, session, next.run, revision, event)
    }

    fun event(
        session: String,
        run: String?,
        revision: String?,
        event: String,
    ) = transaction { append(it, session, run, revision, event) }

    private fun append(
        db: Connection,
        session: String,
        run: String?,
        revision: String?,
        event: String,
    ) {
        db.prepareStatement("INSERT INTO harness_events(session,run,revision,event,created) VALUES(?,?,?,?,?)").use {
            it.setString(1, session)
            it.setString(2, run)
            it.setString(3, revision)
            it.setString(4, event)
            it.setLong(5, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    /** Unsettled/uncertain requests retain their full reservation after restart. Amounts in micro-USD. */
    fun reserve(
        id: String,
        micros: Long,
    ): Boolean =
        transaction { db ->
            require(micros in 1..5_000_000)
            val used = db.createStatement().use { s ->
                s.executeQuery("SELECT COALESCE(SUM(CASE WHEN settled=1 THEN charged ELSE reserved END),0) FROM harness_budget").use {
                    it.next()
                    it.getLong(1)
                }
            }
            if (used + micros > 5_000_000) {
                false
            } else {
                db.prepareStatement("INSERT INTO harness_budget(id,reserved) VALUES(?,?)").use {
                    it.setString(1, id)
                    it.setLong(2, micros)
                    it.executeUpdate()
                }
                true
            }
        }

    fun settle(
        id: String,
        charged: Long,
    ) = transaction { db ->
        db.prepareStatement("SELECT reserved,charged,settled FROM harness_budget WHERE id=?").use { query ->
            query.setString(1, id)
            query.executeQuery().use { r ->
                require(r.next())
                require(charged in 0..r.getLong(1)) { "Charge exceeds reserved upper bound" }
                if (r.getBoolean(3)) {
                    require(charged == r.getLong(2))
                    return@transaction
                }
            }
        }
        db.prepareStatement("UPDATE harness_budget SET charged=?,settled=1 WHERE id=?").use {
            it.setLong(1, charged)
            it.setString(2, id)
            it.executeUpdate()
        }
    }
}
