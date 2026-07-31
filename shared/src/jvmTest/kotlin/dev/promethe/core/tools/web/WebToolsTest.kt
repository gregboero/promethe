package dev.promethe.core.tools.web

import dev.promethe.core.JvmOutboundUrlPolicy
import dev.promethe.core.OutboundHttpResponse
import dev.promethe.core.PinnedJvmOutboundHttpFetcher
import dev.promethe.core.SecureJvmOutboundHttpClient
import dev.promethe.core.browser.BrowserBackend
import dev.promethe.core.browser.BrowserResult
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.config.MergedConfigProvider
import dev.promethe.core.tools.builtin.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.*

class WebToolsTest {
    private fun createMockHttpClient(responseProvider: (String) -> String): HttpClient =
        HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                val responseText = responseProvider(url)
                respond(
                    content = responseText,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) {
                json()
            }
        }

    private fun createSecureClient(responseProvider: (String) -> OutboundHttpResponse): SecureJvmOutboundHttpClient =
        object : SecureJvmOutboundHttpClient {
            override suspend fun get(url: String): OutboundHttpResponse = responseProvider(url)

            override suspend fun getFollowingRedirects(
                url: String,
                headers: Map<String, String>,
                maxRedirects: Int,
            ): OutboundHttpResponse = responseProvider(url)

            override suspend fun postJsonFollowingRedirects(
                url: String,
                headers: Map<String, String>,
                body: String,
                maxRedirects: Int,
            ): OutboundHttpResponse = error("POST not expected")
        }

    @Test
    fun testWebSearchTavily() =
        runTest {
            // Initialize ConfigProvider with Tavily key for this test
            ConfigProvider.initialize(MergedConfigProvider { mapOf("TAVILY_API_KEY" to "test-key") })
            val client = createMockHttpClient { url ->
                if (url.contains("tavily.com")) {
                    """
                    {
                      "answer": "Answer from Tavily",
                      "results": [
                        {
                          "title": "Example Title",
                          "url": "https://example.com",
                          "content": "Example content snippet"
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }

            val tool = WebSearchTool(client)
            val result = tool.execute(WebSearchArgs(query = "kotlin", maxResults = 1))
            assertTrue(result.contains("Answer from Tavily"), "Result should contain Tavily answer summary")
            assertTrue(result.contains("Example Title"), "Result should contain result title")
            assertTrue(result.contains("https://example.com"), "Result should contain URL")
        }

    @Test
    fun testWebSearchSearxng() =
        runTest {
            // Initialize ConfigProvider with SearXNG URL (no Tavily key)
            ConfigProvider.initialize(MergedConfigProvider { mapOf("SEARXNG_URL" to "http://localhost:8080") })
            val client = createMockHttpClient { url ->
                if (url.contains("localhost:8080/search")) {
                    """
                    {
                      "results": [
                        {
                          "title": "Searxng Title",
                          "url": "https://searx.com",
                          "content": "Searxng snippet"
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }

            val tool = WebSearchTool(client)
            val result = tool.execute(WebSearchArgs(query = "kotlin", maxResults = 1))
            assertTrue(result.contains("Searxng Title"), "Result should contain Searxng title")
            assertTrue(result.contains("https://searx.com"), "Result should contain Searxng URL")
        }

    @Test
    fun testWebSearchDuckDuckGoFallback() =
        runTest {
            val client = HttpClient(
                MockEngine { _ ->
                    respond(
                        content =
                            """
                            <html>
                              <body>
                                <a class="result__a" href="https://ddg.com/kotlin">Kotlin Programming</a>
                                <a class="result__snippet" href="#">A modern programming language...</a>
                              </body>
                            </html>
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                },
            )

            // Initialize ConfigProvider with no keys → DuckDuckGo fallback
            ConfigProvider.initialize(MergedConfigProvider { emptyMap() })
            val tool = WebSearchTool(client)
            val result = tool.execute(WebSearchArgs(query = "kotlin", maxResults = 1))
            assertTrue(result.contains("Kotlin Programming"), "Result should parse DDG title")
            assertTrue(result.contains("https://ddg.com/kotlin"), "Result should parse DDG URL")
        }

    @Test
    fun testWebCrawl() =
        runTest {
            val client =
                createSecureClient { url ->
                    val content =
                        when (url) {
                            "https://start.com" -> {
                                "<html><body><a href=\"/page2\">Next</a><p>Start page content</p></body></html>"
                            }

                            "https://start.com/page2" -> {
                                "<html><body><p>Page 2 content</p></body></html>"
                            }

                            else -> {
                                "<html><body>Not Found</body></html>"
                            }
                        }
                    OutboundHttpResponse(
                        status = 200,
                        location = null,
                        body = content,
                    )
                }

            val tool = WebCrawlTool(client)
            val result = tool.execute(WebCrawlArgs(url = "https://start.com", maxPages = 2))
            assertTrue(result.contains("Start page content"), "Result should contain page 1 text")
            assertTrue(result.contains("Page 2 content"), "Result should contain page 2 text")
        }

    @Test
    fun testWebExtract() =
        runTest {
            val client =
                createSecureClient {
                    OutboundHttpResponse(
                        status = 200,
                        location = null,
                        body = "<html><head><title>Test Title</title>" +
                            "<meta name=\"description\" content=\"Test Desc\"/>" +
                            "</head><body><h1>Heading 1</h1><p>Content text here</p" +
                            "><a href=\"/link\">Link text</a></body></html>",
                    )
                }

            val tool = WebExtractTool(client)

            // Markdown format
            val mdResult = tool.execute(WebExtractArgs(url = "https://extract.com", format = "markdown"))
            assertTrue(mdResult.contains("# Heading 1"), "Markdown should convert headers")
            assertTrue(mdResult.contains("[Link text](/link)"), "Markdown should convert links")

            // Text format
            val textResult = tool.execute(WebExtractArgs(url = "https://extract.com", format = "text"))
            assertFalse(textResult.contains("<h1>"), "Plain text shouldn't have HTML tags")
            assertTrue(textResult.contains("Heading 1"), "Plain text should contain header content")

            // Structured format
            val structResult = tool.execute(WebExtractArgs(url = "https://extract.com", format = "structured"))
            assertTrue(structResult.contains("Title: Test Title"), "Structured should contain title")
            assertTrue(structResult.contains("Description: Test Desc"), "Structured should contain description")
        }

    @Test
    fun testRssFeed() =
        runTest {
            val client =
                createSecureClient {
                    OutboundHttpResponse(
                        status = 200,
                        location = null,
                        body =
                            """
                            <rss version="2.0">
                              <channel>
                                <item>
                                  <title>RSS Item 1</title>
                                  <link>https://rss.com/1</link>
                                  <pubDate>Wed, 10 Jun 2026 00:00:00 GMT</pubDate>
                                </item>
                              </channel>
                            </rss>
                            """.trimIndent(),
                    )
                }

            val tool = RssFeedTool(client)
            val result = tool.execute(RssFeedArgs(url = "https://rss.com/feed"))
            assertTrue(result.contains("RSS Item 1"), "Result should parse RSS item title")
            assertTrue(result.contains("https://rss.com/1"), "Result should parse RSS item link")
        }

    @Test
    fun `web tools reject private and metadata destinations without opening a client`() =
        runTest {
            var clientOpened = false
            val policy = JvmOutboundUrlPolicy { host -> arrayOf(InetAddress.getByName(host)) }
            val outboundClient =
                PinnedJvmOutboundHttpFetcher(policy) {
                    clientOpened = true
                    error("A blocked destination must not create an HTTP client")
                }

            val crawl = WebCrawlTool(outboundClient).execute(WebCrawlArgs("http://127.0.0.1/admin"))
            val rss = RssFeedTool(outboundClient).execute(RssFeedArgs("http://169.254.169.254/latest/meta-data"))
            val extract = WebExtractTool(outboundClient).execute(WebExtractArgs("http://10.0.0.1/internal"))

            assertTrue(crawl.contains("[ERROR"), "Got: $crawl")
            assertTrue(rss.contains("[ERROR]"), "Got: $rss")
            assertTrue(extract.contains("[ERROR]"), "Got: $extract")
            assertFalse(clientOpened)
        }

    @Test
    fun testXSearchApi() =
        runTest {
            val client = createMockHttpClient { _ ->
                """
                {
                  "data": [
                    {
                      "id": "1",
                      "text": "Hello from X!"
                    }
                  ]
                }
                """.trimIndent()
            }

            // With environment variable mock, XSearchTool should query API
            // Ensure ConfigProvider returns empty keys so the error path is triggered
            ConfigProvider.initialize(MergedConfigProvider { emptyMap() })
            // Here we just test it compiles and executes. If no bearer token is present it falls back to Tavily.
            val tool = XSearchTool(client)
            val result = tool.execute(XSearchArgs(query = "kotlin"))
            // If bearer is empty and Tavily is empty, it returns an error
            assertTrue(result.contains("[ERROR]"), "Result should return error when keys are missing")
        }

    @Test
    fun testBrowserToolsMetadataAndExecution() =
        runTest {
            val fakeBackend = object : BrowserBackend {
                override suspend fun navigate(url: String): BrowserResult = BrowserResult(true, "Navigated to $url")

                override suspend fun click(selector: String): BrowserResult = BrowserResult(true, "Clicked $selector")

                override suspend fun type(
                    selector: String,
                    text: String,
                ): BrowserResult = BrowserResult(true, "Typed $text")

                override suspend fun extract(selector: String): BrowserResult = BrowserResult(true, "Extracted $selector")

                override suspend fun extractPage(): BrowserResult = BrowserResult(true, "Extracted page")

                override suspend fun screenshot(): BrowserResult = BrowserResult(true, "c2NyZWVuc2hvdA==")

                override suspend fun evaluate(expression: String): BrowserResult = BrowserResult(true, "Result of $expression")

                override suspend fun close() {}

                override fun isConnected(): Boolean = true
            }

            val tools = BrowserTools.create(fakeBackend)
            assertEquals(12, tools.size, "Should create 12 tools")

            // Test navigate tool
            val navigateTool = tools.first { it.name == "browser_navigate" } as BrowserNavigateTool
            val navResult = navigateTool.execute(BrowserNavigateArgs("https://example.com"))
            assertEquals("Navigated to https://example.com", navResult)

            // Test click tool
            val clickTool = tools.first { it.name == "browser_click" } as BrowserClickTool
            val clickResult = clickTool.execute(BrowserClickArgs("#btn"))
            assertEquals("Clicked #btn", clickResult)

            // Test type tool
            val typeTool = tools.first { it.name == "browser_type" } as BrowserTypeTool
            val typeResult = typeTool.execute(BrowserTypeArgs("#input", "hello"))
            assertEquals("Typed hello", typeResult)

            // Test extract tool
            val extractTool = tools.first { it.name == "browser_extract" } as BrowserExtractTool
            val extractResult = extractTool.execute(BrowserExtractArgs("#content"))
            assertEquals("Extracted #content", extractResult)
        }

    @Test
    fun `web screenshot is unavailable without a sandbox runner`() =
        runTest {
            val result = WebScreenshotTool(workDir = System.getProperty("java.io.tmpdir"))
                .execute(WebScreenshotArgs(url = "https://example.com"))

            assertTrue(result.startsWith("[SANDBOX UNAVAILABLE]"), "Got: $result")
        }
}
