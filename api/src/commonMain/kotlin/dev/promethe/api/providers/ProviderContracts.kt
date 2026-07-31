package dev.promethe.api.providers

import kotlinx.serialization.Serializable

/** Capabilities advertised by a model in the public provider catalog. */
@Serializable
enum class ModelCapability {
    CHAT,
    VISION,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    IMAGE_INPUT,
    IMAGE_OUTPUT,
    EMBEDDINGS,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    REASONING,
}

/** Operational lifecycle of a provider or model. */
@Serializable
enum class ModelLifecycle {
    ACTIVE,
    PREVIEW,
    DEPRECATED,
}

/** Evidence level for the catalog entry; this is metadata, not a runtime health check. */
@Serializable
enum class CertificationStatus {
    CERTIFIED,
    BETA,
    EXPERIMENTAL,
    UNVERIFIED,
}

/** Public availability of provider metadata and model access. */
@Serializable
enum class ProviderAvailability {
    AVAILABLE,
    CONFIGURATION_REQUIRED,
    UNAVAILABLE,
    STALE,
}

/** Public maturity level for catalog metadata. */
@Serializable
enum class ModelMaturity {
    STABLE,
    PREVIEW,
    BETA,
    EXPERIMENTAL,
}

/** Public, secret-free description of an LLM provider. */
@Serializable
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val capabilities: List<ModelCapability>,
    val lifecycle: ModelLifecycle = ModelLifecycle.ACTIVE,
    val certificationStatus: CertificationStatus = CertificationStatus.UNVERIFIED,
    val availability: ProviderAvailability = ProviderAvailability.UNAVAILABLE,
    val maturity: ModelMaturity = ModelMaturity.STABLE,
)

/** Public, secret-free description of a model exposed by a provider. */
@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val providerId: String,
    val capabilities: List<ModelCapability>,
    val lifecycle: ModelLifecycle = ModelLifecycle.ACTIVE,
    val certificationStatus: CertificationStatus = CertificationStatus.UNVERIFIED,
    val availability: ProviderAvailability = ProviderAvailability.UNAVAILABLE,
    val maturity: ModelMaturity = ModelMaturity.STABLE,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportedParameters: List<String> = emptyList(),
    val modalities: List<String> = emptyList(),
    val expiresAt: String? = null,
)
