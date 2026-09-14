package dev.promethe.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HarnessAdaptationEvidence(
    val suite: String,
    val passed: Boolean,
    val cases: Int,
    val baselineBytes: Long,
    val presentedBytes: Long,
    val coldMillis: Long,
    val warmMillis: Long,
    val evaluationMillis: Long,
) {
    val useful: Boolean get() = passed && baselineBytes > 0 && presentedBytes < baselineBytes * 3 / 4
}

@Serializable
data class HarnessLibraryEntry(
    val id: String,
    val scope: String,
    val contract: String,
    val toolName: String,
    val source: String,
    val hash: String,
    val compatibility: String,
    val evidence: HarnessAdaptationEvidence,
    val createdAt: Long,
    val active: Boolean = true,
    val reason: String = "evaluated",
)

/** Profile-local immutable versions. Only evaluation can publish; invalidation never erases evidence. */
class HarnessAdaptationLibrary(
    private val store: HarnessStore,
) {
    init {
        store.transaction { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS harness_library(id TEXT PRIMARY KEY, scope TEXT NOT NULL, body TEXT NOT NULL, active INTEGER NOT NULL, reason TEXT NOT NULL)")
            }
        }
    }

    fun entries(scope: String): List<HarnessLibraryEntry> =
        store.transaction { db ->
            db.prepareStatement("SELECT body,active,reason FROM harness_library WHERE scope=? ORDER BY rowid DESC LIMIT 256").use {
                it.setString(1, scope)
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(Json.decodeFromString<HarnessLibraryEntry>(rows.getString(1)).copy(active = rows.getBoolean(2), reason = rows.getString(3)))
                    }
                }
            }
        }

    fun publish(
        scope: String,
        revision: HarnessRevision,
        compatibility: String,
        evidence: HarnessAdaptationEvidence,
    ): HarnessLibraryEntry {
        require(revision.language == HarnessLanguage.KOTLIN && evidence.useful)
        require(revision.hash == SessionHarness.sha256(revision.source))
        val entry = HarnessLibraryEntry(UUID.randomUUID().toString(), scope, HarnessAdaptationEvaluator.CONTRACT, revision.toolName, revision.source, revision.hash, compatibility, evidence, System.currentTimeMillis())
        store.transaction { db ->
            db.prepareStatement("SELECT COUNT(*) FROM harness_library WHERE scope=?").use {
                it.setString(1, scope)
                it.executeQuery().use { rows ->
                    rows.next()
                    require(rows.getInt(1) < 256) { "LAB library capacity reached; preserve evidence and use original observations" }
                }
            }
            db.prepareStatement("INSERT INTO harness_library VALUES(?,?,?,?,?)").use {
                it.setString(1, entry.id)
                it.setString(2, scope)
                it.setString(3, Json.encodeToString(entry))
                it.setBoolean(4, true)
                it.setString(5, entry.reason)
                it.executeUpdate()
            }
        }
        return entry
    }

    fun invalidate(
        scope: String,
        id: String,
        reason: String,
    ) = store.transaction { db ->
        db.prepareStatement("UPDATE harness_library SET active=0,reason=? WHERE id=? AND scope=?").use {
            it.setString(1, reason.take(256))
            it.setString(2, id)
            it.setString(3, scope)
            require(it.executeUpdate() == 1) { "Unknown scoped library entry" }
        }
    }
}
