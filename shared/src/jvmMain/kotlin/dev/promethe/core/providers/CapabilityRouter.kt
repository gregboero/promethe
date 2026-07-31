package dev.promethe.core.providers

import dev.promethe.core.ToolApprovalGate
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

// ══════════════════════════════════════════════════════════════
// CapabilityRouter — résolution de providers pour une capability
// ══════════════════════════════════════════════════════════════

/**
 * Résultat de la résolution d'un provider pour une [Capability].
 *
 * Trois cas possibles :
 * - [Ready] : un seul provider configuré, prêt à l'emploi.
 * - [MultipleAvailable] : plusieurs providers configurés, l'utilisateur doit choisir.
 * - [NotConfigured] : aucun provider configuré, l'utilisateur doit en configurer un.
 */
sealed class CapabilityResolution {
    /**
     * Un seul provider configuré — prêt à être utilisé après confirmation.
     *
     * @property provider        Le [ProviderEntry] sélectionné.
     * @property confirmMessage  Message de confirmation à afficher à l'utilisateur.
     */
    data class Ready(
        val provider: ProviderEntry,
        val confirmMessage: String,
    ) : CapabilityResolution()

    /**
     * Plusieurs providers configurés — l'utilisateur doit en choisir un.
     *
     * @property providers     Liste des [ProviderEntry] configurés, triés par priorité.
     * @property choiceMessage Message numéroté listant les options disponibles.
     */
    data class MultipleAvailable(
        val providers: List<ProviderEntry>,
        val choiceMessage: String,
    ) : CapabilityResolution()

    /**
     * Aucun provider n'est configuré pour cette capability.
     *
     * @property capability         La [Capability] demandée.
     * @property availableProviders Liste de tous les providers disponibles (non configurés).
     * @property message            Message d'aide indiquant comment configurer un provider.
     */
    data class NotConfigured(
        val capability: Capability,
        val availableProviders: List<ProviderEntry>,
        val message: String,
    ) : CapabilityResolution()
}

/**
 * Routeur de capabilities — détermine quel provider utiliser pour une [Capability] donnée.
 *
 * Le routeur interroge le [ProviderRegistry] pour identifier les providers configurés
 * et produit un [CapabilityResolution] qui guide l'agent ou l'UI vers l'action appropriée :
 *
 * - **Aucun provider configuré** → message d'aide avec la liste des providers disponibles
 *   et les clés à renseigner (via `config_set` ou Settings > AI Media).
 * - **Un seul provider** → message de confirmation avant exécution.
 * - **Plusieurs providers** → message de choix numéroté.
 *
 * Les messages sont en français pour rester cohérents avec le style de l'agent
 * (voir [dev.promethe.core.tools.builtin.ConfigTools]).
 *
 * @param registry Le [ProviderRegistry] contenant l'état des providers et des clés API.
 * @see ToolApprovalGate pour le mécanisme d'approbation human-in-the-loop complémentaire.
 */
class CapabilityRouter(
    private val registry: ProviderRegistry,
) {
    /**
     * Résout le provider à utiliser pour la [capability] demandée.
     *
     * La résolution suit cette logique :
     * 1. Récupère les providers configurés via [ProviderRegistry.getConfigured].
     * 2. Si aucun n'est configuré → [CapabilityResolution.NotConfigured].
     * 3. Si exactement un est configuré → [CapabilityResolution.Ready].
     * 4. Si plusieurs sont configurés → [CapabilityResolution.MultipleAvailable].
     *
     * @param capability La capability IA demandée (ex: [Capability.IMAGE_GENERATION]).
     * @return Le [CapabilityResolution] correspondant à l'état courant.
     */
    fun resolve(capability: Capability): CapabilityResolution {
        val configured = registry.getConfigured(capability)
        val label = registry.getCapabilityLabel(capability)

        return when {
            configured.isEmpty() -> {
                val allProviders = registry.getAll(capability)
                val message = buildNotConfiguredMessage(capability, allProviders)
                logger.info { "Aucun provider configuré pour $label" }
                CapabilityResolution.NotConfigured(
                    capability = capability,
                    availableProviders = allProviders,
                    message = message,
                )
            }

            configured.size == 1 -> {
                val provider = configured.first()
                val message = buildConfirmMessage(capability, provider)
                logger.debug { "Provider unique pour $label : ${provider.name}" }
                CapabilityResolution.Ready(
                    provider = provider,
                    confirmMessage = message,
                )
            }

            else -> {
                val message = buildChoiceMessage(capability, configured)
                logger.debug {
                    "Plusieurs providers pour $label : ${configured.joinToString { it.name }}"
                }
                CapabilityResolution.MultipleAvailable(
                    providers = configured,
                    choiceMessage = message,
                )
            }
        }
    }

    /**
     * Construit le message d'aide lorsqu'aucun provider n'est configuré.
     *
     * Le message liste tous les providers disponibles avec leurs clés de
     * credential respectives, et suggère d'utiliser `config_set` ou l'écran
     * Settings > AI Media pour la configuration.
     *
     * Exemple de sortie :
     * ```
     * Aucun provider configuré pour Génération d'images.
     * Tu peux en configurer un :
     *
     * • DALL-E 3 (OpenAI) — clé : openai
     * • Imagen 3 (Google) — clé : google
     *
     * Utilise config_set ou va dans Settings > AI Media.
     * ```
     *
     * @param capability La capability non configurée.
     * @param providers  Liste de tous les providers disponibles pour cette capability.
     * @return Le message formaté en français.
     */
    fun buildNotConfiguredMessage(
        capability: Capability,
        providers: List<ProviderEntry>,
    ): String {
        val label = registry.getCapabilityLabel(capability)
        val sb = StringBuilder()

        sb.appendLine("Aucun provider configuré pour $label.")
        sb.appendLine("Tu peux en configurer un :")
        sb.appendLine()

        providers.forEach { provider ->
            val keys = provider.credentialKeys.joinToString(", ")
            val status = if (provider.implemented) "clé : $keys" else "indisponible dans cette version"
            sb.appendLine("• ${provider.name} — $status")
        }

        sb.appendLine()
        sb.append("Utilise config_set ou va dans Settings > AI Media.")

        return sb.toString()
    }

    /**
     * Construit le message de confirmation lorsqu'un seul provider est disponible.
     *
     * Exemple : « Pour la génération d'images, j'ai DALL-E 3 (OpenAI) de configuré. Je l'utilise ? »
     *
     * @param capability La capability demandée.
     * @param provider   Le provider unique configuré.
     * @return Le message de confirmation en français.
     */
    fun buildConfirmMessage(
        capability: Capability,
        provider: ProviderEntry,
    ): String {
        val label = registry.getCapabilityLabel(capability).lowercase()
        return "Pour la $label, j'ai ${provider.name} de configuré. Je l'utilise ?"
    }

    /**
     * Construit le message de choix lorsque plusieurs providers sont configurés.
     *
     * Le message propose une liste numérotée afin que l'utilisateur puisse
     * sélectionner le provider souhaité.
     *
     * Exemple :
     * ```
     * Plusieurs providers disponibles pour la génération d'images :
     *
     * 1. DALL-E 3 (OpenAI)
     * 2. Imagen 3 (Google)
     *
     * Lequel veux-tu utiliser ?
     * ```
     *
     * @param capability La capability demandée.
     * @param providers  Liste des providers configurés, triés par priorité.
     * @return Le message de choix numéroté en français.
     */
    fun buildChoiceMessage(
        capability: Capability,
        providers: List<ProviderEntry>,
    ): String {
        val label = registry.getCapabilityLabel(capability).lowercase()
        val sb = StringBuilder()

        sb.appendLine("Plusieurs providers disponibles pour la $label :")
        sb.appendLine()

        providers.forEachIndexed { index, provider ->
            sb.appendLine("${index + 1}. ${provider.name}")
        }

        sb.appendLine()
        sb.append("Lequel veux-tu utiliser ?")

        return sb.toString()
    }
}
