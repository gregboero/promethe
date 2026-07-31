package dev.promethe.core.tools.web

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import dev.promethe.core.PrometheJson
import dev.promethe.core.config.ConfigProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ══════════════════════════════════════════════════════════════
// WebSearchTool — web_search parity with Hermes
//
// Supports multiple backends in priority order:
//  1. Tavily (TAVILY_API_KEY)
//  2. SearXNG (SEARXNG_URL — self-hosted, no key needed)
//  3. Fallback: DuckDuckGo HTML scrape (no API key)
// ══════════════════════════════════════════════════════════════

@Serializable
data class WebSearchArgs(
    @property:LLMDescription("The search query.")
    val query: String,
    @property:LLMDescription("Maximum number of results to return. Default 5.")
    val maxResults: Int = 5,
)

class WebSearchTool(
    private val httpClient: HttpClient,
) : SimpleTool<WebSearchArgs>(
        argsType = typeToken<WebSearchArgs>(),
        name = "web_search",
        description = "Search the web for a query. Returns titles, snippets, and URLs. Supports Tavily, SearXNG, or DuckDuckGo.",
    ) {
    private val json = PrometheJson
    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

    override suspend fun execute(args: WebSearchArgs): String {
        // Read keys lazily — ConfigProvider may not be initialized at construction time
        val tavilyKey = ConfigProvider.get().get("TAVILY_API_KEY", "")
        val searxng = ConfigProvider.get().get("SEARXNG_URL", "")
        val providerName = ConfigProvider.get()::class.simpleName
        logger.info { "web_search: provider=$providerName, tavilyKey=${if (tavilyKey.isNotBlank()) "${tavilyKey.take(8)}…" else "(empty)"}, searxng=${searxng.ifBlank { "(empty)" }}, query='${args.query}'" }
        return when {
            tavilyKey.isNotBlank() -> searchTavily(args, tavilyKey)
            searxng.isNotBlank() -> searchSearxng(args, searxng)
            else -> searchDuckDuckGo(args)
        }
    }

    private suspend fun searchTavily(
        args: WebSearchArgs,
        apiKey: String,
    ): String {
        return try {
            val response = httpClient.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"api_key":"$apiKey","query":"${args.query.replace(
                        "\"",
                        "\\\"",
                    )}","max_results":${args.maxResults},"include_answer":true}""",
                )
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val answer = body["answer"]?.jsonPrimitive?.content ?: ""
            val results = body["results"]?.jsonArray

            // If no results array or empty, return the AI answer or a clear message
            if (results == null || results.isEmpty()) {
                return if (answer.isNotBlank()) {
                    "**Summary**: $answer\n\n(No web page results — use the summary above to answer. Do NOT search again.)"
                } else {
                    "No web results found for '${args.query}'. This search has been attempted — do NOT call web_search again for this topic. Answer the user based on your general knowledge instead."
                }
            }

            val sb = StringBuilder()
            if (answer.isNotBlank()) {
                sb.appendLine("**Summary**: $answer")
                sb.appendLine()
            }
            results.take(args.maxResults).forEachIndexed { i, r ->
                val obj = r.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val url = obj["url"]?.jsonPrimitive?.content ?: ""
                val snippet = obj["content"]?.jsonPrimitive?.content ?: ""
                sb.appendLine("${i + 1}. **$title**")
                sb.appendLine("   $url")
                sb.appendLine("   $snippet")
                sb.appendLine()
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "[ERROR] Tavily search failed: ${e.message}"
        }
    }

    private suspend fun searchSearxng(
        args: WebSearchArgs,
        baseUrl: String,
    ): String {
        return try {
            val response = httpClient.get("$baseUrl/search") {
                parameter("q", args.query)
                parameter("format", "json")
                parameter("pageno", 1)
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val results = body["results"]?.jsonArray ?: return "[ERROR] No results"

            val sb = StringBuilder()
            results.take(args.maxResults).forEachIndexed { i, r ->
                val obj = r.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val url = obj["url"]?.jsonPrimitive?.content ?: ""
                val snippet = obj["content"]?.jsonPrimitive?.content ?: ""
                sb.appendLine("${i + 1}. **$title**")
                sb.appendLine("   $url")
                sb.appendLine("   $snippet")
                sb.appendLine()
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "[ERROR] SearXNG search failed: ${e.message}"
        }
    }

    private suspend fun searchDuckDuckGo(args: WebSearchArgs): String {
        return try {
            val response = httpClient.get("https://html.duckduckgo.com/html/") {
                parameter("q", args.query)
                header("User-Agent", "Mozilla/5.0 (compatible; Promethe/1.0)")
            }
            val html = response.bodyAsText()
            val resultPattern = Regex(
                """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
            val matches = resultPattern.findAll(html).take(args.maxResults).toList()
            if (matches.isEmpty()) return "[WARN] No results found for '${args.query}'"

            val sb = StringBuilder()
            matches.forEachIndexed { i, m ->
                val url = m.groupValues[1].replace(Regex(".*uddg="), "").let { java.net.URLDecoder.decode(it.split("&").first(), "UTF-8") }
                val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                val snippet = m.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
                sb.appendLine("${i + 1}. **$title**")
                sb.appendLine("   $url")
                sb.appendLine("   $snippet")
                sb.appendLine()
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "[ERROR] DuckDuckGo search failed: ${e.message}"
        }
    }
}
