package dev.promethe.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebScraperToolTest {
    @Test
    fun `scraper follows redirects through the pinned client`() =
        runTest {
            val requestedHosts = mutableListOf<String?>()
            var clientCount = 0
            val scraper =
                WebScraperTool(
                    PinnedJvmOutboundHttpFetcher(
                        policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) },
                        clientFactory = {
                            clientCount++
                            HttpClient(
                                MockEngine { request ->
                                    requestedHosts += request.headers[HttpHeaders.Host]
                                    when (request.url.encodedPath) {
                                        "/start" -> {
                                            respond(
                                                content = "",
                                                status = HttpStatusCode.Found,
                                                headers = headersOf(HttpHeaders.Location, "https://public.example/final"),
                                            )
                                        }

                                        "/final" -> {
                                            respond("<html><body><p>Safe content</p></body></html>")
                                        }

                                        else -> {
                                            error("Unexpected request path: ${request.url.encodedPath}")
                                        }
                                    }
                                },
                            ) {
                                followRedirects = false
                            }
                        },
                    ),
                )

            val result = scraper.execute(WebScraperArgs(url = "https://public.example/start"))

            assertTrue(result.contains("Safe content"), result)
            assertEquals(2, clientCount)
            assertEquals(listOf<String?>("public.example", "public.example"), requestedHosts)
        }

    @Test
    fun `scraper rejects a private redirect before opening a second connection`() =
        runTest {
            var clientCount = 0
            val scraper =
                WebScraperTool(
                    PinnedJvmOutboundHttpFetcher(
                        policy =
                            JvmOutboundUrlPolicy { host ->
                                if (host == "private.example") {
                                    arrayOf(InetAddress.getByName("127.0.0.1"))
                                } else {
                                    arrayOf(InetAddress.getByName("93.184.216.34"))
                                }
                            },
                        clientFactory = {
                            clientCount++
                            HttpClient(
                                MockEngine {
                                    respond(
                                        content = "",
                                        status = HttpStatusCode.Found,
                                        headers = headersOf(HttpHeaders.Location, "https://private.example/internal"),
                                    )
                                },
                            ) {
                                followRedirects = false
                            }
                        },
                    ),
                )

            val result = scraper.execute(WebScraperArgs(url = "https://public.example/start"))

            assertTrue(result.startsWith("[ERROR] Web scraping failed:"), result)
            assertTrue(result.contains("private, local, or special-use destinations are blocked"), result)
            assertEquals(1, clientCount)
            assertFalse(result.contains("internal"))
        }
}
