package dev.promethe.core.browser

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * BrowserbaseBrowserBackend — cloud browser automation via Browserbase.
 *
 * Browserbase provides managed headless Chrome with anti-bot stealth tooling.
 * Flow:
 *   1. Create a session via REST API → get a CDP WebSocket URL
 *   2. Use CDP commands over HTTP (same as [CdpBrowserBackend])
 *   3. Close session when done
 *
 * Required env vars:
 * - BROWSERBASE_API_KEY
 * - BROWSERBASE_PROJECT_ID
 */
class BrowserbaseBrowserBackend(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val projectId: String,
) : BrowserBackend {
    private val baseUrl = "https://api.browserbase.com"
    private var sessionId: String? = null
    private var cdpBackend: CdpBrowserBackend? = null
    private var connected = false

    /**
     * Create a Browserbase session and connect via CDP.
     */
    suspend fun connect(): BrowserResult {
        return try {
            // 1. Create session
            val createResponse =
                httpClient.post("$baseUrl/v1/sessions") {
                    header("x-bb-api-key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"projectId":"$projectId"}""")
                }

            if (!createResponse.status.isSuccess()) {
                return BrowserResult(false, error = "Failed to create Browserbase session: ${createResponse.status}")
            }

            val responseJson = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            sessionId = responseJson["id"]?.jsonPrimitive?.content
                ?: return BrowserResult(false, error = "No session ID in response")

            // 2. Get CDP connect URL
            val connectResponse =
                httpClient.get("$baseUrl/v1/sessions/$sessionId/debug") {
                    header("x-bb-api-key", apiKey)
                }

            val debugJson = Json.parseToJsonElement(connectResponse.bodyAsText()).jsonObject
            val debuggerUrl = debugJson["debuggerFullscreenUrl"]?.jsonPrimitive?.content
            val cdpUrl = debugJson["wsUrl"]?.jsonPrimitive?.content

            // 3. Extract CDP HTTP endpoint from WebSocket URL
            // wsUrl is like "wss://connect.browserbase.com/..."
            // We need to derive the HTTP endpoint for our CDP HTTP-based approach
            val cdpHost =
                cdpUrl?.replace("wss://", "")?.replace("ws://", "")?.substringBefore("/")
                    ?: "connect.browserbase.com"
            val cdpPort = 443

            cdpBackend = CdpBrowserBackend(httpClient, cdpHost, cdpPort)
            connected = true

            BrowserResult(true, "Connected to Browserbase session: $sessionId")
        } catch (e: Exception) {
            BrowserResult(false, error = "Browserbase connection failed: ${e.message}")
        }
    }

    private suspend fun ensureConnected() {
        if (!connected || cdpBackend == null) {
            connect()
        }
    }

    override suspend fun navigate(url: String): BrowserResult {
        ensureConnected()
        return cdpBackend?.navigate(url) ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun click(selector: String): BrowserResult {
        ensureConnected()
        return cdpBackend?.click(selector) ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun type(
        selector: String,
        text: String,
    ): BrowserResult {
        ensureConnected()
        return cdpBackend?.type(selector, text) ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun extract(selector: String): BrowserResult {
        ensureConnected()
        return cdpBackend?.extract(selector) ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun extractPage(): BrowserResult {
        ensureConnected()
        return cdpBackend?.extractPage() ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun screenshot(): BrowserResult {
        ensureConnected()
        return cdpBackend?.screenshot() ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun evaluate(expression: String): BrowserResult {
        ensureConnected()
        return cdpBackend?.evaluate(expression) ?: BrowserResult(false, error = "Not connected")
    }

    override suspend fun close() {
        try {
            cdpBackend?.close()
            sessionId?.let { sid ->
                httpClient.patch("$baseUrl/v1/sessions/$sid") {
                    header("x-bb-api-key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"COMPLETED"}""")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Best-effort cleanup failed for Browserbase session $sessionId" }
        } finally {
            connected = false
            cdpBackend = null
            sessionId = null
        }
    }

    override fun isConnected(): Boolean = connected
}
