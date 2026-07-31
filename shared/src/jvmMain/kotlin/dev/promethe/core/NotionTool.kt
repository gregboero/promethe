package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════
//  Notion Integration Tool
//  Query databases, create/update pages, search content.
//  Requires NOTION_API_KEY (internal integration token).
// ══════════════════════════════════════════════════════════════

@Serializable
data class NotionArgs(
    @property:LLMDescription("Action: 'search', 'get_page', 'create_page', 'query_database', 'get_database', 'append_blocks'")
    val action: String,
    @property:LLMDescription("Search query text.")
    val query: String = "",
    @property:LLMDescription("Page or database ID.")
    val id: String = "",
    @property:LLMDescription("Parent page or database ID for creating pages.")
    val parentId: String = "",
    @property:LLMDescription("Page title.")
    val title: String = "",
    @property:LLMDescription("Content to add (plain text, converted to paragraph blocks).")
    val content: String = "",
    @property:LLMDescription("Filter type for search: 'page' or 'database'.")
    val filterType: String = "",
)

class NotionTool(
    private val httpClient: HttpClient,
    private val apiKey: String,
) : SimpleTool<NotionArgs>(
        argsType = typeToken<NotionArgs>(),
        name = "notion",
        description = "Interact with Notion: search, get/create pages, query databases, append content.",
    ) {
    private val json = PrometheJson
    private val baseUrl = "https://api.notion.com/v1"
    private val notionVersion = "2022-06-28"

    override suspend fun execute(args: NotionArgs): String {
        if (apiKey.isBlank()) return "[ERROR] Notion API key not configured (set NOTION_API_KEY)"

        return try {
            when (args.action.lowercase()) {
                "search" -> search(args.query, args.filterType)
                "get_page" -> getPage(args.id)
                "create_page" -> createPage(args.parentId, args.title, args.content)
                "query_database" -> queryDatabase(args.id, args.query)
                "get_database" -> getDatabase(args.id)
                "append_blocks" -> appendBlocks(args.id, args.content)
                else -> "[ERROR] Unknown Notion action: ${args.action}"
            }
        } catch (e: Exception) {
            "[ERROR] Notion API call failed: ${e.message}"
        }
    }

    private suspend fun notionGet(path: String): String {
        val response =
            httpClient.get("$baseUrl$path") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionVersion)
                timeout { requestTimeoutMillis = 15_000 }
            }
        return response.bodyAsText()
    }

    private suspend fun notionPost(
        path: String,
        body: JsonObject,
    ): String {
        val response =
            httpClient.post("$baseUrl$path") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionVersion)
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(body.toString())
            }
        return response.bodyAsText()
    }

    private suspend fun notionPatch(
        path: String,
        body: JsonObject,
    ): String {
        val response =
            httpClient.patch("$baseUrl$path") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionVersion)
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(body.toString())
            }
        return response.bodyAsText()
    }

    private suspend fun search(
        query: String,
        filterType: String,
    ): String {
        val body =
            buildJsonObject {
                if (query.isNotBlank()) put("query", query)
                if (filterType.isNotBlank()) {
                    putJsonObject("filter") {
                        put("property", "object")
                        put("value", filterType)
                    }
                }
                put("page_size", 10)
            }
        val raw = notionPost("/search", body)
        val results = json.parseToJsonElement(raw).jsonObject["results"]?.jsonArray ?: return "No results."

        return results
            .joinToString("\n") { el ->
                val obj = el.jsonObject
                val type = obj["object"]?.jsonPrimitive?.content ?: "?"
                val title = extractTitle(obj)
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                "[$type] $title (id: $id)"
            }.ifBlank { "No results found." }
    }

    private suspend fun getPage(id: String): String {
        if (id.isBlank()) return "[ERROR] Page ID required"
        val raw = notionGet("/pages/$id")
        val obj = json.parseToJsonElement(raw).jsonObject
        return buildString {
            appendLine("Page: ${extractTitle(obj)}")
            appendLine("ID: ${obj["id"]?.jsonPrimitive?.content}")
            appendLine("URL: ${obj["url"]?.jsonPrimitive?.content}")
            appendLine("Created: ${obj["created_time"]?.jsonPrimitive?.content}")
            appendLine("Last edited: ${obj["last_edited_time"]?.jsonPrimitive?.content}")
        }
    }

    private suspend fun createPage(
        parentId: String,
        title: String,
        content: String,
    ): String {
        if (parentId.isBlank()) return "[ERROR] Parent ID required"
        val body =
            buildJsonObject {
                putJsonObject("parent") { put("page_id", parentId) }
                putJsonObject("properties") {
                    putJsonObject("title") {
                        putJsonArray("title") {
                            addJsonObject {
                                putJsonObject("text") { put("content", title) }
                            }
                        }
                    }
                }
                if (content.isNotBlank()) {
                    putJsonArray("children") {
                        // Split content into paragraph blocks
                        content.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
                            addJsonObject {
                                put("object", "block")
                                put("type", "paragraph")
                                putJsonObject("paragraph") {
                                    putJsonArray("rich_text") {
                                        addJsonObject {
                                            put("type", "text")
                                            putJsonObject("text") { put("content", paragraph) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        val raw = notionPost("/pages", body)
        val obj = json.parseToJsonElement(raw).jsonObject
        return "Page created: ${obj["url"]?.jsonPrimitive?.content} (id: ${obj["id"]?.jsonPrimitive?.content})"
    }

    private suspend fun queryDatabase(
        id: String,
        query: String,
    ): String {
        if (id.isBlank()) return "[ERROR] Database ID required"
        val body = buildJsonObject { put("page_size", 20) }
        val raw = notionPost("/databases/$id/query", body)
        val results = json.parseToJsonElement(raw).jsonObject["results"]?.jsonArray ?: return "No results."

        return results
            .joinToString("\n") { el ->
                val obj = el.jsonObject
                "${extractTitle(obj)} (id: ${obj["id"]?.jsonPrimitive?.content})"
            }.ifBlank { "Database is empty." }
    }

    private suspend fun getDatabase(id: String): String {
        if (id.isBlank()) return "[ERROR] Database ID required"
        val raw = notionGet("/databases/$id")
        val obj = json.parseToJsonElement(raw).jsonObject
        val properties = obj["properties"]?.jsonObject
        return buildString {
            appendLine("Database: ${extractTitle(obj)}")
            appendLine("ID: ${obj["id"]?.jsonPrimitive?.content}")
            appendLine("Properties:")
            properties?.forEach { (name, prop) ->
                appendLine("  - $name: ${prop.jsonObject["type"]?.jsonPrimitive?.content}")
            }
        }
    }

    private suspend fun appendBlocks(
        pageId: String,
        content: String,
    ): String {
        if (pageId.isBlank()) return "[ERROR] Page ID required"
        val body =
            buildJsonObject {
                putJsonArray("children") {
                    content.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
                        addJsonObject {
                            put("object", "block")
                            put("type", "paragraph")
                            putJsonObject("paragraph") {
                                putJsonArray("rich_text") {
                                    addJsonObject {
                                        put("type", "text")
                                        putJsonObject("text") { put("content", paragraph) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        notionPatch("/blocks/$pageId/children", body)
        return "Content appended to page $pageId"
    }

    private fun extractTitle(obj: JsonObject): String {
        val props = obj["properties"]?.jsonObject ?: return "(untitled)"
        // Try "title" property first, then "Name"
        val titleProp = props["title"] ?: props["Name"] ?: props.values.firstOrNull()
        val titleArray = titleProp?.jsonObject?.get("title")?.jsonArray
        return titleArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("plain_text")
            ?.jsonPrimitive
            ?.content
            ?: titleProp
                ?.jsonObject
                ?.get("title")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
            ?: "(untitled)"
    }
}
