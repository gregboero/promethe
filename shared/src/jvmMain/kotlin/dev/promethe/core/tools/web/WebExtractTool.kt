package dev.promethe.core.tools.web

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.SecureJvmOutboundHttpClient
import kotlinx.serialization.Serializable

@Serializable
data class WebExtractArgs(
    @property:LLMDescription("The URL to extract content from.")
    val url: String,
    @property:LLMDescription("Output format: 'text', 'markdown', 'structured'. Default: 'markdown'.")
    val format: String = "markdown",
)

class WebExtractTool(
    private val outboundClient: SecureJvmOutboundHttpClient,
) : SimpleTool<WebExtractArgs>(
        argsType = typeToken<WebExtractArgs>(),
        name = "web_extract",
        description = "Extract structured content (text, links, headings, metadata) from a web page URL.",
    ) {
    override suspend fun execute(args: WebExtractArgs): String {
        return try {
            val response =
                outboundClient.getFollowingRedirects(
                    url = args.url,
                    headers =
                        mapOf(
                            "User-Agent" to "Mozilla/5.0 (compatible; Promethe/1.0)",
                            "Accept" to "text/html,application/xhtml+xml",
                        ),
                )
            if (response.status !in 200..299) {
                return "[ERROR] HTTP ${response.status} for ${args.url}"
            }
            val html = response.body
            when (args.format) {
                "text" -> extractPlainText(html)
                "structured" -> extractStructured(html, args.url)
                else -> extractMarkdown(html)
            }
        } catch (e: Exception) {
            "[ERROR] web_extract failed: ${e.message}"
        }
    }

    private fun extractPlainText(html: String): String =
        html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().take(50_000)

    private fun extractMarkdown(html: String): String {
        var md = html
        md = md.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        md = md.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        for (i in 6 downTo 1) {
            md = md.replace(Regex("<h$i[^>]*>(.*?)</h$i>", RegexOption.IGNORE_CASE), "\n${"#".repeat(i)} $1\n")
        }
        md = md.replace(Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE), "[$2]($1)")
        md = md.replace(Regex("<(strong|b)>(.*?)</(strong|b)>", RegexOption.IGNORE_CASE), "**$2**")
        md = md.replace(Regex("<(em|i)>(.*?)</(em|i)>", RegexOption.IGNORE_CASE), "*$2*")
        md = md.replace(Regex("<li[^>]*>(.*?)</li>", RegexOption.IGNORE_CASE), "- $1\n")
        md = md.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        md = md.replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
        md = md.replace(Regex("</p>", RegexOption.IGNORE_CASE), "")
        md = md.replace(Regex("<[^>]+>"), "")
        md = md.replace(Regex("\n{3,}"), "\n\n").trim()
        return md.take(50_000)
    }

    private fun extractStructured(
        html: String,
        url: String,
    ): String {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: ""
        val desc = Regex("<meta[^>]+name=\"description\"[^>]+content=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: ""
        val headings = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.take(20).toList()
        val text = extractPlainText(html).take(10_000)
        return buildString {
            appendLine("URL: $url")
            appendLine("Title: $title")
            appendLine("Description: $desc")
            appendLine("Headings: ${headings.joinToString(", ")}")
            appendLine("---")
            append(text)
        }
    }
}
