package dev.promethe.core.browser

import dev.promethe.core.ioDispatcher
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * CdpBrowserBackend — Chrome DevTools Protocol implementation.
 *
 * Connects to a running Chrome/Chromium instance via its debug port.
 * Requires Chrome launched with: --remote-debugging-port=9222
 *
 * Uses the HTTP endpoints of CDP (not WebSocket) for simplicity.
 * For production use, the WebSocket transport would offer event subscriptions.
 */
class CdpBrowserBackend(
    private val httpClient: HttpClient,
    private val cdpHost: String = "localhost",
    private val cdpPort: Int = 9222,
) : BrowserBackend {
    private var targetId: String? = null
    private var sessionId: String? = null
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    private val cdpBase get() = "http://$cdpHost:$cdpPort"

    override suspend fun navigate(url: String): BrowserResult =
        withContext(ioDispatcher) {
            try {
                ensureTarget()
                val result = sendCommand("Page.navigate", buildJsonObject { put("url", url) })
                // Wait a bit for page load
                sendCommand(
                    "Runtime.evaluate",
                    buildJsonObject {
                        put("expression", "new Promise(r => setTimeout(r, 2000))")
                        put("awaitPromise", true)
                    },
                )
                BrowserResult(success = true, content = "Navigated to $url")
            } catch (e: Exception) {
                BrowserResult(success = false, error = "Navigate failed: ${e.message}")
            }
        }

    override suspend fun click(selector: String): BrowserResult =
        withContext(ioDispatcher) {
            evaluate("document.querySelector('$selector')?.click(); 'clicked'")
        }

    override suspend fun type(
        selector: String,
        text: String,
    ): BrowserResult =
        withContext(ioDispatcher) {
            val escaped = text.replace("'", "\\'")
            evaluate(
                """
                (() => {
                    const el = document.querySelector('$selector');
                    if (!el) return 'Element not found: $selector';
                    el.focus();
                    el.value = '$escaped';
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    return 'typed';
                })()
                """.trimIndent(),
            )
        }

    override suspend fun extract(selector: String): BrowserResult =
        withContext(ioDispatcher) {
            evaluate("document.querySelector('$selector')?.innerText || 'Element not found'")
        }

    override suspend fun extractPage(): BrowserResult =
        withContext(ioDispatcher) {
            evaluate("document.body?.innerText || ''")
        }

    override suspend fun screenshot(): BrowserResult =
        withContext(ioDispatcher) {
            try {
                ensureTarget()
                val result =
                    sendCommand(
                        "Page.captureScreenshot",
                        buildJsonObject {
                            put("format", "png")
                        },
                    )
                val data =
                    result
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonPrimitive
                        ?.content ?: ""
                BrowserResult(success = true, content = data)
            } catch (e: Exception) {
                BrowserResult(success = false, error = "Screenshot failed: ${e.message}")
            }
        }

    override suspend fun evaluate(expression: String): BrowserResult =
        withContext(ioDispatcher) {
            try {
                ensureTarget()
                val result =
                    sendCommand(
                        "Runtime.evaluate",
                        buildJsonObject {
                            put("expression", expression)
                            put("returnByValue", true)
                        },
                    )
                val value =
                    result
                        ?.jsonObject
                        ?.get("result")
                        ?.jsonObject
                        ?.get("value")
                        ?.jsonPrimitive
                        ?.content ?: ""
                BrowserResult(success = true, content = value)
            } catch (e: Exception) {
                BrowserResult(success = false, error = "Evaluate failed: ${e.message}")
            }
        }

    override suspend fun close() {
        targetId?.let { id ->
            try {
                httpClient.get("$cdpBase/json/close/$id")
            } catch (e: Exception) {
                logger.warn(e) { "Failed to close CDP target $id" }
            }
        }
        targetId = null
        sessionId = null
    }

    override fun isConnected(): Boolean = targetId != null

    // ── Internal CDP Communication ──────────────────────────

    private suspend fun ensureTarget() {
        if (targetId != null) return

        // Get list of targets (tabs)
        val response = httpClient.get("$cdpBase/json/list")
        val body = response.bodyAsText()
        val targets = json.parseToJsonElement(body).jsonArray

        // Use first page target, or create new one
        val pageTarget =
            targets.firstOrNull {
                it.jsonObject["type"]?.jsonPrimitive?.content == "page"
            }

        if (pageTarget != null) {
            targetId = pageTarget.jsonObject["id"]?.jsonPrimitive?.content
        } else {
            // Create new tab
            val newTab = httpClient.get("$cdpBase/json/new")
            val newBody = json.parseToJsonElement(newTab.bodyAsText()).jsonObject
            targetId = newBody["id"]?.jsonPrimitive?.content
        }

        logger.info { "Connected to target: $targetId" }
    }

    /**
     * Send a CDP command via HTTP.
     * Note: This uses the simplified HTTP endpoint, not WebSocket.
     * For commands that require a session, we use the /json/protocol endpoint.
     */
    private suspend fun sendCommand(
        method: String,
        params: JsonObject = buildJsonObject {},
    ): JsonElement? {
        val payload =
            buildJsonObject {
                put("id", 1)
                put("method", method)
                put("params", params)
            }

        // Use the target's WebSocket URL for commands
        val wsUrl = "$cdpBase/json/protocol/$targetId"

        // For simplicity, use the HTTP command endpoint
        val response =
            httpClient.post("$cdpBase/json/command/$targetId") {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }

        val body = response.bodyAsText()
        return try {
            val result = json.parseToJsonElement(body).jsonObject
            result["result"]
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse CDP response for command sent to target $targetId" }
            null
        }
    }
}
