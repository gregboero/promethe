package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Profile-owned aggregate starts, committed before external work. No provider or tool arguments. */
class PersistentResourceQuotaBank(
    path: Path,
    ownerId: String,
    rules: List<ResourceQuotaRule>,
    private val now: () -> Long = System::currentTimeMillis,
) : ResourceQuotaBank {
    private val rules = rules.toList()
    private val owner = hash(ownerId)
    private val url: String

    init {
        require(ownerId.isNotBlank())
        require(rules.size <= 128 && rules.map { it.id }.distinct().size == rules.size) { "At most 128 quota rules with distinct ids are allowed" }
        Files.createDirectories(path.toAbsolutePath().parent)
        url = "jdbc:sqlite:${path.toAbsolutePath()}"
        transaction { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS quota_clock(owner TEXT PRIMARY KEY, observed INTEGER NOT NULL)")
                it.execute("CREATE TABLE IF NOT EXISTS quota_starts(owner TEXT NOT NULL, rule_key TEXT NOT NULL, subject TEXT NOT NULL, window_start INTEGER NOT NULL, used INTEGER NOT NULL CHECK(used>=0), PRIMARY KEY(owner,rule_key,subject,window_start))")
            }
        }
    }

    override fun policies(): List<ResourceQuotaRule> = rules.toList()

    override suspend fun reserve(
        resource: GovernedResource,
        scope: ResourceQuotaScope,
    ): ResourceQuotaUsage? =
        withContext(Dispatchers.IO) {
            val matching = rules.filter { it.resource == resource }.mapNotNull { rule -> subject(rule, scope)?.let { rule to it } }
            if (matching.isEmpty()) return@withContext null
            transaction { db ->
                val timestamp = timestamp(db)
                db.prepareStatement("INSERT INTO quota_clock VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET observed=excluded.observed").use {
                    it.setString(1, owner)
                    it.setLong(2, timestamp)
                    it.executeUpdate()
                }
                val usages = matching.map { (rule, subject) -> usage(db, rule, subject, timestamp) }
                val denied = usages.firstOrNull { it.used >= it.maxStarts }
                if (denied != null) return@transaction denied
                for ((index, pair) in matching.withIndex()) {
                    val (rule, subject) = pair
                    db.prepareStatement("INSERT INTO quota_starts VALUES(?,?,?,?,1) ON CONFLICT(owner,rule_key,subject,window_start) DO UPDATE SET used=quota_starts.used+1").use {
                        it.setString(1, owner)
                        it.setString(2, key(rule))
                        it.setString(3, subject)
                        it.setLong(4, usages[index].windowStartedAt)
                        it.executeUpdate()
                    }
                }
                null
            }
        }

    override suspend fun snapshot(): List<ResourceQuotaUsage> =
        withContext(Dispatchers.IO) {
            transaction { db ->
                val timestamp = timestamp(db)
                rules.flatMap { rule ->
                    val subjects = if (rule.dimension == ResourceQuotaDimension.OWNER) {
                        listOf("local-profile")
                    } else if (rule.selector != "*") {
                        listOf(selector(rule))
                    } else {
                        db.prepareStatement("SELECT DISTINCT subject FROM quota_starts WHERE owner=? AND rule_key=? AND window_start=? ORDER BY subject").use {
                            it.setString(1, owner)
                            it.setString(2, key(rule))
                            it.setLong(3, windowStart(rule, timestamp))
                            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                        }
                    }
                    subjects.map { usage(db, rule, it, timestamp) }
                }
            }
        }

    private fun subject(
        rule: ResourceQuotaRule,
        scope: ResourceQuotaScope,
    ): String? {
        val value = when (rule.dimension) {
            ResourceQuotaDimension.OWNER -> "local-profile"
            ResourceQuotaDimension.PROVIDER -> requireNotNull(scope.provider?.trim()?.lowercase()?.takeIf { it.isNotBlank() }) { "Actual provider required for aggregate admission" }
            ResourceQuotaDimension.TOOL -> requireNotNull(scope.toolName?.takeIf { it.isNotBlank() }) { "Actual tool name required for aggregate admission" }
        }
        require(value.length <= 256) { "Quota subject is too long" }
        return value.takeIf { rule.selector == "*" || it == selector(rule) }
    }

    private fun selector(rule: ResourceQuotaRule) = if (rule.dimension == ResourceQuotaDimension.PROVIDER) rule.selector.lowercase() else rule.selector

    // Limits can be tightened without clearing consumption; changing a policy's identity/window is explicit.
    private fun key(rule: ResourceQuotaRule) = hash(Json.encodeToString(rule.copy(maxStarts = 0, selector = selector(rule))))

    private fun windowStart(
        rule: ResourceQuotaRule,
        timestamp: Long,
    ): Long {
        val window = rule.windowSeconds * 1_000
        return timestamp / window * window
    }

    private fun timestamp(db: Connection): Long {
        val persisted = db.prepareStatement("SELECT observed FROM quota_clock WHERE owner=?").use {
            it.setString(1, owner)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
        }
        return maxOf(persisted, now().coerceIn(0, Long.MAX_VALUE - 31_536_000_000))
    }

    private fun usage(
        db: Connection,
        rule: ResourceQuotaRule,
        subject: String,
        timestamp: Long,
    ): ResourceQuotaUsage {
        val start = windowStart(rule, timestamp)
        val used = db.prepareStatement("SELECT used FROM quota_starts WHERE owner=? AND rule_key=? AND subject=? AND window_start=?").use {
            it.setString(1, owner)
            it.setString(2, key(rule))
            it.setString(3, subject)
            it.setLong(4, start)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
        }
        return ResourceQuotaUsage(rule.id, rule.dimension, rule.resource, subject, used, rule.maxStarts, start, start + rule.windowSeconds * 1_000)
    }

    private fun <T> transaction(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use { db ->
            db.createStatement().use {
                it.execute("PRAGMA busy_timeout=10000")
                it.execute("BEGIN IMMEDIATE")
            }
            try {
                val result = block(db)
                db.createStatement().use { it.execute("COMMIT") }
                result
            } catch (error: Throwable) {
                runCatching { db.createStatement().use { it.execute("ROLLBACK") } }
                throw error
            }
        }

    companion object {
        fun parseRules(value: String): List<ResourceQuotaRule> {
            require(value.length <= 64 * 1024) { "Quota configuration is too large" }
            val rules = Json.decodeFromString<List<ResourceQuotaRule>>(value)
            require(rules.size <= 128 && rules.map { it.id }.distinct().size == rules.size)
            return rules
        }

        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
