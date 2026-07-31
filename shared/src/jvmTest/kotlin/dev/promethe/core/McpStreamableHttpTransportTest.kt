package dev.promethe.core

import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpStreamableHttpTransportTest {
    @Test
    fun `MCP requests use the secured outbound client`() =
        runTest {
            val outboundClient = RecordingOutboundClient()
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.example/rpc",
                    customHeaders = mapOf("Authorization" to "Bearer secret"),
                    outboundClient = outboundClient,
                )

            transport.initialize()

            assertEquals("https://mcp.example/rpc", outboundClient.url)
            assertEquals("Bearer secret", outboundClient.headers["Authorization"])
            assertEquals("2025-11-05", outboundClient.headers["MCP-Protocol-Version"])
            assertTrue(outboundClient.body.contains("\"method\":\"initialize\""))
        }

    @Test
    fun `MCP rejects private destinations before opening a client`() =
        runTest {
            var clientOpened = false
            val policy = JvmOutboundUrlPolicy { host -> arrayOf(InetAddress.getByName(host)) }
            val outboundClient =
                PinnedJvmOutboundHttpFetcher(policy) {
                    clientOpened = true
                    error("A blocked destination must not create an HTTP client")
                }
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "http://169.254.169.254/latest/meta-data",
                    outboundClient = outboundClient,
                )

            assertFailsWith<IllegalArgumentException> {
                transport.initialize()
            }
            assertFalse(clientOpened)
        }

    private class RecordingOutboundClient : SecureJvmOutboundHttpClient {
        var url: String = ""
        var headers: Map<String, String> = emptyMap()
        var body: String = ""

        override suspend fun get(url: String): OutboundHttpResponse = error("GET not expected")

        override suspend fun getFollowingRedirects(
            url: String,
            headers: Map<String, String>,
            maxRedirects: Int,
        ): OutboundHttpResponse = error("GET not expected")

        override suspend fun postJsonFollowingRedirects(
            url: String,
            headers: Map<String, String>,
            body: String,
            maxRedirects: Int,
        ): OutboundHttpResponse {
            this.url = url
            this.headers = headers
            this.body = body
            return OutboundHttpResponse(
                status = 200,
                location = null,
                body = """{"jsonrpc":"2.0","id":1,"result":{}}""",
            )
        }
    }
}
