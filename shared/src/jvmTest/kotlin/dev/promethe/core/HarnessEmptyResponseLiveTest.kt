package dev.promethe.core

import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

/** Fifteen first-turn probes only. No continuation, native workers, skill synthesis or retries. */
class HarnessEmptyResponseLiveTest {
    @Test fun `isolate empty responses across minimal and reconstructed prompts`() =
        runBlocking {
            assumeTrue(System.getenv("PROMETHE_HARNESS_EMPTY_PROBE") == "true")
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            val budget = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_BUDGET_DB")))
            check(budget.isAbsolute && Files.isRegularFile(budget))
            val batch = "empty-probe-${UUID.randomUUID()}"
            val directory = Files.createDirectories(output.resolve(batch))
            val historical = output.resolve("kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3/kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3-small_mixed-1-original-transcript.json")
            val task = Json.parseToJsonElement(Files.readString(historical)).jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content
            val (system, history) = captureHarnessProbePrompt(directory, task)
            check(history == listOf("user" to task))

            data class Variant(
                val name: String,
                val prompt: String,
                val history: List<Pair<String, String>>?,
                val omit: Boolean = false,
            )
            val variants = listOf(
                Variant("text_user", "Reply exactly OK.", null),
                Variant("text_roles", "You are a helpful assistant.", listOf("user" to "Reply exactly OK.")),
                Variant("json_roles", "You are a helpful assistant.", listOf("user" to "Return exactly this JSON and nothing else: {\"action\":{\"tool_name\":\"json_query\",\"args\":{\"page\":0}}}")),
                Variant("reconstructed_none", system, history),
                Variant("reconstructed_default", system, history, true),
            )
            val sequence = (1..3).flatMap { repeat -> (variants.drop(repeat - 1) + variants.take(repeat - 1)).map { repeat to it } }
            Files.writeString(
                directory.resolve("protocol.json"),
                buildJsonObject {
                    put("batch", batch)
                    put("plannedCalls", 15)
                    put("repetitions", 3)
                    put("model", "gpt-5.6-terra")
                    put("endpoint", "https://api.openai.com/v1/chat/completions")
                    put("maxCompletionTokens", 4096)
                    put("campaignCapMicroUsd", 5_000_000)
                    put("captureCalls", 1)
                    put("captureModelCalls", 0)
                    put("toolExecutionEnabled", false)
                    put("historicalSystemPromptWasArchived", false)
                    put(
                        "variants",
                        JsonArray(
                            variants.map { variant ->
                                buildJsonObject {
                                    put("name", variant.name)
                                    put("prompt", variant.prompt)
                                    put(
                                        "history",
                                        variant.history?.let {
                                            JsonArray(
                                                it.map { (role, content) ->
                                                    buildJsonObject {
                                                        put("role", role)
                                                        put("content", content)
                                                    }
                                                },
                                            )
                                        } ?: JsonNull,
                                    )
                                    put("reasoningEffort", if (variant.omit) "omitted" else "none")
                                }
                            },
                        ),
                    )
                    put("sequence", JsonArray(sequence.map { (repeat, variant) -> JsonPrimitive("${variant.name}-$repeat") }))
                }.toString(),
            )
            val credentials = requireNotNull(CredentialsStore.load())
            check(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra")
            val store = HarnessStore(budget)
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
            val results = mutableListOf<JsonObject>()
            for ((repeat, variant) in sequence) {
                val label = "$batch-${variant.name}-$repeat"
                var audit: JsonObject? = null
                val api = CampaignApi(store, credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }, output) { request ->
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 200) audit = runCatching { CampaignWireAudit.inspect(response.body()) }.getOrNull()
                    CampaignHttpResponse(response.statusCode(), response.body(), response.headers().firstValue("x-request-id").orElse(null))
                }
                var text: String? = null
                var code: String? = null
                try {
                    text = api.complete(variant.prompt, label, variant.history, omitReasoningEffort = variant.omit)
                } catch (error: AgentExecutionException) {
                    code = error.code
                }
                val expected = if (variant.name.startsWith("text_")) {
                    text?.trim() == "OK"
                } else {
                    runCatching {
                        val action = Json.parseToJsonElement(requireNotNull(AgentActionJson.extract(text.orEmpty()))).jsonObject.getValue("action").jsonObject
                        action.getValue("tool_name").jsonPrimitive.content == "json_query" && action.getValue("args").jsonObject.getValue("page").jsonPrimitive.int == 0
                    }.getOrDefault(false)
                }
                results.add(
                    buildJsonObject {
                        put("label", label)
                        put("variant", variant.name)
                        put("repeat", repeat)
                        put("response", text)
                        put("errorCode", code)
                        put("matchesExpectedFirstResponse", expected)
                        put("wireAudit", audit ?: JsonNull)
                    },
                )
                Files.writeString(directory.resolve("results.json"), JsonArray(results).toString())
                check(code == null || code in setOf("campaign_empty_content", "campaign_completion_length", "campaign_refusal", "campaign_content_filtered", "campaign_unsupported_tool_call")) { "Probe stopped on transport, schema or budget error" }
            }
            check(results.size == 15)
        }
}
