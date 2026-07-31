package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.CredentialsStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

private class InvalidConfigValueException : IllegalArgumentException()

// ══════════════════════════════════════════════════════════════
// Outils de configuration pour l'agent :
//   - ConfigGetTool  → lire la configuration courante
//   - ConfigSetTool  → modifier une valeur de configuration
// ══════════════════════════════════════════════════════════════

// ── Clés sensibles qui ne doivent JAMAIS être exposées ──────

private val SENSITIVE_KEYS = setOf(
    "apiKey",
    "llmApiKey",
    "ragEmbeddingApiKey",
    "ragVectorStoreApiKey",
    "replicateApiToken",
    "falKey",
    "runwayApiKey",
    "stabilityApiKey",
    "elevenlabsApiKey",
    "deepgramApiKey",
    "googleAiKey",
)

// ── Clés autorisées en écriture ─────────────────────────────

private val WRITABLE_KEYS = setOf(
    "temperature",
    "maxTokens",
    "maxIterations",
    "llmModel",
    "llmProvider",
    "ollamaUrl",
    "gatewayUrl",
    "memoryEnabled",
    "memoryProvider",
    "theme",
    "systemPrompt",
    "ragEnabled",
    // AI Media Providers
    "replicateApiToken",
    "falKey",
    "runwayApiKey",
    "stabilityApiKey",
    "elevenlabsApiKey",
    "deepgramApiKey",
    "googleAiKey",
)

// ══════════════════════════════════════════════════════════════
// ConfigGetTool
// ══════════════════════════════════════════════════════════════

@Serializable
data class ConfigGetArgs(
    @property:LLMDescription(
        "Clé de configuration à lire. Vide = afficher toute la configuration visible (les clés sensibles sont masquées).",
    )
    val key: String = "",
)

/**
 * Outil de lecture de la configuration de l'agent.
 *
 * Permet à l'agent de consulter ses propres paramètres de configuration.
 * Les champs sensibles (clés API, mots de passe) ne sont jamais exposés.
 */
class ConfigGetTool :
    SimpleTool<ConfigGetArgs>(
        argsType = typeToken<ConfigGetArgs>(),
        name = "config_get",
        description = "Read agent configuration. Without a key, shows all visible settings. " +
            "Sensitive keys (apiKey, llmApiKey, passwords) are never revealed.",
    ) {
    override suspend fun execute(args: ConfigGetArgs): String {
        return try {
            val creds = CredentialsStore.loadOrCreate()

            if (args.key.isBlank()) {
                // Afficher toute la configuration visible
                logger.debug { "config_get: lecture de toute la configuration" }
                buildVisibleConfig(creds)
            } else {
                // Lecture d'une clé spécifique
                val key = args.key.trim()
                if (key in SENSITIVE_KEYS) {
                    logger.warn { "config_get: tentative de lecture de la clé sensible '$key'" }
                    return "❌ La clé '$key' est sensible et ne peut pas être lue par l'agent."
                }
                val value = getConfigValue(creds, key)
                    ?: return "❌ Clé de configuration inconnue : '$key'"
                logger.debug { "config_get: lecture de '$key'" }
                "$key = $value"
            }
        } catch (e: Exception) {
            logger.error(e) { "config_get: erreur lors de la lecture de la configuration" }
            "[ERREUR] Impossible de lire la configuration : ${e.message}"
        }
    }

    /**
     * Construit une représentation lisible de toute la configuration,
     * en masquant les champs sensibles.
     */
    private fun buildVisibleConfig(creds: CredentialsStore.Credentials): String {
        val entries = getAllConfigEntries(creds)
        val sb = StringBuilder("📋 Configuration de l'agent :\n")
        for ((key, value) in entries) {
            if (key in SENSITIVE_KEYS) continue
            sb.appendLine("  $key = $value")
        }
        return sb.toString()
    }
}

// ══════════════════════════════════════════════════════════════
// ConfigSetTool
// ══════════════════════════════════════════════════════════════

@Serializable
data class ConfigSetArgs(
    @property:LLMDescription("Clé de configuration à modifier (ex: temperature, maxTokens, llmModel, theme…).")
    val key: String,
    @property:LLMDescription("Nouvelle valeur à affecter à la clé.")
    val value: String,
)

/**
 * Outil de modification de la configuration de l'agent.
 *
 * Permet à l'agent de mettre à jour un paramètre de configuration.
 * Seules les clés autorisées peuvent être modifiées ; les clés sensibles
 * (apiKey, llmApiKey, mots de passe) sont interdites en écriture.
 *
 * @param onConfigChanged Optional callback invoked after each successful change.
 *   Receives the key and new value. Used to hot-reload live components (e.g.,
 *   [KoogLlmAdapter.updateActiveModel]) without restarting the server.
 */
class ConfigSetTool(
    private val onConfigChanged: ((key: String, value: String) -> Unit)? = null,
) : SimpleTool<ConfigSetArgs>(
        argsType = typeToken<ConfigSetArgs>(),
        name = "config_set",
        description = "Update an agent configuration value. " +
            "Allowed keys: temperature, maxTokens, maxIterations, llmModel, llmProvider, " +
            "ollamaUrl, gatewayUrl, memoryEnabled, memoryProvider, theme, systemPrompt, ragEnabled, " +
            "replicateApiToken, falKey, runwayApiKey, stabilityApiKey, elevenlabsApiKey, deepgramApiKey, googleAiKey. " +
            "Sensitive keys are masked in reads but configurable in writes for media providers.",
    ) {
    override suspend fun execute(args: ConfigSetArgs): String {
        val key = args.key.trim()
        val value = args.value.trim()

        // Vérification de sécurité : clés interdites
        if (key in SENSITIVE_KEYS) {
            logger.warn { "config_set: tentative de modification de la clé sensible '$key'" }
            return "❌ La clé '$key' est sensible et ne peut pas être modifiée par l'agent."
        }

        // Vérification : clé autorisée
        if (key !in WRITABLE_KEYS) {
            logger.warn { "config_set: clé non autorisée '$key'" }
            return "❌ La clé '$key' n'est pas modifiable. Clés autorisées : ${WRITABLE_KEYS.sorted().joinToString(", ")}"
        }

        return try {
            CredentialsStore.update { credentials ->
                applyConfigUpdate(credentials, key, value)
                    ?: throw InvalidConfigValueException()
            }
            // Notify live components of the change (e.g., hot-reload KoogLlmAdapter)
            onConfigChanged?.invoke(key, value)
            logger.info { "config_set: '$key' mis à jour avec succès" }
            "✅ Configuration mise à jour : $key = $value"
        } catch (_: InvalidConfigValueException) {
            "❌ Valeur invalide pour '$key' : '$value'"
        } catch (e: Exception) {
            logger.error(e) { "config_set: erreur lors de la modification de '$key'" }
            "[ERREUR] Impossible de modifier la configuration : ${e.message}"
        }
    }

    /**
     * Applique une mise à jour de configuration via `copy()`.
     * Retourne `null` si la valeur est invalide pour le type attendu.
     */
    private fun applyConfigUpdate(
        creds: CredentialsStore.Credentials,
        key: String,
        value: String,
    ): CredentialsStore.Credentials? {
        return try {
            when (key) {
                "temperature" -> {
                    val d = value.toDoubleOrNull() ?: return null
                    if (d < 0.0 || d > 2.0) return null
                    creds.copy(temperature = d)
                }

                "maxTokens" -> {
                    val i = value.toIntOrNull() ?: return null
                    if (i < 1) return null
                    creds.copy(maxTokens = i)
                }

                "maxIterations" -> {
                    val i = value.toIntOrNull() ?: return null
                    if (i < 1) return null
                    creds.copy(maxIterations = i)
                }

                "llmModel" -> {
                    creds.copy(llmModel = value)
                }

                "llmProvider" -> {
                    creds.copy(llmProvider = value)
                }

                "ollamaUrl" -> {
                    creds.copy(ollamaUrl = value)
                }

                "gatewayUrl" -> {
                    creds.copy(gatewayUrl = value)
                }

                "memoryEnabled" -> {
                    val b = value.toBooleanStrictOrNull() ?: return null
                    creds.copy(memoryEnabled = b)
                }

                "memoryProvider" -> {
                    creds.copy(memoryProvider = value)
                }

                "theme" -> {
                    creds.copy(theme = value)
                }

                "systemPrompt" -> {
                    creds.copy(systemPrompt = value)
                }

                "ragEnabled" -> {
                    val b = value.toBooleanStrictOrNull() ?: return null
                    creds.copy(ragEnabled = b)
                }

                // AI Media Providers
                "replicateApiToken" -> {
                    creds.copy(replicateApiToken = value)
                }

                "falKey" -> {
                    creds.copy(falKey = value)
                }

                "runwayApiKey" -> {
                    creds.copy(runwayApiKey = value)
                }

                "stabilityApiKey" -> {
                    creds.copy(stabilityApiKey = value)
                }

                "elevenlabsApiKey" -> {
                    creds.copy(elevenlabsApiKey = value)
                }

                "deepgramApiKey" -> {
                    creds.copy(deepgramApiKey = value)
                }

                "googleAiKey" -> {
                    creds.copy(googleAiKey = value)
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "config_set: erreur de conversion pour '$key' = '$value'" }
            null
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Fonctions utilitaires partagées
// ══════════════════════════════════════════════════════════════

/**
 * Retourne toutes les entrées de configuration sous forme de paires clé-valeur.
 */
private fun getAllConfigEntries(creds: CredentialsStore.Credentials): List<Pair<String, Any?>> =
    listOf(
        "apiKey" to creds.apiKey,
        "llmProvider" to creds.llmProvider,
        "llmApiKey" to creds.llmApiKey,
        "llmModel" to creds.llmModel,
        "gatewayUrl" to creds.gatewayUrl,
        "ollamaUrl" to creds.ollamaUrl,
        "temperature" to creds.temperature,
        "maxTokens" to creds.maxTokens,
        "maxIterations" to creds.maxIterations,
        "memoryEnabled" to creds.memoryEnabled,
        "memoryProvider" to creds.memoryProvider,
        "systemPrompt" to creds.systemPrompt,
        "theme" to creds.theme,
        "ragEnabled" to creds.ragEnabled,
        "ragEmbeddingProvider" to creds.ragEmbeddingProvider,
        "ragEmbeddingModel" to creds.ragEmbeddingModel,
        "ragEmbeddingBaseUrl" to creds.ragEmbeddingBaseUrl,
        "ragEmbeddingApiKey" to creds.ragEmbeddingApiKey,
        "ragEmbeddingDimensions" to creds.ragEmbeddingDimensions,
        "ragVectorStoreType" to creds.ragVectorStoreType,
        "ragVectorStoreUrl" to creds.ragVectorStoreUrl,
        "ragVectorStoreApiKey" to creds.ragVectorStoreApiKey,
        "ragVectorStorePath" to creds.ragVectorStorePath,
        "ragChunkSize" to creds.ragChunkSize,
        "ragChunkOverlap" to creds.ragChunkOverlap,
        // AI Media Providers
        "replicateApiToken" to creds.replicateApiToken,
        "falKey" to creds.falKey,
        "runwayApiKey" to creds.runwayApiKey,
        "stabilityApiKey" to creds.stabilityApiKey,
        "elevenlabsApiKey" to creds.elevenlabsApiKey,
        "deepgramApiKey" to creds.deepgramApiKey,
        "googleAiKey" to creds.googleAiKey,
    )

/**
 * Retourne la valeur d'une clé spécifique, ou `null` si la clé n'existe pas.
 */
private fun getConfigValue(
    creds: CredentialsStore.Credentials,
    key: String,
): Any? =
    when (key) {
        "llmProvider" -> creds.llmProvider

        "llmModel" -> creds.llmModel

        "gatewayUrl" -> creds.gatewayUrl

        "ollamaUrl" -> creds.ollamaUrl

        "temperature" -> creds.temperature

        "maxTokens" -> creds.maxTokens

        "maxIterations" -> creds.maxIterations

        "memoryEnabled" -> creds.memoryEnabled

        "memoryProvider" -> creds.memoryProvider

        "systemPrompt" -> creds.systemPrompt

        "theme" -> creds.theme

        "ragEnabled" -> creds.ragEnabled

        "ragEmbeddingProvider" -> creds.ragEmbeddingProvider

        "ragEmbeddingModel" -> creds.ragEmbeddingModel

        "ragEmbeddingBaseUrl" -> creds.ragEmbeddingBaseUrl

        "ragEmbeddingDimensions" -> creds.ragEmbeddingDimensions

        "ragVectorStoreType" -> creds.ragVectorStoreType

        "ragVectorStoreUrl" -> creds.ragVectorStoreUrl

        "ragVectorStorePath" -> creds.ragVectorStorePath

        "ragChunkSize" -> creds.ragChunkSize

        "ragChunkOverlap" -> creds.ragChunkOverlap

        // AI Media Providers (return masked)
        "replicateApiToken" -> if (creds.replicateApiToken.isNotBlank()) "***configured***" else ""

        "falKey" -> if (creds.falKey.isNotBlank()) "***configured***" else ""

        "runwayApiKey" -> if (creds.runwayApiKey.isNotBlank()) "***configured***" else ""

        "stabilityApiKey" -> if (creds.stabilityApiKey.isNotBlank()) "***configured***" else ""

        "elevenlabsApiKey" -> if (creds.elevenlabsApiKey.isNotBlank()) "***configured***" else ""

        "deepgramApiKey" -> if (creds.deepgramApiKey.isNotBlank()) "***configured***" else ""

        "googleAiKey" -> if (creds.googleAiKey.isNotBlank()) "***configured***" else ""

        else -> null
    }
