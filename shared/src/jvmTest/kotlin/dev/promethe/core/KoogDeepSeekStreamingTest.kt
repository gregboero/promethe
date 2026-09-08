package dev.promethe.core

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.streaming.StreamFrame
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogDeepSeekStreamingTest {
    @Test
    fun `real HTTP SSE preserves reasoning and reassembles fragmented tool arguments`() =
        runBlocking {
            verifySse(chunked = true)
            verifySse(chunked = false)
        }

    private suspend fun verifySse(chunked: Boolean) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestBody = ""
        server.createContext("/chat/completions") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val deltas = listOf(
                """{"reasoning_content":"fixture reasoning"}""",
                """{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"weather","arguments":"{\"city\":"}}]}""",
                """{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]}""",
            )
            val frames = deltas.map { delta ->
                """{"id":"fixture","system_fingerprint":"local","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":$delta,"finish_reason":null}]}"""
            } + """{"id":"fixture","system_fingerprint":"local","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
            val bytes = (frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = DeepSeekLLMClient(apiKey = "local-fixture-only", settings = DeepSeekClientSettings(baseUrl = "http://127.0.0.1:${server.address.port}"), httpClientFactory = getHttpClientFactory())
        try {
            val frames = withTimeout(15_000) {
                client.executeStreaming(prompt("fixture") { user("Weather in Paris?") }, DeepSeekModels.DeepSeekV4Flash).toList()
            }
            assertTrue("\"stream\":true" in requestBody.replace(" ", ""))
            assertEquals("fixture reasoning", frames.filterIsInstance<StreamFrame.ReasoningDelta>().joinToString("") { it.text.orEmpty() })
            val call = frames.filterIsInstance<StreamFrame.ToolCallComplete>().single()
            assertEquals("call-1", call.id)
            assertEquals("weather", call.name)
            assertEquals(Json.parseToJsonElement("""{"city":"Paris"}"""), call.contentJson)
            assertEquals("tool_calls", frames.filterIsInstance<StreamFrame.End>().single().finishReason)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
