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
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import dev.promethe.core.PrometheJson
import dev.promethe.core.config.ConfigProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class XSearchArgs(
    @property:LLMDescription("Search query (supports Twitter operators like from:, #hashtag).")
    val query: String,
    @property:LLMDescription("Maximum results (10-100). Default 10.")
    val maxResults: Int = 10,
    @property:LLMDescription("Sort: 'recency' or 'relevancy'. Default 'recency'.")
    val sortOrder: String = "recency",
)

class XSearchTool(
    private val httpClient: HttpClient,
) : SimpleTool<XSearchArgs>(
        argsType = typeToken<XSearchArgs>(),
        name = "x_search",
        description = "Search X (Twitter) for recent posts matching a query.",
    ) {
    private val json = PrometheJson

    override suspend fun execute(args: XSearchArgs): String {
        val bearerToken = ConfigProvider.get().get("TWITTER_BEARER_TOKEN", "")
        return if (bearerToken.isNotBlank()) {
            searchViaApi(args, bearerToken)
        } else {
            searchViaWebFallback(args)
        }
    }

    private suspend fun searchViaApi(
        args: XSearchArgs,
        bearerToken: String,
    ): String {
        return try {
            val response = httpClient.get("https://api.twitter.com/2/tweets/search/recent") {
                parameter("query", args.query)
                parameter("max_results", args.maxResults.coerceIn(10, 100))
                parameter("sort_order", args.sortOrder)
                parameter("tweet.fields", "created_at,author_id,public_metrics,lang")
                parameter("expansions", "author_id")
                parameter("user.fields", "username,name")
                header("Authorization", "Bearer $bearerToken")
            }
            if (!response.status.isSuccess()) {
                return "[ERROR] X API: HTTP ${response.status.value}"
            }
            response.bodyAsText()
        } catch (e: Exception) {
            "[ERROR] X API request failed: ${e.message}"
        }
    }

    private suspend fun searchViaWebFallback(args: XSearchArgs): String {
        val tavilyKey = ConfigProvider.get().get("TAVILY_API_KEY", "")
        if (tavilyKey.isBlank()) {
            return "[ERROR] No TWITTER_BEARER_TOKEN or TAVILY_API_KEY configured. Set one for x_search."
        }
        return try {
            val searchQuery = "site:x.com OR site:twitter.com ${args.query}"
            val response = httpClient.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("api_key", tavilyKey)
                        put("query", searchQuery)
                        put("max_results", args.maxResults.coerceAtMost(10))
                    }.toString(),
                )
            }
            response.bodyAsText()
        } catch (e: Exception) {
            "[ERROR] Fallback search failed: ${e.message}"
        }
    }
}
