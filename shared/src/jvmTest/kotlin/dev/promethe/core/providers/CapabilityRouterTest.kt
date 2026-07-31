package dev.promethe.core.providers

import kotlin.test.*

/**
 * Tests for [CapabilityRouter] — validates resolution logic
 * (NotConfigured, Ready, MultipleAvailable) and message formatting.
 */
class CapabilityRouterTest {
    @Test
    fun `resolve returns NotConfigured when no keys`() {
        val router = CapabilityRouter(ProviderRegistry(emptyMap()))
        val resolution = router.resolve(Capability.IMAGE_GENERATION)

        assertTrue(resolution is CapabilityResolution.NotConfigured)
        val nc = resolution as CapabilityResolution.NotConfigured
        assertEquals(Capability.IMAGE_GENERATION, nc.capability)
        assertTrue(nc.message.contains("Aucun provider configuré"))
        assertTrue(nc.availableProviders.isNotEmpty(), "Should list available providers even when none configured")
    }

    @Test
    fun `resolve returns Ready when single provider configured`() {
        val router = CapabilityRouter(ProviderRegistry(mapOf("stability" to "sk-test")))
        val resolution = router.resolve(Capability.IMAGE_GENERATION)

        assertTrue(resolution is CapabilityResolution.Ready)
        val ready = resolution as CapabilityResolution.Ready
        assertEquals("stability-ai", ready.provider.id)
        assertTrue(ready.confirmMessage.contains("Stable Diffusion"))
    }

    @Test
    fun `resolve returns MultipleAvailable when multiple providers configured`() {
        val router = CapabilityRouter(
            ProviderRegistry(
                mapOf(
                    "openai" to "sk-test",
                    "google" to "AIza-test",
                ),
            ),
        )
        val resolution = router.resolve(Capability.IMAGE_GENERATION)

        assertTrue(resolution is CapabilityResolution.MultipleAvailable)
        val multi = resolution as CapabilityResolution.MultipleAvailable
        assertEquals(2, multi.providers.size)
        assertTrue(multi.choiceMessage.contains("1."))
        assertTrue(multi.choiceMessage.contains("2."))
        assertTrue(multi.choiceMessage.contains("Lequel veux-tu utiliser"))
    }

    @Test
    fun `notConfigured message lists available providers and setup instructions`() {
        val router = CapabilityRouter(ProviderRegistry(emptyMap()))
        val resolution = router.resolve(Capability.VISION) as CapabilityResolution.NotConfigured

        assertTrue(resolution.message.contains("config_set"), "Should mention config_set tool")
        assertTrue(resolution.message.contains("Settings"), "Should mention Settings page")
    }

    @Test
    fun `each capability type can be resolved`() {
        val router = CapabilityRouter(ProviderRegistry(emptyMap()))

        Capability.entries.forEach { cap ->
            val resolution = router.resolve(cap)
            assertTrue(
                resolution is CapabilityResolution.NotConfigured,
                "Empty keys should give NotConfigured for $cap",
            )
        }
    }

    @Test
    fun `Ready provider priority order is respected`() {
        // Only Google → google-imagen3 should be the default (priority 1, but only one configured)
        val router = CapabilityRouter(ProviderRegistry(mapOf("google" to "AIza-test")))
        val resolution = router.resolve(Capability.IMAGE_GENERATION)

        assertTrue(resolution is CapabilityResolution.Ready)
        assertEquals("google-imagen3", (resolution as CapabilityResolution.Ready).provider.id)
    }

    @Test
    fun `MultipleAvailable providers sorted by priority`() {
        val router = CapabilityRouter(
            ProviderRegistry(
                mapOf(
                    "openai" to "sk-test",
                    "google" to "AIza-test",
                    "stability" to "sk-stab",
                ),
            ),
        )
        val resolution = router.resolve(Capability.IMAGE_GENERATION) as CapabilityResolution.MultipleAvailable

        assertEquals("openai-dalle3", resolution.providers[0].id, "OpenAI should be first (priority 0)")
        assertEquals("google-imagen3", resolution.providers[1].id, "Google should be second (priority 1)")
        assertEquals("stability-ai", resolution.providers[2].id, "Stability should be third (priority 2)")
    }
}
