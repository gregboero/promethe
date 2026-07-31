package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import dev.promethe.db.AgentProfileRow
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class MultiModelRouterTest {
    @Test
    fun testGetNextKeyRoundRobin() =
        runTest {
            val db = DatabaseFactory.createInMemory()
            val apiKeys = mapOf(
                "openai" to "key1,key2,key3",
                "anthropic" to "key-anthropic",
            )
            val router = MultiModelRouter(db, apiKeys)

            // Test round robin rotation for openai
            assertEquals("key1", router.getNextKey("openai"))
            assertEquals("key2", router.getNextKey("openai"))
            assertEquals("key3", router.getNextKey("openai"))
            assertEquals("key1", router.getNextKey("openai"))

            // Test single key rotation always returns same key
            assertEquals("key-anthropic", router.getNextKey("anthropic"))
            assertEquals("key-anthropic", router.getNextKey("anthropic"))
        }

    @Test
    fun testBuildFallbackChain() =
        runTest {
            val db = DatabaseFactory.createInMemory()

            // Insert default agent profile
            val defaultProfile = AgentProfileRow(
                id = "default",
                name = "default",
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.2,
                maxIterations = 10,
                systemPrompt = "prompt",
                ephemeral = false,
                createdAt = 0,
            )
            db.insertAgentProfile(defaultProfile)
            db.setDefaultProfile("default")

            val apiKeys = mapOf(
                "openai" to "key1",
                "anthropic" to "key2",
                "google" to "key3",
            )
            val router = MultiModelRouter(db, apiKeys)

            val chain = router.buildFallbackChain("default")
            assertNotNull(chain)

            // First entry should be the primary profile provider/model
            val primaryResult = chain.executeWithFallback { provider, model ->
                provider to model
            }
            assertEquals("openai", primaryResult.result.first)
            assertEquals("gpt-4o", primaryResult.result.second)

            // Throwing error on primary should fall back to next provider
            val fallbackResult = chain.executeWithFallback { provider, model ->
                if (provider == "openai") throw RuntimeException("429 Rate Limit")
                provider
            }
            assertTrue(fallbackResult.result == "anthropic" || fallbackResult.result == "google")
        }
}
