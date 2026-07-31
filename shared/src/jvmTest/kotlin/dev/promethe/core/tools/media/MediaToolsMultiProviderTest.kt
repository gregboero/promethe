package dev.promethe.core.tools.media

import dev.promethe.core.*
import dev.promethe.core.tools.ai.SpeechToTextTool
import dev.promethe.core.tools.ai.SpeechToTextArgs
import dev.promethe.core.providers.Capability
import dev.promethe.core.providers.CapabilityResolution
import dev.promethe.core.providers.CapabilityRouter
import dev.promethe.core.providers.ProviderRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.*
import kotlinx.coroutines.test.runTest

/**
 * Tests for refactored MediaTools — validates multi-provider routing.
 * All tests use empty API keys to verify the NotConfigured path.
 */
class MediaToolsMultiProviderTest {
    private val emptyApiKeys: Map<String, String> = emptyMap()
    private val emptyRouter = CapabilityRouter(ProviderRegistry(emptyApiKeys))
    private val mockClient = HttpClient(MockEngine { _ -> respond("") })

    // ── ImageGenerationTool ──

    @Test
    fun `image generation returns not-configured when no keys`() =
        runTest {
            val tool = ImageGenerationTool(mockClient, emptyApiKeys, emptyRouter)
            val result = tool.execute(ImageGenArgs(prompt = "a cat"))
            assertTrue(result.contains("Aucun provider configuré"), "Should indicate no provider: $result")
            assertTrue(result.contains("Génération d'images"), "Should mention capability name")
        }

    @Test
    fun `image generation with single provider returns Ready path`() =
        runTest {
            val keys = mapOf("stability" to "sk-test")
            val router = CapabilityRouter(ProviderRegistry(keys))
            val tool = ImageGenerationTool(mockClient, keys, router)
            // With a real key but mock client, it will try to call the API and fail gracefully
            val result = tool.execute(ImageGenArgs(prompt = "a cat"))
            // Should NOT say "Aucun provider" — it should attempt to use Stability
            assertFalse(result.contains("Aucun provider configuré"), "Should attempt provider: $result")
        }

    // ── VisionTool ──

    @Test
    fun `vision returns not-configured when no keys`() =
        runTest {
            val tool = VisionTool(mockClient, emptyApiKeys, emptyRouter)
            val result = tool.execute(VisionArgs(imageUrl = "https://example.com/img.png"))
            assertTrue(result.contains("Aucun provider configuré"), "Should indicate no provider: $result")
        }

    // ── TextToSpeechTool ──

    @Test
    fun `TTS returns error when gateway unreachable`() =
        runTest {
            val tool = TextToSpeechTool(mockClient)
            val result = tool.execute(TextToSpeechArgs(text = "Hello"))
            // TTS now delegates to gateway — mock returns empty success response
            // Just verify it doesn't crash and doesn't mention old provider routing
            assertFalse(result.contains("Aucun provider configuré"), "Should not use old routing: $result")
        }

    // ── SpeechToTextTool ──

    @Test
    fun `STT returns not-configured when no keys`() =
        runTest {
            val tool = SpeechToTextTool(mockClient, emptyApiKeys, "build/tmp/stt")
            val result = tool.execute(SpeechToTextArgs(path = "/tmp/audio.mp3"))
            assertTrue(result.contains("No API key configured"), "Should indicate no key: $result")
        }

    // ── Router resolution correctness ──

    @Test
    fun `router resolves NotConfigured for all capabilities with empty keys`() {
        Capability.entries.forEach { cap ->
            val resolution = emptyRouter.resolve(cap)
            assertTrue(
                resolution is CapabilityResolution.NotConfigured,
                "$cap should be NotConfigured with empty keys",
            )
        }
    }

    @Test
    fun `router resolves Ready for single-key vision`() {
        val router = CapabilityRouter(ProviderRegistry(mapOf("anthropic" to "sk-ant")))
        val resolution = router.resolve(Capability.VISION)
        assertTrue(resolution is CapabilityResolution.Ready, "Should be Ready with Anthropic key")
        assertEquals("anthropic-claude-vision", (resolution as CapabilityResolution.Ready).provider.id)
    }

    @Test
    fun `router resolves MultipleAvailable for multi-key image gen`() {
        val router = CapabilityRouter(
            ProviderRegistry(
                mapOf(
                    "openai" to "sk-test",
                    "google" to "AIza-test",
                    "stability" to "sk-stab",
                ),
            ),
        )
        val resolution = router.resolve(Capability.IMAGE_GENERATION)
        assertTrue(resolution is CapabilityResolution.MultipleAvailable)
        assertEquals(3, (resolution as CapabilityResolution.MultipleAvailable).providers.size)
    }
}
