package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixCacheTelemetryTest {
    @Test
    fun `prefix fingerprint is independent of tool registration order`() {
        val alpha = ToolDescriptor(name = "alpha", description = "First")
        val beta = ToolDescriptor(name = "beta", description = "Second")

        val first = stablePromptPrefix("openai", "gpt", listOf("system"), listOf(beta, alpha))
        val second = stablePromptPrefix("openai", "gpt", listOf("system"), listOf(alpha, beta))

        assertEquals(first, second)
        assertEquals(64, first.fingerprint.length)
        assertTrue(first.bytes > 0)
        assertNotEquals(first, stablePromptPrefix("openai", "other", listOf("system"), listOf(alpha, beta)))
    }

    @Test
    fun `anthropic tools are sorted with one cache breakpoint`() {
        val tools =
            preparePromptTools(
                provider = "anthropic",
                tools =
                    listOf(
                        ToolDescriptor(name = "zeta", description = "Last"),
                        ToolDescriptor(name = "alpha", description = "First"),
                    ),
            )

        assertEquals(listOf("alpha", "zeta"), tools.map { it.name })
        assertNull(tools.first().cacheControl)
        assertIs<AnthropicCacheControl.Default>(tools.last().cacheControl)
    }

    @Test
    fun `provider usage reads anthropic metadata and openai raw response`() {
        val anthropic =
            providerCacheUsage(
                ResponseMetaInfo.Empty.copy(
                    metadata =
                        buildJsonObject {
                            put("cacheReadInputTokens", 120)
                            put("cacheCreationInputTokens", 40)
                        },
                ),
                rawResponse = null,
            )
        val openAi =
            providerCacheUsage(
                ResponseMetaInfo.Empty,
                rawResponse =
                    buildJsonObject {
                        put(
                            "usage",
                            buildJsonObject {
                                put(
                                    "prompt_tokens_details",
                                    buildJsonObject { put("cached_tokens", 256) },
                                )
                            },
                        )
                    },
            )

        assertEquals(ProviderCacheUsage(readTokens = 120, writeTokens = 40), anthropic)
        assertEquals(ProviderCacheUsage(readTokens = 256), openAi)
    }

    @Test
    fun `telemetry separates prefix candidates from observable provider hits`() =
        runTest {
            val telemetry = PrefixCacheTelemetry(maxPrefixes = 2)
            val first = StablePromptPrefix("a".repeat(64), 10)
            val second = StablePromptPrefix("b".repeat(64), 20)

            telemetry.observe(first)
            telemetry.observe(first)
            telemetry.observe(second)
            telemetry.recordProviderUsage(ProviderCacheUsage())
            telemetry.recordProviderUsage(ProviderCacheUsage(readTokens = 0, writeTokens = 50))
            telemetry.recordProviderUsage(ProviderCacheUsage(readTokens = 25))

            val snapshot = telemetry.snapshot()
            assertEquals(1, snapshot.candidateHits)
            assertEquals(2, snapshot.candidateMisses)
            assertEquals(1, snapshot.providerHits)
            assertEquals(1, snapshot.providerMisses)
            assertEquals(25, snapshot.providerReadTokens)
            assertEquals(50, snapshot.providerWriteTokens)
            assertEquals(2, snapshot.observableResponses)
            assertEquals(2, snapshot.knownPrefixes)
        }
}
