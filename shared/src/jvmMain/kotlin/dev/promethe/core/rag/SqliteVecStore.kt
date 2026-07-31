package dev.promethe.core.rag

import java.sql.Connection
import java.sql.DriverManager

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SqliteVecStore — embedded vector store using SQLite with manual cosine similarity.
 *
 * Zero-dependency: uses standard JDBC SQLite driver (already in the project).
 * Stores embeddings as BLOB (serialized float arrays).
 *
 * For production scale, switch to Qdrant/Pinecone/Milvus.
 * This implementation is optimized for simplicity and small-to-medium datasets (< 100K vectors).
 */
class SqliteVecStore(
    private val dbPath: String = "vectors.db",
    private val tableName: String = "vector_chunks",
) : VectorStore,
    HybridSearchEngine.FtsSearcher {
    override val storeName = "sqlite-vec"

    private var connection: Connection? = null

    private fun getConnection(): Connection {
        if (connection == null || connection!!.isClosed) {
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            initSchema()
        }
        return connection!!
    }

    private fun initSchema() {
        val conn = connection ?: return
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    id TEXT PRIMARY KEY,
                    embedding BLOB NOT NULL,
                    content TEXT NOT NULL,
                    source TEXT DEFAULT '',
                    metadata TEXT DEFAULT '{}',
                    created_at INTEGER DEFAULT (strftime('%s','now'))
                )
                """.trimIndent(),
            )
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_${tableName}_source ON $tableName(source)",
            )

            // FTS5 virtual table for keyword search (hybrid RAG)
            stmt.executeUpdate(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS ${tableName}_fts USING fts5(
                    content,
                    source,
                    content=$tableName,
                    content_rowid=rowid,
                    tokenize='unicode61'
                )
                """.trimIndent(),
            )

            // Sync triggers: keep FTS5 in sync with base table
            stmt.executeUpdate(
                """
                CREATE TRIGGER IF NOT EXISTS ${tableName}_ai AFTER INSERT ON $tableName BEGIN
                    INSERT INTO ${tableName}_fts(rowid, content, source)
                    VALUES (new.rowid, new.content, new.source);
                END
                """.trimIndent(),
            )

            stmt.executeUpdate(
                """
                CREATE TRIGGER IF NOT EXISTS ${tableName}_ad AFTER DELETE ON $tableName BEGIN
                    INSERT INTO ${tableName}_fts(${tableName}_fts, rowid, content, source)
                    VALUES ('delete', old.rowid, old.content, old.source);
                END
                """.trimIndent(),
            )

            stmt.executeUpdate(
                """
                CREATE TRIGGER IF NOT EXISTS ${tableName}_au AFTER UPDATE ON $tableName BEGIN
                    INSERT INTO ${tableName}_fts(${tableName}_fts, rowid, content, source)
                    VALUES ('delete', old.rowid, old.content, old.source);
                    INSERT INTO ${tableName}_fts(rowid, content, source)
                    VALUES (new.rowid, new.content, new.source);
                END
                """.trimIndent(),
            )
        }
        logger.info { "SqliteVecStore initialized: $dbPath" }
    }

    override suspend fun upsert(
        id: String,
        embedding: FloatArray,
        content: String,
        metadata: Map<String, String>,
    ) {
        val conn = getConnection()
        val blob = serializeFloatArray(embedding)
        val source = metadata["source"] ?: ""
        val metaJson = metadata.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }

        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO $tableName (id, embedding, content, source, metadata)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.setBytes(2, blob)
            ps.setString(3, content)
            ps.setString(4, source)
            ps.setString(5, "{$metaJson}")
            ps.executeUpdate()
        }
    }

    override suspend fun upsertBatch(items: List<VectorItem>) {
        val conn = getConnection()
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO $tableName (id, embedding, content, source, metadata)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                for (item in items) {
                    val source = item.metadata["source"] ?: ""
                    val metaJson = item.metadata.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                    ps.setString(1, item.id)
                    ps.setBytes(2, serializeFloatArray(item.embedding))
                    ps.setString(3, item.content)
                    ps.setString(4, source)
                    ps.setString(5, "{$metaJson}")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        val conn = getConnection()

        // Build WHERE clause for filters
        val whereClause = if (filter.isNotEmpty()) {
            " WHERE " + filter.entries.joinToString(" AND ") { "${it.key} = ?" }
        } else {
            ""
        }

        val results = mutableListOf<ScoredResult>()

        conn.prepareStatement(
            "SELECT id, embedding, content, metadata FROM $tableName$whereClause",
        ).use { ps ->
            var paramIdx = 1
            for ((_, v) in filter) {
                ps.setString(paramIdx++, v)
            }

            val rs = ps.executeQuery()
            while (rs.next()) {
                val id = rs.getString("id")
                val embBlob = rs.getBytes("embedding")
                val content = rs.getString("content")
                val metaStr = rs.getString("metadata")

                val storedEmb = deserializeFloatArray(embBlob)
                val score = cosineSimilarity(queryEmbedding, storedEmb)

                val meta = parseSimpleJsonMap(metaStr)
                results.add(ScoredResult(id, content, score, meta))
            }
        }

        // Sort by score descending, take topK
        return results.sortedByDescending { it.score }.take(topK)
    }

    override suspend fun keywordSearch(
        query: String,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        val conn = getConnection()
        val results = mutableListOf<ScoredResult>()

        // Escape FTS5 special characters in query
        val safeQuery = query
            .replace("\"", "\"\"")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" OR ") { "\"$it\"" }

        if (safeQuery.isBlank()) return emptyList()

        val sql =
            """
            SELECT b.id, f.content, f.source, b.metadata, rank
            FROM ${tableName}_fts f
            JOIN $tableName b ON b.rowid = f.rowid
            WHERE ${tableName}_fts MATCH ?
            ORDER BY rank
            LIMIT ?
            """.trimIndent()

        try {
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, safeQuery)
                ps.setInt(2, topK)

                val rs = ps.executeQuery()
                while (rs.next()) {
                    val content = rs.getString("content")
                    val source = rs.getString("source")
                    val metaStr = rs.getString("metadata")
                    val bm25Rank = rs.getDouble("rank")

                    val meta = parseSimpleJsonMap(metaStr) + ("source" to source)
                    // FTS5 rank is negative (lower = better), convert to positive score
                    results.add(
                        ScoredResult(
                            id = rs.getString("id"),
                            content = content,
                            score = -bm25Rank, // Negate because FTS5 rank is negative
                            metadata = meta,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "FTS5 keyword search failed, returning empty" }
        }

        return results
    }

    override suspend fun delete(id: String) {
        val conn = getConnection()
        conn.prepareStatement("DELETE FROM $tableName WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    override suspend fun deleteBySource(source: String) {
        val conn = getConnection()
        conn.prepareStatement("DELETE FROM $tableName WHERE source = ?").use { ps ->
            ps.setString(1, source)
            ps.executeUpdate()
        }
    }

    override suspend fun count(): Int {
        val conn = getConnection()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getInt(1) else 0
        }
    }

    override suspend fun testConnection(): Boolean =
        try {
            getConnection()
            count()
            true
        } catch (e: Exception) {
            logger.debug(e) { "SqliteVecStore connection test failed" }
            false
        }

    override suspend fun close() {
        connection?.close()
        connection = null
    }

    // ── Serialization helpers ──

    companion object {
        fun serializeFloatArray(arr: FloatArray): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(arr.size * 4)
            buf.asFloatBuffer().put(arr)
            return buf.array()
        }

        fun deserializeFloatArray(bytes: ByteArray): FloatArray {
            val buf = java.nio.ByteBuffer.wrap(bytes)
            val floats = FloatArray(bytes.size / 4)
            buf.asFloatBuffer().get(floats)
            return floats
        }

        fun cosineSimilarity(
            a: FloatArray,
            b: FloatArray,
        ): Double {
            if (a.size != b.size) return 0.0
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = Math.sqrt(normA) * Math.sqrt(normB)
            return if (denom == 0.0) 0.0 else dot / denom
        }

        fun parseSimpleJsonMap(json: String): Map<String, String> {
            if (json.isBlank() || json == "{}") return emptyMap()
            return try {
                val inner = json.trim().removePrefix("{").removeSuffix("}")
                inner.split(",").associate { pair ->
                    val (k, v) = pair.split(":", limit = 2)
                    k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}
