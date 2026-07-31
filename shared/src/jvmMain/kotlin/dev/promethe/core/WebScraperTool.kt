package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
//  Web Scraper Tool — Smart content extraction from web pages
//  Beyond simple HTTP fetch: cleans HTML, extracts text,
//  finds links, metadata, and structured content.
// ══════════════════════════════════════════════════════════════

@Serializable
data class WebScraperArgs(
    @property:LLMDescription("URL of the web page to scrape.")
    val url: String,
    @property:LLMDescription(
        "What to extract: 'text' (main content), 'links' (all links), 'metadata' (title/description/og), 'full' (everything), 'raw' (raw HTML)",
    )
    val extract: String = "text",
    @property:LLMDescription("CSS-like selector hint for content extraction (e.g., 'article', 'main', '.content').")
    val selector: String = "",
    @property:LLMDescription("Maximum content length to return (chars).")
    val maxLength: Int = 10000,
)

class WebScraperTool(
    private val outboundHttpClient: SecureJvmOutboundHttpClient,
) : SimpleTool<WebScraperArgs>(
        argsType = typeToken<WebScraperArgs>(),
        name = "web_scrape",
        description = "Scrape and extract structured content from web pages: text, links, metadata, or raw HTML.",
    ) {
    override suspend fun execute(args: WebScraperArgs): String {
        return try {
            val response =
                outboundHttpClient.getFollowingRedirects(
                    url = args.url,
                    headers =
                        mapOf(
                            "User-Agent" to "Mozilla/5.0 (compatible; PrometheBot/1.0)",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        ),
                )

            if (response.status !in 200..299) {
                return "[ERROR] HTTP ${response.status}"
            }

            val html = response.body

            when (args.extract.lowercase()) {
                "raw" -> {
                    html.take(args.maxLength)
                }

                "text" -> {
                    extractText(html, args.selector).take(args.maxLength)
                }

                "links" -> {
                    extractLinks(html, args.url).take(args.maxLength)
                }

                "metadata" -> {
                    extractMetadata(html)
                }

                "full" -> {
                    buildString {
                        appendLine("=== METADATA ===")
                        appendLine(extractMetadata(html))
                        appendLine("\n=== CONTENT ===")
                        appendLine(extractText(html, args.selector))
                        appendLine("\n=== LINKS (top 20) ===")
                        appendLine(extractLinks(html, args.url))
                    }.take(args.maxLength)
                }

                else -> {
                    extractText(html, args.selector).take(args.maxLength)
                }
            }
        } catch (e: Exception) {
            "[ERROR] Web scraping failed: ${e.message}"
        }
    }

    /**
     * Extract readable text content from HTML.
     * Strips tags, scripts, styles, and normalizes whitespace.
     */
    private fun extractText(
        html: String,
        selectorHint: String,
    ): String {
        var content = html

        // If a selector hint is provided, try to find that section
        if (selectorHint.isNotBlank()) {
            val tagName = selectorHint.replace(".", "").replace("#", "")
            // Try to find content between matching tags
            val patterns =
                listOf(
                    "<$tagName[^>]*>(.*?)</$tagName>",
                    "<[^>]*class=\"[^\"]*${selectorHint.removePrefix(".")}[^\"]*\"[^>]*>(.*?)</",
                    "<[^>]*id=\"${selectorHint.removePrefix("#")}\"[^>]*>(.*?)</",
                )
            for (pattern in patterns) {
                val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(content)
                if (match != null && match.groupValues[1].length > 100) {
                    content = match.groupValues[1]
                    break
                }
            }
        }

        // Remove script and style blocks
        content = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        content = content.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        content = content.replace(Regex("<!--[\\s\\S]*?-->"), "")

        // Remove nav, header, footer, aside
        for (tag in listOf("nav", "header", "footer", "aside")) {
            content = content.replace(Regex("<$tag[^>]*>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE), "")
        }

        // Convert common elements to text
        content = content.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        content = content.replace(Regex("</(p|div|h[1-6]|li|tr)>", RegexOption.IGNORE_CASE), "\n")
        content = content.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")

        // Strip all remaining HTML tags
        content = content.replace(Regex("<[^>]+>"), "")

        // Decode HTML entities
        content =
            content
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")

        // Normalize whitespace
        content = content.replace(Regex("[ \\t]+"), " ")
        content = content.replace(Regex("\\n{3,}"), "\n\n")

        return content.trim()
    }

    /**
     * Extract all links from HTML.
     */
    private fun extractLinks(
        html: String,
        baseUrl: String,
    ): String {
        val linkRegex = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val links =
            linkRegex
                .findAll(html)
                .take(20)
                .map { match ->
                    val href = match.groupValues[1]
                    val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    val fullUrl =
                        if (href.startsWith("http")) {
                            href
                        } else if (href.startsWith("/")) {
                            val base = baseUrl.replace(Regex("(https?://[^/]+).*"), "$1")
                            "$base$href"
                        } else {
                            href
                        }
                    "[$text]($fullUrl)"
                }.toList()

        return links.joinToString("\n").ifBlank { "No links found." }
    }

    /**
     * Extract page metadata: title, description, Open Graph tags.
     */
    private fun extractMetadata(html: String): String {
        val title =
            Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: "(no title)"

        val description =
            Regex("<meta[^>]*name=\"description\"[^>]*content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: Regex("<meta[^>]*content=\"([^\"]+)\"[^>]*name=\"description\"", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                ?: "(no description)"

        val ogTitle = extractMeta(html, "og:title")
        val ogDesc = extractMeta(html, "og:description")
        val ogImage = extractMeta(html, "og:image")
        val ogUrl = extractMeta(html, "og:url")
        val canonical =
            Regex("<link[^>]*rel=\"canonical\"[^>]*href=\"([^\"]+)\"")
                .find(html)
                ?.groupValues
                ?.get(1) ?: ""

        return buildString {
            appendLine("Title: $title")
            appendLine("Description: $description")
            if (ogTitle.isNotBlank()) appendLine("OG Title: $ogTitle")
            if (ogDesc.isNotBlank()) appendLine("OG Description: $ogDesc")
            if (ogImage.isNotBlank()) appendLine("OG Image: $ogImage")
            if (ogUrl.isNotBlank()) appendLine("OG URL: $ogUrl")
            if (canonical.isNotBlank()) appendLine("Canonical: $canonical")
        }
    }

    private fun extractMeta(
        html: String,
        property: String,
    ): String =
        Regex("<meta[^>]*property=\"$property\"[^>]*content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1) ?: ""
}
