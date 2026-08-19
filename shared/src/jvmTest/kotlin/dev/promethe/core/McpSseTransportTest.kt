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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSseTransportTest {
    @Test
    fun `SSE discovery and requests use the secured outbound client`() =
        runTest {
            val outboundClient = RecordingOutboundClient()
            val transport =
                McpSseTransport(
                    baseUrl = "https://mcp.example/sse",
                    outboundClient = outboundClient,
                )

            transport.initialize()

            assertEquals("https://mcp.example/sse", outboundClient.discoveryUrl)
            assertEquals("text/event-stream", outboundClient.discoveryHeaders["Accept"])
            assertEquals("https://mcp.example/messages?session=one", outboundClient.requestUrl)
            assertEquals(
                McpProtocol.LEGACY_VERSION,
                outboundClient.requestHeaders[McpProtocol.PROTOCOL_VERSION_HEADER],
            )
            assertTrue(outboundClient.requestBody.contains("\"method\":\"initialize\""))
        }

    @Test
    fun `SSE rejects a private initial destination`() =
        runTest {
            var clientOpened = false
            val outboundClient =
                PinnedJvmOutboundHttpFetcher(JvmOutboundUrlPolicy()) {
                    clientOpened = true
                    error("A private destination must not open an HTTP client")
                }
            val transport =
                McpSseTransport(
                    baseUrl = "http://127.0.0.1/sse",
                    outboundClient = outboundClient,
                )

            assertFailsWith<IllegalArgumentException> {
                transport.initialize()
            }
            assertFalse(clientOpened)
        }

    @Test
    fun `SSE rejects a private advertised endpoint`() =
        runTest {
            var clientOpenCount = 0
            val policy = publicExamplePolicy()
            val outboundClient =
                PinnedJvmOutboundHttpFetcher(policy) {
                    clientOpenCount++
                    mockClient(
                        status = HttpStatusCode.OK,
                        body = "event: endpoint\ndata: http://169.254.169.254/messages\n\n",
                    )
                }
            val transport =
                McpSseTransport(
                    baseUrl = "https://mcp.example/sse",
                    outboundClient = outboundClient,
                )

            assertFailsWith<IllegalArgumentException> {
                transport.initialize()
            }
            assertEquals(1, clientOpenCount)
        }

    @Test
    fun `SSE rejects a redirect to a private destination`() =
        runTest {
            var clientOpenCount = 0
            val policy = publicExamplePolicy()
            val outboundClient =
                PinnedJvmOutboundHttpFetcher(policy) {
                    clientOpenCount++
                    mockClient(
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "http://127.0.0.1/internal"),
                    )
                }
            val transport =
                McpSseTransport(
                    baseUrl = "https://mcp.example/sse",
                    outboundClient = outboundClient,
                )

            assertFailsWith<IllegalArgumentException> {
                transport.initialize()
            }
            assertEquals(1, clientOpenCount)
        }

    private fun publicExamplePolicy(): JvmOutboundUrlPolicy =
        JvmOutboundUrlPolicy { host ->
            when (host) {
                "mcp.example" -> arrayOf(InetAddress.getByName("93.184.216.34"))
                else -> arrayOf(InetAddress.getByName(host))
            }
        }

    private fun mockClient(
        status: HttpStatusCode,
        body: String = "",
        headers: io.ktor.http.Headers = headersOf(),
    ): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headers,
                )
            },
        ) {
            followRedirects = false
        }

    private class RecordingOutboundClient : SecureJvmOutboundHttpClient {
        var discoveryUrl: String = ""
        var discoveryHeaders: Map<String, String> = emptyMap()
        var requestUrl: String = ""
        var requestHeaders: Map<String, String> = emptyMap()
        var requestBody: String = ""

        override suspend fun get(url: String): OutboundHttpResponse = error("Direct GET not expected")

        override suspend fun getFollowingRedirects(
            url: String,
            headers: Map<String, String>,
            maxRedirects: Int,
        ): OutboundHttpResponse {
            discoveryUrl = url
            discoveryHeaders = headers
            return OutboundHttpResponse(
                status = 200,
                location = null,
                body = "event: endpoint\ndata: /messages?session=one\n\n",
            )
        }

        override suspend fun postJsonFollowingRedirects(
            url: String,
            headers: Map<String, String>,
            body: String,
            maxRedirects: Int,
        ): OutboundHttpResponse {
            requestUrl = url
            requestHeaders = headers
            requestBody = body
            return OutboundHttpResponse(
                status = 200,
                location = null,
                body = """{"jsonrpc":"2.0","id":1,"result":{}}""",
            )
        }
    }
}
