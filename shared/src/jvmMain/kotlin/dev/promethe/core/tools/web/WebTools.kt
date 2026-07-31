package dev.promethe.core.tools.web

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.SecureJvmOutboundHttpClient
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── Args ─────────────────────────────────────────────────────────────

@Serializable
data class WebCrawlArgs(
    @property:LLMDescription("Starting URL to crawl.")
    val url: String,
    @property:LLMDescription("Maximum number of pages to follow. Default 5.")
    val maxPages: Int = 5,
    @property:LLMDescription("If true, only follow links on the same domain. Default true.")
    val sameDomain: Boolean = true,
)

@Serializable
data class RssFeedArgs(
    @property:LLMDescription("RSS or Atom feed URL.")
    val url: String,
    @property:LLMDescription("Maximum number of items to return. Default 10.")
    val maxItems: Int = 10,
)

@Serializable
data class WebScreenshotArgs(
    @property:LLMDescription("URL to screenshot.")
    val url: String,
    @property:LLMDescription("Output file path for the screenshot. Default 'screenshot.png'.")
    val output: String = "screenshot.png",
    @property:LLMDescription("Viewport width in pixels. Default 1280.")
    val width: Int = 1280,
)

// ── Tools ────────────────────────────────────────────────────────────

class WebCrawlTool(
    private val outboundClient: SecureJvmOutboundHttpClient,
) : SimpleTool<WebCrawlArgs>(
        argsType = typeToken<WebCrawlArgs>(),
        name = "web_crawl",
        description = "Crawl a website starting from a URL. Extracts text and discovers links.",
    ) {
    override suspend fun execute(args: WebCrawlArgs): String {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(args.url)
        val results = StringBuilder()
        val baseDomain = try {
            java.net.URI(args.url).host
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse base domain from URL: ${args.url}" }
            ""
        }

        while (queue.isNotEmpty() && visited.size < args.maxPages) {
            val currentUrl = queue.removeFirst()
            if (currentUrl in visited) continue
            visited.add(currentUrl)

            try {
                val response =
                    outboundClient.getFollowingRedirects(
                        url = currentUrl,
                        headers = WEB_DOCUMENT_HEADERS,
                    )
                if (response.status !in 200..299) {
                    results.appendLine("── $currentUrl ── [ERROR: HTTP ${response.status}]")
                    continue
                }
                val html = response.body
                val text = extractText(html)
                val links = extractLinks(html, currentUrl)
                results.appendLine("── $currentUrl ──")
                results.appendLine(text.take(2000))
                results.appendLine("Links found: ${links.size}")
                results.appendLine()

                links.filter { it !in visited }.forEach { link ->
                    if (!args.sameDomain || isSameDomain(link, baseDomain)) {
                        queue.add(link)
                    }
                }
            } catch (e: Exception) {
                results.appendLine("── $currentUrl ── [ERROR: ${e.message}]")
            }
        }
        return results.toString().ifBlank { "[ERROR] Could not crawl ${args.url}" }
    }

    private fun extractText(html: String): String =
        html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractLinks(
        html: String,
        baseUrl: String,
    ): List<String> {
        val pattern = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return pattern.findAll(html).map { it.groupValues[1] }
            .map { href ->
                when {
                    href.startsWith("http") -> {
                        href
                    }

                    href.startsWith("//") -> {
                        "https:$href"
                    }

                    href.startsWith("/") -> {
                        val uri = java.net.URI(baseUrl)
                        "${uri.scheme}://${uri.host}$href"
                    }

                    else -> {
                        "$baseUrl/$href"
                    }
                }
            }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
    }

    private fun isSameDomain(
        url: String,
        domain: String,
    ): Boolean =
        try {
            java.net.URI(url).host == domain
        } catch (e: Exception) {
            logger.debug(e) { "Failed to compare domain for URL: $url" }
            false
        }
}

class RssFeedTool(
    private val outboundClient: SecureJvmOutboundHttpClient,
) : SimpleTool<RssFeedArgs>(
        argsType = typeToken<RssFeedArgs>(),
        name = "rss_feed",
        description = "Parse RSS or Atom feeds and return structured items with title, link, and date.",
    ) {
    override suspend fun execute(args: RssFeedArgs): String =
        try {
            val response =
                outboundClient.getFollowingRedirects(
                    url = args.url,
                    headers = RSS_HEADERS,
                )
            if (response.status !in 200..299) {
                return "[ERROR] HTTP ${response.status} for ${args.url}"
            }
            val xml = response.body
            val items = parseItems(xml, args.maxItems)
            if (items.isEmpty()) {
                "[WARN] No items found in feed."
            } else {
                items.joinToString("\n\n") { (title, link, date) ->
                    "• $title\n  $link\n  $date"
                }
            }
        } catch (e: Exception) {
            "[ERROR] Feed fetch failed: ${e.message}"
        }

    private fun parseItems(
        xml: String,
        max: Int,
    ): List<Triple<String, String, String>> {
        val items = mutableListOf<Triple<String, String, String>>()
        // Simple regex parsing for RSS <item> and Atom <entry>
        val itemPattern = Regex(
            "<(?:item|entry)>([\\s\\S]*?)</(?:item|entry)>",
            RegexOption.IGNORE_CASE,
        )
        for (match in itemPattern.findAll(xml)) {
            if (items.size >= max) break
            val block = match.groupValues[1]
            val title = extractTag(block, "title")
            val link = extractTag(block, "link") ?: extractAttr(block, "link", "href")
            val date = extractTag(block, "pubDate")
                ?: extractTag(block, "published")
                ?: extractTag(block, "updated")
                ?: ""
            items.add(Triple(title ?: "(no title)", link ?: "(no link)", date))
        }
        return items
    }

    private fun extractTag(
        block: String,
        tag: String,
    ): String? {
        val pattern = Regex("<$tag[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</$tag>", RegexOption.IGNORE_CASE)
        return pattern.find(block)?.groupValues?.get(1)?.trim()
    }

    private fun extractAttr(
        block: String,
        tag: String,
        attr: String,
    ): String? {
        val pattern = Regex("""<$tag[^>]*$attr=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return pattern.find(block)?.groupValues?.get(1)
    }
}

private val WEB_DOCUMENT_HEADERS =
    mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Promethe/1.0)",
        "Accept" to "text/html,application/xhtml+xml",
    )

private val RSS_HEADERS =
    mapOf(
        "User-Agent" to "Promethe/1.0",
        "Accept" to "application/rss+xml,application/atom+xml,application/xml,text/xml",
    )

class WebScreenshotTool(
    private val workDir: String,
    /** Null is deliberately unavailable; the tool never falls back to a direct process launch. */
    private val commandRunner: SandboxedCommandRunner? = null,
) : SimpleTool<WebScreenshotArgs>(
        argsType = typeToken<WebScreenshotArgs>(),
        name = "web_screenshot",
        description = "Take a screenshot of a webpage using a headless browser (requires Chrome/Chromium).",
    ) {
    override suspend fun execute(args: WebScreenshotArgs): String {
        val runner =
            commandRunner
                ?: return "[SANDBOX UNAVAILABLE] Web screenshots require the native sandbox helper."
        val relativeOutput =
            normalizedRelativePath(args.output)
                ?: return "[ERROR] Screenshot output must be a relative path within the workspace."
        val outputPath = Path.of(workDir).resolve(relativeOutput).normalize()
        return try {
            val chromeCmd = findChrome(runner) ?: return "[ERROR] Chrome/Chromium not found in the sandbox."
            val result =
                runner.execute(
                    executable = chromeCmd,
                    arguments =
                        listOf(
                            "--headless",
                            "--disable-gpu",
                            "--screenshot=$relativeOutput",
                            "--window-size=${args.width.coerceIn(MIN_VIEWPORT_WIDTH, MAX_VIEWPORT_WIDTH)},900",
                            args.url,
                        ),
                    sessionId = "web-screenshot",
                    timeoutMillis = SCREENSHOT_TIMEOUT_MILLIS,
                )
            if (result.errorCode != null || result.exitCode != 0) {
                return "[ERROR] Screenshot failed: ${result.renderCommandOutput()}"
            }
            if (Files.exists(outputPath)) {
                "Screenshot saved to ${args.output}"
            } else {
                "[ERROR] Screenshot file not created."
            }
        } catch (e: Exception) {
            "[ERROR] Screenshot failed: ${e.message}"
        }
    }

    private suspend fun findChrome(runner: SandboxedCommandRunner): String? {
        val candidates = listOf(
            "google-chrome",
            "chromium-browser",
            "chromium",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium-browser",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        )
        for (c in candidates) {
            try {
                val result =
                    runner.execute(
                        executable = c,
                        arguments = listOf("--version"),
                        sessionId = "web-screenshot-probe",
                        timeoutMillis = BINARY_PROBE_TIMEOUT_MILLIS,
                    )
                if (result.errorCode == null && result.exitCode == 0) {
                    return c
                }
            } catch (e: Exception) {
                logger.debug(e) { "Chrome candidate not available: $c" }
                continue
            }
        }
        return null
    }

    private fun normalizedRelativePath(path: String): Path? {
        if (path.isBlank()) return null
        val normalized = Path.of(path).normalize()
        return normalized.takeIf { !it.isAbsolute && !it.startsWith("..") }
    }

    private companion object {
        const val BINARY_PROBE_TIMEOUT_MILLIS = 5_000L
        const val SCREENSHOT_TIMEOUT_MILLIS = 30_000L
        const val MIN_VIEWPORT_WIDTH = 320
        const val MAX_VIEWPORT_WIDTH = 7_680
    }
}
