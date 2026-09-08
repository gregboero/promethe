package dev.promethe.core

import java.sql.DriverManager
import kotlinx.serialization.json.*

/** SQLite JSON1 supplies a second decoder; never persist arbitrary values or error bodies. */
internal object CampaignWireAudit {
    fun inspect(body: String): JsonObject =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.prepareStatement("SELECT json_valid(?)").use { statement ->
                statement.setString(1, body)
                statement.executeQuery().use { rows -> check(rows.next() && rows.getInt(1) == 1) }
            }
            db.prepareStatement("SELECT json_type(?, '$.choices[0].message.content'), length(json_extract(?, '$.choices[0].message.content'))").use { statement ->
                statement.setString(1, body)
                statement.setString(2, body)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    val type = rows.getString(1)
                    val length = rows.getInt(2).let { if (rows.wasNull()) null else it }
                    buildJsonObject {
                        put("bodySha256", SessionHarness.sha256(body))
                        put("decoder", "SQLite JSON1")
                        put("contentType", type)
                        put("contentCodePoints", length?.let(::JsonPrimitive) ?: JsonNull)
                        put("emptyText", type == "text" && length == 0)
                    }
                }
            }
        }
}
