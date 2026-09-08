package dev.promethe.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Loopback-only experiment transport. Every armed adapter call permits at most one paid send. */
internal class CampaignKoogRelay(
    private val store: HarnessStore,
    private val key: String,
    private val output: Path,
    private val upstream: ((String) -> CampaignHttpResponse)? = null,
) : AutoCloseable {
    private val slot = AtomicReference<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    var sends = 0
        private set
    var blocked = 0
        private set

    init {
        server.createContext("/") { exchange ->
            var status = 400
            var response = """{"error":{"message":"LAB request rejected","type":"invalid_request_error"}}"""
            try {
                val label = slot.getAndSet(null)
                if (label == null) {
                    blocked++
                } else {
                    check(exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/responses")
                    val bytes = exchange.requestBody.readNBytes(65537)
                    check(bytes.size <= 65536 && sends < 36)
                    val payload = bytes.decodeToString()
                    val request = Json.parseToJsonElement(payload).jsonObject
                    check(request["model"]?.jsonPrimitive?.content == "gpt-5.6-terra")
                    check(request["max_output_tokens"]?.jsonPrimitive?.int == 4096)
                    check(request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content == "none")
                    check(request["store"]?.jsonPrimitive?.boolean == false)
                    check(request["parallel_tool_calls"]?.jsonPrimitive?.boolean == false)
                    check(request["stream"]?.jsonPrimitive?.boolean != true)
                    val reservation = (bytes.size + 8192L) * 5 + 4096L * 18
                    val id = UUID.randomUUID().toString()
                    check(store.reserve(id, reservation)) { "Campaign ceiling reached" }
                    val started = System.nanoTime()
                    sends++
                    val received = runCatching {
                        upstream?.invoke(payload) ?: client.send(
                            HttpRequest.newBuilder(URI("https://api.openai.com/v1/responses"))
                                .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer $key")
                                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        ).let { CampaignHttpResponse(it.statusCode(), it.body(), it.headers().firstValue("x-request-id").orElse(null)) }
                    }.getOrNull()
                    val body = received?.body?.takeIf { it.length <= 1024 * 1024 }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    val usage = body?.get("usage") as? JsonObject

                    fun tokens(
                        name: String,
                        limit: Long,
                    ) = (usage?.get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..limit }
                    val input = tokens("input_tokens", Int.MAX_VALUE.toLong())
                    val generated = tokens("output_tokens", 4096)
                    val charge = if (received?.status == 200 && input != null && generated != null) ((input * 5 + 1) / 2 + generated * 12).takeIf { it <= reservation } else null
                    if (charge != null) store.settle(id, charge)
                    val items = (body?.get("output") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    val content = items.flatMap { (it["content"] as? JsonArray).orEmpty() }.mapNotNull { it as? JsonObject }
                    val requestItems = (request["input"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    Files.writeString(
                        output.resolve("request-$id.json"),
                        buildJsonObject {
                            put("id", id)
                            put("label", label)
                            put("endpoint", "responses")
                            put("requestSha256", SessionHarness.sha256(payload))
                            put("requestBytes", bytes.size)
                            put("inputBytes", request.getValue("input").toString().encodeToByteArray().size)
                            put("formalToolBytes", (request["tools"] ?: JsonArray(emptyList())).toString().encodeToByteArray().size)
                            put("inputSha256", SessionHarness.sha256(request.getValue("input").toString()))
                            put("model", "gpt-5.6-terra")
                            put("reasoningEffort", "none")
                            put("maxOutputTokens", 4096)
                            put("toolDefinitions", (request["tools"] as? JsonArray)?.size ?: 0)
                            put("toolResultInputs", requestItems.count { it["type"]?.jsonPrimitive?.content == "function_call_output" })
                            val historyItems = requestItems.filter { it["type"]?.jsonPrimitive?.content in setOf("function_call", "function_call_output") }
                            put(
                                "nativeHistoryPairsValid",
                                historyItems.size % 2 == 0 && historyItems.chunked(2).all { pair ->
                                    pair.size == 2 && pair[0]["type"]?.jsonPrimitive?.content == "function_call" && pair[1]["type"]?.jsonPrimitive?.content == "function_call_output" && pair[0]["call_id"] == pair[1]["call_id"]
                                },
                            )
                            put(
                                "nativeHistoryPages",
                                JsonArray(
                                    historyItems.filter { it["type"]?.jsonPrimitive?.content == "function_call" }.map { item ->
                                        runCatching { Json.parseToJsonElement(item.getValue("arguments").jsonPrimitive.content).jsonObject.getValue("page") }.getOrDefault(JsonNull)
                                    },
                                ),
                            )
                            put("inputTokens", input?.let(::JsonPrimitive) ?: JsonNull)
                            put("outputTokens", generated?.let(::JsonPrimitive) ?: JsonNull)
                            put("accountedUpperBoundMicroUsd", charge?.let(::JsonPrimitive) ?: JsonNull)
                            put("reservationMicroUsd", reservation)
                            put("accountingState", if (charge == null) "reserved_uncertain" else "settled")
                            put("httpStatus", received?.status?.let(::JsonPrimitive) ?: JsonNull)
                            put("requestId", received?.requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
                            put("responseStatus", (body?.get("status") as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("completed", "incomplete", "failed") })
                            put("functionCalls", items.count { it["type"]?.jsonPrimitive?.content == "function_call" })
                            put("visibleTextCharacters", content.filter { it["type"]?.jsonPrimitive?.content == "output_text" }.sumOf { it["text"]?.jsonPrimitive?.contentOrNull?.length ?: 0 })
                            put("refusalPresent", content.any { it["type"]?.jsonPrimitive?.content == "refusal" })
                            put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                        }.toString(),
                    )
                    if (charge != null && body?.get("status")?.jsonPrimitive?.content == "completed") {
                        status = 200
                        response = requireNotNull(received).body
                    }
                }
            } catch (_: Exception) {
                // Never echo credentials, arbitrary provider errors or request bodies.
            }
            val bytes = response.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun arm(label: String) {
        check(slot.compareAndSet(null, label))
    }

    override fun close() = server.stop(0)
}
