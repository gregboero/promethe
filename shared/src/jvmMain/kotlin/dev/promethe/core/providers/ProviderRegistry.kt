package dev.promethe.core.providers

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Capabilities AI supportées par Promethé.
 *
 * Chaque capability représente un type de service IA
 * pour lequel un ou plusieurs providers peuvent être configurés.
 */
enum class Capability {
    IMAGE_GENERATION,
    VISION,
    EMBEDDINGS,
    VIDEO_GENERATION,
    VIDEO_ANALYSIS,
}

/**
 * Entrée décrivant un provider IA dans le registre.
 *
 * @property id          Identifiant unique du provider (ex: "openai-dalle3").
 * @property name        Nom lisible affiché dans l'UI (ex: "DALL-E 3 (OpenAI)").
 * @property capability  La [Capability] offerte par ce provider.
 * @property credentialKeys Clés à vérifier dans la map `apiKeys` pour déterminer
 *                          si le provider est configuré (ex: `["openai"]`).
 * @property isKoogNative `true` si le provider utilise le LLMProvider natif de Koog.
 * @property priority    Priorité de sélection — plus la valeur est basse, plus le
 *                       provider est prioritaire.
 */
data class ProviderEntry(
    val id: String,
    val name: String,
    val capability: Capability,
    val credentialKeys: List<String>,
    val isKoogNative: Boolean,
    /** A known provider is visible to configuration UI but never routed until implemented. */
    val implemented: Boolean = true,
    val priority: Int = 0,
)

/**
 * Registre central de tous les providers de capabilities IA.
 *
 * Le registre connaît l'ensemble des providers disponibles et peut
 * déterminer lesquels sont effectivement configurés en fonction des
 * clés API fournies. Il expose des méthodes pour :
 * - lister les providers configurés ou non pour une capability,
 * - obtenir le provider par défaut (le plus prioritaire parmi les configurés),
 * - vérifier si au moins un provider est configuré pour une capability.
 *
 * @param apiKeys Map des clés API fournies par l'utilisateur,
 *                indexées par identifiant de service (ex: "openai", "google").
 */
class ProviderRegistry(
    private val apiKeys: Map<String, String>,
) {
    /**
     * Liste exhaustive de tous les providers supportés, toutes capabilities confondues.
     */
    private val allProviders: List<ProviderEntry> = listOf(
        // ── IMAGE_GENERATION ────────────────────────────────────────────
        ProviderEntry(
            id = "openai-dalle3",
            name = "DALL-E 3 (OpenAI)",
            capability = Capability.IMAGE_GENERATION,
            credentialKeys = listOf("openai"),
            isKoogNative = true,
            priority = 0,
        ),
        ProviderEntry(
            id = "google-imagen3",
            name = "Imagen 3 (Google)",
            capability = Capability.IMAGE_GENERATION,
            credentialKeys = listOf("google"),
            isKoogNative = true,
            priority = 1,
        ),
        ProviderEntry(
            id = "stability-ai",
            name = "Stable Diffusion (Stability AI)",
            capability = Capability.IMAGE_GENERATION,
            credentialKeys = listOf("stability"),
            isKoogNative = false,
            priority = 2,
        ),
        ProviderEntry(
            id = "replicate-flux",
            name = "Flux (Replicate)",
            capability = Capability.IMAGE_GENERATION,
            credentialKeys = listOf("replicate"),
            isKoogNative = false,
            priority = 3,
        ),
        // ── VISION ──────────────────────────────────────────────────────
        ProviderEntry(
            id = "openai-vision",
            name = "GPT-4o Vision (OpenAI)",
            capability = Capability.VISION,
            credentialKeys = listOf("openai"),
            isKoogNative = true,
            priority = 0,
        ),
        ProviderEntry(
            id = "google-gemini-vision",
            name = "Gemini Vision (Google)",
            capability = Capability.VISION,
            credentialKeys = listOf("google"),
            isKoogNative = true,
            priority = 1,
        ),
        ProviderEntry(
            id = "anthropic-claude-vision",
            name = "Claude Vision (Anthropic)",
            capability = Capability.VISION,
            credentialKeys = listOf("anthropic"),
            isKoogNative = true,
            priority = 2,
        ),
        // ── EMBEDDINGS ──────────────────────────────────────────────────
        ProviderEntry(
            id = "openai-embed",
            name = "text-embedding-3 (OpenAI)",
            capability = Capability.EMBEDDINGS,
            credentialKeys = listOf("openai"),
            isKoogNative = false,
            priority = 0,
        ),
        ProviderEntry(
            id = "google-embed",
            name = "Gemini Embeddings (Google)",
            capability = Capability.EMBEDDINGS,
            credentialKeys = listOf("google"),
            isKoogNative = false,
            priority = 1,
        ),
        ProviderEntry(
            id = "ollama-embed",
            name = "Ollama (Local)",
            capability = Capability.EMBEDDINGS,
            credentialKeys = listOf("ollama_url"),
            isKoogNative = false,
            priority = 2,
        ),
        ProviderEntry(
            id = "cohere-embed",
            name = "Cohere Embed",
            capability = Capability.EMBEDDINGS,
            credentialKeys = listOf("cohere"),
            isKoogNative = false,
            priority = 3,
        ),
        ProviderEntry(
            id = "voyage-embed",
            name = "Voyage AI",
            capability = Capability.EMBEDDINGS,
            credentialKeys = listOf("voyage"),
            isKoogNative = false,
            priority = 4,
        ),
        ProviderEntry(
            id = "mistral-embed",
            name = "Mistral Embed",
            capability = Capability.EMBEDDINGS,
            credentialKeys = listOf("mistral"),
            isKoogNative = false,
            priority = 5,
        ),
        // ── VIDEO_GENERATION ────────────────────────────────────────────
        ProviderEntry(
            id = "google-veo3",
            name = "Veo 3 (Google)",
            capability = Capability.VIDEO_GENERATION,
            credentialKeys = listOf("google"),
            isKoogNative = false,
            implemented = false,
            priority = 0,
        ),
        ProviderEntry(
            id = "replicate-luma",
            name = "Luma Ray (Replicate)",
            capability = Capability.VIDEO_GENERATION,
            credentialKeys = listOf("replicate"),
            isKoogNative = false,
            priority = 1,
        ),
        ProviderEntry(
            id = "fal-minimax",
            name = "MiniMax (fal.ai)",
            capability = Capability.VIDEO_GENERATION,
            credentialKeys = listOf("fal"),
            isKoogNative = false,
            priority = 2,
        ),
        ProviderEntry(
            id = "runway-gen3",
            name = "Gen-3 Alpha (Runway)",
            capability = Capability.VIDEO_GENERATION,
            credentialKeys = listOf("runway"),
            isKoogNative = false,
            priority = 3,
        ),
        // ── VIDEO_ANALYSIS ──────────────────────────────────────────────
        ProviderEntry(
            id = "google-gemini-video",
            name = "Gemini Video (Google)",
            capability = Capability.VIDEO_ANALYSIS,
            credentialKeys = listOf("google"),
            isKoogNative = true,
            priority = 0,
        ),
        ProviderEntry(
            id = "openai-vision-frames",
            name = "GPT-4o Frames (OpenAI)",
            capability = Capability.VIDEO_ANALYSIS,
            credentialKeys = listOf("openai"),
            isKoogNative = true,
            implemented = false,
            priority = 1,
        ),
    )

    init {
        val configured = allProviders.count { isConfigured(it) }
        logger.info {
            "ProviderRegistry initialisé : $configured/${allProviders.size} providers configurés"
        }
        Capability.entries.forEach { cap ->
            val providers = getConfigured(cap)
            if (providers.isNotEmpty()) {
                logger.debug {
                    "  ${getCapabilityLabel(cap)} : ${providers.joinToString { it.name }}"
                }
            }
        }
    }

    /**
     * Vérifie si un [provider] est configuré.
     *
     * Un provider est considéré comme configuré lorsque **toutes** ses
     * [ProviderEntry.credentialKeys] sont présentes et non-vides dans
     * la map [apiKeys].
     *
     * @param provider Le provider à vérifier.
     * @return `true` si toutes les clés requises sont présentes et non-vides.
     */
    fun isConfigured(provider: ProviderEntry): Boolean =
        provider.credentialKeys.all { key ->
            apiKeys[key]?.isNotBlank() == true
        }

    /**
     * Retourne la liste des providers configurés pour une [capability],
     * triés par priorité croissante (le plus prioritaire en premier).
     *
     * @param capability La capability recherchée.
     * @return Liste des providers configurés, ordonnée par [ProviderEntry.priority].
     */
    fun getConfigured(capability: Capability): List<ProviderEntry> =
        allProviders
            .filter { it.capability == capability && it.implemented && isConfigured(it) }
            .sortedBy { it.priority }

    /**
     * Retourne **tous** les providers enregistrés pour une [capability],
     * qu'ils soient configurés ou non, triés par priorité croissante.
     *
     * @param capability La capability recherchée.
     * @return Liste complète des providers pour cette capability.
     */
    fun getAll(capability: Capability): List<ProviderEntry> =
        allProviders
            .filter { it.capability == capability }
            .sortedBy { it.priority }

    /**
     * Retourne le provider par défaut pour une [capability] :
     * le premier provider configuré ayant la priorité la plus basse.
     *
     * @param capability La capability recherchée.
     * @return Le provider par défaut, ou `null` si aucun n'est configuré.
     */
    fun getDefault(capability: Capability): ProviderEntry? = getConfigured(capability).firstOrNull()

    /**
     * Vérifie si au moins un provider est configuré pour une [capability].
     *
     * @param capability La capability à vérifier.
     * @return `true` si au moins un provider est configuré.
     */
    fun isAnyConfigured(capability: Capability): Boolean = getConfigured(capability).isNotEmpty()

    /**
     * Retourne le libellé français d'une [capability] pour l'affichage UI.
     *
     * @param capability La capability dont on veut le libellé.
     * @return Le libellé localisé en français.
     */
    fun getCapabilityLabel(capability: Capability): String =
        when (capability) {
            Capability.IMAGE_GENERATION -> "Génération d'images"
            Capability.VISION -> "Vision"
            Capability.EMBEDDINGS -> "Embeddings"
            Capability.VIDEO_GENERATION -> "Génération vidéo"
            Capability.VIDEO_ANALYSIS -> "Analyse vidéo"
        }
}
