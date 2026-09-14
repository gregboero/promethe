package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class CampaignResponseDiagnosticsTest {
    private fun body(
        content: String = "\"[1]\"",
        finish: String = "stop",
        extra: String = "",
        usage: String = """{"prompt_tokens":100,"completion_tokens":20,"completion_tokens_details":{"reasoning_tokens":4},"prompt_tokens_details":{"cached_tokens":30}}""",
    ) = """{"id":"chatcmpl-test","model":"test-model","choices":[{"finish_reason":"$finish","message":{"content":$content$extra}}],"usage":$usage}"""

    @Test fun `nullable blank and malformed content never become successful text`() {
        for (content in listOf("null", "\"\"", "\"   \"")) {
            assertEquals("campaign_empty_content", CampaignResponseDiagnostics.inspect(CampaignHttpResponse(200, body(content))).errorCode)
        }
        assertEquals("campaign_invalid_content", CampaignResponseDiagnostics.inspect(CampaignHttpResponse(200, body("[]"))).errorCode)
    }

    @Test fun `provider termination and tool calls have distinct diagnostics`() {
        for ((finish, code) in mapOf("length" to "campaign_completion_length", "content_filter" to "campaign_content_filtered", "tool_calls" to "campaign_unsupported_tool_call", "future" to "campaign_unknown_finish_reason")) {
            assertEquals(code, CampaignResponseDiagnostics.inspect(CampaignHttpResponse(200, body("null", finish))).errorCode)
        }
        assertEquals("campaign_refusal", CampaignResponseDiagnostics.inspect(CampaignHttpResponse(200, body("null", extra = ",\"refusal\":\"private refusal\""))).errorCode)
        assertEquals("campaign_unsupported_tool_call", CampaignResponseDiagnostics.inspect(CampaignHttpResponse(200, body(extra = ",\"tool_calls\":[{\"arguments\":\"private args\"}]"))).errorCode)
    }

    @Test fun `usage subsets are diagnostic and never added twice`() =
        withStore { store, directory ->
            val api = CampaignApi(store, "private-key", directory) { CampaignHttpResponse(200, body(), "req-test") }
            assertEquals("[1]", api.complete("fixture", "test", emptyList()))
            val receipt = receipt(directory)
            assertEquals(490, receipt.getValue("accountedUpperBoundMicroUsd").jsonPrimitive.int)
            assertEquals(4, receipt.getValue("diagnostics").jsonObject.getValue("reasoningTokens").jsonPrimitive.int)
            assertEquals(30, receipt.getValue("diagnostics").jsonObject.getValue("cachedInputTokens").jsonPrimitive.int)
            assertEquals(100, api.lastInputTokens)
            assertEquals("settled", receipt.getValue("accountingState").jsonPrimitive.content)
        }

    @Test fun `rejected text still settles known usage without persisting refusal or tools`() {
        for (extra in listOf("", ",\"refusal\":\"private refusal\"", ",\"tool_calls\":[{\"arguments\":\"private args\"}]")) {
            withStore { store, directory ->
                var calls = 0
                val api = CampaignApi(store, "private-key", directory) {
                    calls++
                    CampaignHttpResponse(200, body("null", extra = extra))
                }
                assertFailsWith<AgentExecutionException> { api.complete("fixture", "test", emptyList()) }
                val receipt = receipt(directory)
                assertEquals(1, calls)
                assertEquals("settled", receipt.getValue("accountingState").jsonPrimitive.content)
                assertFalse(receipt.toString().contains("private"))
                assertFalse("response" in receipt)
            }
        }
    }

    @Test fun `unaccountable responses and transport failures retain reservation and sanitized receipt`() {
        val cases = listOf(
            CampaignHttpResponse(500, "private provider error"),
            CampaignHttpResponse(200, "broken"),
            CampaignHttpResponse(200, body(usage = "null")),
            CampaignHttpResponse(200, body(usage = """{"prompt_tokens":"100","completion_tokens":20}""")),
            CampaignHttpResponse(200, body(usage = """{"prompt_tokens":2147483647,"completion_tokens":20}""")),
            null,
        )
        for (response in cases) {
            withStore { store, directory ->
                var calls = 0
                val api = CampaignApi(store, "private-key", directory) {
                    calls++
                    response ?: error("private transport")
                }
                assertFailsWith<AgentExecutionException> { api.complete("fixture", "test", emptyList()) }
                val receipt = receipt(directory)
                assertEquals(1, calls)
                assertEquals("reserved_uncertain", receipt.getValue("accountingState").jsonPrimitive.content)
                assertEquals(JsonNull, receipt["accountedUpperBoundMicroUsd"])
                assertFalse(receipt.toString().contains("private"))
                val reserved = receipt.getValue("reservationMicroUsd").jsonPrimitive.long
                assertFalse(store.reserve("cannot-forget-uncertain", 5_000_001 - reserved))
            }
        }
    }

    @Test fun `budget exhaustion never sends a request`() =
        withStore { store, directory ->
            assertTrue(store.reserve("existing", 5_000_000))
            val api = CampaignApi(store, "private-key", directory) { error("must not send") }
            assertEquals("campaign_budget_exhausted", assertFailsWith<AgentExecutionException> { api.complete("fixture", "test") }.code)
            assertEquals(0, Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith("request-") }.count() })
        }

    private fun receipt(directory: Path): JsonObject =
        Files.list(directory).use { files ->
            Json.parseToJsonElement(Files.readString(files.filter { it.fileName.toString().startsWith("request-") }.findFirst().orElseThrow())).jsonObject
        }

    private fun withStore(block: (HarnessStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("campaign-diagnostics")
        try {
            block(HarnessStore(directory.resolve("budget.sqlite")), directory)
        } finally {
            check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            directory.toFile().deleteRecursively()
        }
    }
}
