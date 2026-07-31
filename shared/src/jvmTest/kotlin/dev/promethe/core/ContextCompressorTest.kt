package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral tests for [ContextCompressor] — including the over-threshold
 * summarization path, driven through a stubbed [KoogLlmAdapter.complete]
 * so no LLM is needed.
 */
class ContextCompressorTest {
    /** Adapter stub: returns a canned summary and counts LLM calls. */
    private class StubAdapter(
        config: AgentConfig,
    ) : KoogLlmAdapter(config) {
        var completeCalls = 0

        override suspend fun complete(
            systemPrompt: String,
            messages: List<Pair<String, String>>,
            model: String,
            temperature: Double,
        ): LlmResponse {
            completeCalls++
            return LlmResponse(
                content = "STUB_SUMMARY",
                promptTokens = 0,
                completionTokens = 0,
                model = model,
                provider = "stub",
            )
        }
    }

    // Tiny window so a dozen normal messages blow past the threshold.
    private val smallConfig =
        AgentConfig(
            maxContextTokens = 100,
            compressionThreshold = 0.5, // threshold = 50 tokens
        )

    /** 12 plain messages of ~120 chars each (~30 tokens each → ~360 tokens total). */
    private fun longHistory(): List<Pair<String, String>> =
        (1..12).map { i ->
            val role = if (i % 2 == 0) "assistant" else "user"
            role to "Message number $i — ".repeat(6)
        }

    // ── Early-return branches ────────────────────────────────────

    @Test
    fun `under threshold returns messages unchanged`() =
        runTest {
            val config = AgentConfig(maxContextTokens = 1_000_000, compressionThreshold = 0.8)
            val adapter = StubAdapter(config)
            val messages = longHistory()

            val result = ContextCompressor(adapter, config).compress("sys", messages)

            assertEquals(messages, result)
            assertEquals(0, adapter.completeCalls, "No LLM call expected under threshold")
        }

    @Test
    fun `too few messages are returned unchanged even over threshold`() =
        runTest {
            val adapter = StubAdapter(smallConfig)
            // 9 messages = keepFirstN(2) + keepLastN(6) + 1 → early return
            val messages = longHistory().take(9)

            val result = ContextCompressor(adapter, smallConfig).compress("sys", messages)

            assertEquals(messages, result)
            assertEquals(0, adapter.completeCalls)
        }

    // ── Real compression path ────────────────────────────────────

    @Test
    fun `over threshold summarizes the middle and keeps head and tail`() =
        runTest {
            val adapter = StubAdapter(smallConfig)
            val messages = longHistory()

            val result = ContextCompressor(adapter, smallConfig).compress("sys", messages)

            // head(2) + summary(1) + tail(6)
            assertEquals(9, result.size, "Expected head+summary+tail, got: ${result.map { it.second.take(20) }}")
            assertEquals(messages.take(2), result.take(2), "First messages must be preserved verbatim")
            assertEquals(messages.takeLast(6), result.takeLast(6), "Last messages must be preserved verbatim")

            val summaryMessage = result[2]
            assertEquals("system", summaryMessage.first)
            assertTrue(summaryMessage.second.contains("[Context Summary"), "Got: ${summaryMessage.second.take(60)}")
            assertTrue(summaryMessage.second.contains("STUB_SUMMARY"))
            assertTrue(summaryMessage.second.contains("4 messages compressed"), "12 - 2 head - 6 tail = 4 middle")
            assertEquals(1, adapter.completeCalls, "Exactly one summarization call expected")
        }

    @Test
    fun `summary is cached — same block does not call the LLM twice`() =
        runTest {
            val adapter = StubAdapter(smallConfig)
            val compressor = ContextCompressor(adapter, smallConfig)
            val messages = longHistory()

            compressor.compress("sys", messages)
            compressor.compress("sys", messages)

            assertEquals(1, adapter.completeCalls, "Second compression of the same block must hit the cache")
        }

    @Test
    fun `clearCache forces re-summarization`() =
        runTest {
            val adapter = StubAdapter(smallConfig)
            val compressor = ContextCompressor(adapter, smallConfig)
            val messages = longHistory()

            compressor.compress("sys", messages)
            compressor.clearCache()
            compressor.compress("sys", messages)

            assertEquals(2, adapter.completeCalls, "Cache cleared — the LLM must be called again")
        }

    @Test
    fun `different blocks are summarized separately`() =
        runTest {
            val adapter = StubAdapter(smallConfig)
            val compressor = ContextCompressor(adapter, smallConfig)

            compressor.compress("sys", longHistory())
            compressor.compress("sys", longHistory().map { (r, c) -> r to "$c!" })

            assertEquals(2, adapter.completeCalls)
        }
}
