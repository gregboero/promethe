package dev.promethe.core.providers

import dev.promethe.core.LiveProviderKeys
import kotlin.test.*

/**
 * Tests for [ProviderRegistry] — validates provider catalogue,
 * key-based configuration detection, and capability filtering.
 */
class ProviderRegistryTest {
    @Test
    fun `empty keys means nothing configured`() {
        val registry = ProviderRegistry(emptyMap())
        val configured = registry.getConfigured(Capability.IMAGE_GENERATION)
        assertTrue(configured.isEmpty(), "No providers should be configured without keys")
    }

    @Test
    fun `openai key enables multiple capabilities`() {
        val registry = ProviderRegistry(mapOf("openai" to "sk-test"))
        val imageProviders = registry.getConfigured(Capability.IMAGE_GENERATION)
        val visionProviders = registry.getConfigured(Capability.VISION)

        val embedProviders = registry.getConfigured(Capability.EMBEDDINGS)

        assertTrue(imageProviders.any { it.id == "openai-dalle3" }, "OpenAI should provide DALL-E 3")
        assertTrue(visionProviders.any { it.id == "openai-vision" }, "OpenAI should provide vision")

        assertTrue(embedProviders.any { it.id == "openai-embed" }, "OpenAI should provide embeddings")
    }

    @Test
    fun `google key enables google providers`() {
        val registry = ProviderRegistry(mapOf("google" to "AIza-test"))
        val imageProviders = registry.getConfigured(Capability.IMAGE_GENERATION)
        val visionProviders = registry.getConfigured(Capability.VISION)
        val videoGen = registry.getConfigured(Capability.VIDEO_GENERATION)
        val videoAnalysis = registry.getConfigured(Capability.VIDEO_ANALYSIS)

        assertTrue(imageProviders.any { it.id == "google-imagen3" }, "Google should provide Imagen 3")
        assertTrue(visionProviders.any { it.id == "google-gemini-vision" }, "Google should provide Gemini vision")
        assertFalse(videoGen.any { it.id == "google-veo3" }, "Known but unimplemented Veo 3 must not be routed")
        assertTrue(videoAnalysis.any { it.id == "google-gemini-video" }, "Google should provide video analysis")
    }

    @Test
    fun `isConfigured returns true only when key present`() {
        val registry = ProviderRegistry(mapOf("stability" to "sk-stab"))
        val allProviders = registry.getConfigured(Capability.IMAGE_GENERATION)

        assertTrue(allProviders.any { it.id == "stability-ai" }, "Stability should be configured")
        assertFalse(allProviders.any { it.id == "openai-dalle3" }, "OpenAI should not be configured")
    }

    @Test
    fun `getDefault returns first configured provider`() {
        val registry = ProviderRegistry(mapOf("anthropic" to "sk-ant"))
        val default = registry.getDefault(Capability.VISION)
        assertNotNull(default, "Should have a default vision provider")
        assertEquals("anthropic-claude-vision", default.id)
    }

    @Test
    fun `getDefault returns null when nothing configured`() {
        val registry = ProviderRegistry(emptyMap())
        val default = registry.getDefault(Capability.IMAGE_GENERATION)
        assertNull(default, "Should return null when no providers configured")
    }

    @Test
    fun `multiple keys enable multiple providers per capability`() {
        val registry = ProviderRegistry(
            mapOf(
                "openai" to "sk-test",
                "google" to "AIza-test",
                "stability" to "sk-stab",
            ),
        )
        val imageProviders = registry.getConfigured(Capability.IMAGE_GENERATION)
        assertEquals(3, imageProviders.size, "Should have 3 image generation providers")
    }

    @Test
    fun `getCapabilityLabel returns human-readable label`() {
        val registry = ProviderRegistry(emptyMap())
        val label = registry.getCapabilityLabel(Capability.IMAGE_GENERATION)
        assertTrue(label.isNotBlank(), "Label should not be blank")
    }

    @Test
    fun `live provider keys update an existing capability registry`() {
        try {
            LiveProviderKeys.replace(emptyMap())
            val registry = ProviderRegistry(LiveProviderKeys)
            assertFalse(registry.isAnyConfigured(Capability.IMAGE_GENERATION))

            LiveProviderKeys.replace(mapOf("stability" to "test-key"))
            assertTrue(registry.getConfigured(Capability.IMAGE_GENERATION).any { it.id == "stability-ai" })
        } finally {
            LiveProviderKeys.replace(emptyMap())
        }
    }
}
