package dev.promethe.api.providers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderContractsSerializationTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `provider and model descriptors round trip`() {
        val provider = ProviderDescriptor(
            id = "example",
            displayName = "Example Provider",
            description = "A public catalog entry",
            capabilities = listOf(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
            certificationStatus = CertificationStatus.BETA,
            availability = ProviderAvailability.AVAILABLE,
            maturity = ModelMaturity.PREVIEW,
        )
        val model = ModelDescriptor(
            id = "example-chat",
            displayName = "Example Chat",
            providerId = provider.id,
            capabilities = provider.capabilities,
            lifecycle = ModelLifecycle.PREVIEW,
            certificationStatus = CertificationStatus.EXPERIMENTAL,
            availability = ProviderAvailability.AVAILABLE,
            maturity = ModelMaturity.BETA,
            contextWindowTokens = 128_000,
            maxOutputTokens = 8_192,
            supportedParameters = listOf("tools", "response_format"),
            modalities = listOf("text", "image"),
            expiresAt = "2027-01-01T00:00:00Z",
        )

        assertEquals(provider, json.decodeFromString<ProviderDescriptor>(json.encodeToString(provider)))
        assertEquals(model, json.decodeFromString<ModelDescriptor>(json.encodeToString(model)))
    }

    @Test
    fun `serialization contains metadata but no credential fields`() {
        val encoded = json.encodeToString(
            ProviderDescriptor(
                id = "safe-provider",
                displayName = "Safe Provider",
                description = "No credentials here",
                capabilities = listOf(ModelCapability.CHAT),
            ),
        )

        assertTrue(encoded.contains("safe-provider"))
        assertTrue(!encoded.contains("apiKey", ignoreCase = true))
        assertTrue(!encoded.contains("secret", ignoreCase = true))
    }
}
