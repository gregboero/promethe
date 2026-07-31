package dev.promethe.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ModelPricingServiceTest {
    private fun createMockClient(handler: (String) -> String = { "" }): HttpClient =
        HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                respond(
                    content = handler(url),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    private fun createFailingClient(): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = "Internal Server Error",
                    status = HttpStatusCode.InternalServerError,
                )
            },
        )

    // ── Ollama is always free ────────────────────────────────────────

    @Test
    fun `ollama provider returns zero pricing`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("ollama", "llama3")
            assertEquals(0.0, prompt)
            assertEquals(0.0, completion)
        }

    @Test
    fun `ollama is case-insensitive`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            for (variant in listOf("Ollama", "OLLAMA", "oLLaMa")) {
                val (prompt, completion) = service.getPricing(variant, "mistral")
                assertEquals(0.0, prompt, "prompt should be free for '$variant'")
                assertEquals(0.0, completion, "completion should be free for '$variant'")
            }
        }

    @Test
    fun `ollama returns free pricing for any model id`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            for (model in listOf("llama3", "mistral", "codellama:13b", "totally-unknown-model")) {
                val (prompt, completion) = service.getPricing("ollama", model)
                assertEquals(0.0, prompt)
                assertEquals(0.0, completion)
            }
        }

    @Test
    fun `ollama does not hit http at all`() =
        runTest {
            var httpCalled = false
            val client = HttpClient(
                MockEngine {
                    httpCalled = true
                    respond("", HttpStatusCode.OK)
                },
            )
            val service = ModelPricingService(client)
            service.getPricing("ollama", "llama3")
            assertFalse(httpCalled, "Ollama should short-circuit without HTTP calls")
        }

    // ── Known pricing fallback ───────────────────────────────────────

    @Test
    fun `openai provider returns known pricing for gpt-4o`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("openai", "gpt-4o")
            assertEquals(2.50, prompt)
            assertEquals(10.0, completion)
        }

    @Test
    fun `openai provider returns known pricing for gpt-4o-mini`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("openai", "gpt-4o-mini")
            assertEquals(0.15, prompt)
            assertEquals(0.60, completion)
        }

    @Test
    fun `anthropic provider returns known pricing for claude-sonnet-4`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("anthropic", "claude-sonnet-4-20250514")
            assertEquals(3.0, prompt)
            assertEquals(15.0, completion)
        }

    @Test
    fun `google provider returns known pricing for gemini-2-5-pro`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("google", "gemini-2.5-pro")
            assertEquals(1.25, prompt)
            assertEquals(10.0, completion)
        }

    @Test
    fun `deepseek provider returns known pricing for deepseek-chat`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("deepseek", "deepseek-chat")
            assertEquals(0.27, prompt)
            assertEquals(1.10, completion)
        }

    @Test
    fun `deepseek unknown model falls back to deepseek-chat pricing`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("deepseek", "deepseek-unknown-model")
            // Source: KNOWN_PRICING["deepseek-chat"] as second fallback
            assertEquals(0.27, prompt)
            assertEquals(1.10, completion)
        }

    // ── DEFAULT_PRICING fallback ─────────────────────────────────────

    @Test
    fun `openai unknown model returns default pricing`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("openai", "unknown-model-xyz")
            assertEquals(ModelPricingService.DEFAULT_PRICING.first, prompt)
            assertEquals(ModelPricingService.DEFAULT_PRICING.second, completion)
        }

    @Test
    fun `anthropic unknown model returns default pricing`() =
        runTest {
            val service = ModelPricingService(createMockClient())
            val (prompt, completion) = service.getPricing("anthropic", "claude-99")
            assertEquals(ModelPricingService.DEFAULT_PRICING.first, prompt)
            assertEquals(ModelPricingService.DEFAULT_PRICING.second, completion)
        }

    // ── Caching ──────────────────────────────────────────────────────

    @Test
    fun `pricing is cached after first fetch`() =
        runTest {
            var callCount = 0
            val client = HttpClient(
                MockEngine {
                    callCount++
                    respond(
                        content = """{"data":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
            val service = ModelPricingService(client)

            // First call triggers HTTP
            service.getPricing("openrouter", "test-model")
            val firstCallCount = callCount

            // Second call should hit cache, no additional HTTP
            service.getPricing("openrouter", "test-model")
            assertEquals(firstCallCount, callCount, "Second call should use cache, not fetch again")
        }

    @Test
    fun `different models are cached independently`() =
        runTest {
            var callCount = 0
            val client = HttpClient(
                MockEngine {
                    callCount++
                    respond(
                        content = """{"data":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
            val service = ModelPricingService(client)

            service.getPricing("openrouter", "model-a")
            val afterFirstModel = callCount

            // Different model triggers a new fetch
            service.getPricing("openrouter", "model-b")
            assertTrue(callCount > afterFirstModel, "Different model should trigger a new fetch")
        }

    // ── OpenRouter API fetch ─────────────────────────────────────────

    @Test
    fun `openrouter fetches pricing from API`() =
        runTest {
            val client = createMockClient { url ->
                if (url.contains("openrouter.ai")) {
                    """
                    {
                      "data": [
                        {
                          "id": "openai/gpt-4o",
                          "name": "GPT-4o",
                          "pricing": {
                            "prompt": "0.000005",
                            "completion": "0.000015"
                          },
                          "context_length": 128000
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }

            val service = ModelPricingService(client)
            val (prompt, completion) = service.getPricing("openrouter", "openai/gpt-4o")
            // 0.000005 * 1_000_000 = 5.0
            assertEquals(5.0, prompt)
            // 0.000015 * 1_000_000 = 15.0
            assertEquals(15.0, completion)
        }

    @Test
    fun `openrouter returns default for unknown model`() =
        runTest {
            val client = createMockClient { url ->
                if (url.contains("openrouter.ai")) {
                    """{"data": [{"id": "other-model", "name": "Other"}]}"""
                } else {
                    ""
                }
            }

            val service = ModelPricingService(client)
            val (prompt, completion) = service.getPricing("openrouter", "missing-model")
            assertEquals(ModelPricingService.DEFAULT_PRICING.first, prompt)
            assertEquals(ModelPricingService.DEFAULT_PRICING.second, completion)
        }

    // ── LiteLLM API fetch ────────────────────────────────────────────

    @Test
    fun `litellm fetches pricing from proxy`() =
        runTest {
            val client = createMockClient { url ->
                if (url.contains("litellm.local/model/info")) {
                    """
                    {
                      "data": [
                        {
                          "model_name": "gpt-4o",
                          "model_info": {
                            "input_cost_per_token": 0.0000025,
                            "output_cost_per_token": 0.00001
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }

            val service = ModelPricingService(client, litellmBaseUrl = "http://litellm.local")
            val (prompt, completion) = service.getPricing("litellm", "gpt-4o")
            // 0.0000025 * 1_000_000 = 2.5
            assertEquals(2.5, prompt)
            // 0.00001 * 1_000_000 = 10.0
            assertEquals(10.0, completion)
        }

    @Test
    fun `litellm with blank base url falls back to known pricing`() =
        runTest {
            val service = ModelPricingService(createMockClient(), litellmBaseUrl = "")
            val (prompt, completion) = service.getPricing("litellm", "gpt-4o")
            assertEquals(2.50, prompt)
            assertEquals(10.0, completion)
        }

    @Test
    fun `litellm with blank base url and unknown model returns default`() =
        runTest {
            val service = ModelPricingService(createMockClient(), litellmBaseUrl = "")
            val (prompt, completion) = service.getPricing("litellm", "totally-unknown")
            assertEquals(ModelPricingService.DEFAULT_PRICING.first, prompt)
            assertEquals(ModelPricingService.DEFAULT_PRICING.second, completion)
        }

    // ── Unknown provider ─────────────────────────────────────────────

    @Test
    fun `unknown provider without litellm falls back to openrouter`() =
        runTest {
            val client = createMockClient { url ->
                if (url.contains("openrouter.ai")) {
                    """
                    {
                      "data": [
                        {
                          "id": "custom-model",
                          "name": "Custom",
                          "pricing": {"prompt": "0.000001", "completion": "0.000002"}
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }

            val service = ModelPricingService(client, litellmBaseUrl = "")
            val (prompt, completion) = service.getPricing("some-random-provider", "custom-model")
            assertEquals(1.0, prompt)
            assertEquals(2.0, completion)
        }

    @Test
    fun `unknown provider with litellm configured tries litellm first`() =
        runTest {
            val client = createMockClient { url ->
                if (url.contains("litellm.local")) {
                    """
                    {
                      "data": [
                        {
                          "model_name": "my-model",
                          "model_info": {
                            "input_cost_per_token": 0.000003,
                            "output_cost_per_token": 0.000006
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            }

            val service = ModelPricingService(client, litellmBaseUrl = "http://litellm.local")
            val (prompt, completion) = service.getPricing("custom-provider", "my-model")
            assertEquals(3.0, prompt)
            assertEquals(6.0, completion)
        }

    // ── Error handling ───────────────────────────────────────────────

    @Test
    fun `http failure falls back to default pricing`() =
        runTest {
            val service = ModelPricingService(createFailingClient())
            val (prompt, completion) = service.getPricing("openrouter", "any-model")
            assertEquals(ModelPricingService.DEFAULT_PRICING.first, prompt)
            assertEquals(ModelPricingService.DEFAULT_PRICING.second, completion)
        }

    @Test
    fun `litellm http failure falls back to known pricing`() =
        runTest {
            val service = ModelPricingService(createFailingClient(), litellmBaseUrl = "http://litellm.local")
            val (prompt, completion) = service.getPricing("litellm", "gpt-4o")
            // Falls back to KNOWN_PRICING["gpt-4o"]
            assertEquals(2.50, prompt)
            assertEquals(10.0, completion)
        }

    // ── Companion object constants ───────────────────────────────────

    @Test
    fun `DEFAULT_PRICING is 1 dollar per million`() {
        assertEquals(Pair(1.0, 1.0), ModelPricingService.DEFAULT_PRICING)
    }

    @Test
    fun `KNOWN_PRICING contains expected providers`() {
        val known = ModelPricingService.KNOWN_PRICING
        // Verify key models from each provider are present
        assertNotNull(known["gpt-4o"], "Should contain OpenAI gpt-4o")
        assertNotNull(known["claude-sonnet-4-20250514"], "Should contain Anthropic claude-sonnet-4")
        assertNotNull(known["gemini-2.5-pro"], "Should contain Google gemini-2.5-pro")
        assertNotNull(known["deepseek-chat"], "Should contain DeepSeek deepseek-chat")
        assertNotNull(known["nvidia/llama-3.1-nemotron-70b-instruct"], "Should contain NVIDIA model")
    }
}
