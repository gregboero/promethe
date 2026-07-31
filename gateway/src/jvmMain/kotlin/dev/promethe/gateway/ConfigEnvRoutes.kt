package dev.promethe.gateway

import dev.promethe.core.CredentialsStore
import dev.promethe.core.ProviderSecretRegistry
import dev.promethe.core.RuntimeConfigRegistry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ConfigEnvRoutes — manage runtime environment configuration.
 *
 * Provides:
 * - GET  /api/v1/config/env          — list all configured env vars (values masked)
 * - PUT  /api/v1/config/env/{KEY}    — set a single env var
 * - DELETE /api/v1/config/env/{KEY}  — remove a single env var
 *
 * Persisted to `~/.promethe/credentials.json` (unified config).
 */
fun Route.configEnvRoutes() {
    get("/config/env") {
        val all = CredentialsStore.load()?.toEnvMap() ?: emptyMap()
        // Mask only secret values (keys, tokens, passwords) — show first 4 chars + ***
        val sensitivePatterns = listOf("key", "secret", "token", "password", "api_key", "apikey")
        val masked = all.mapValues { (k, v) ->
            val isSensitive = sensitivePatterns.any { k.lowercase().contains(it) }
            if (isSensitive) {
                if (v.length > 6) v.take(4) + "***" else "***"
            } else {
                v // Non-sensitive values (provider names, models, voices) returned in cleartext
            }
        }
        call.respond(masked)
    }

    put("/config/env/{key}") {
        val key = call.parameters["key"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing key"))
        val body = call.receive<EnvSetRequest>()
        // Reject masked values (e.g. "tvly***") to prevent overwriting real keys
        if (body.value.contains("***")) {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Masked value rejected — send the real value"))
        }
        if (!setCredentialByEnvKey(key, body.value)) {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown configuration key: $key"))
        }
        call.respond(mapOf("status" to "set", "key" to key))
    }

    delete("/config/env/{key}") {
        val key = call.parameters["key"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing key"))
        if (!setCredentialByEnvKey(key, "")) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown configuration key: $key"))
        }
        call.respond(mapOf("status" to "removed", "key" to key))
    }
}

@Serializable
private data class EnvSetRequest(
    val value: String,
)

/**
 * Map an env-style key to the typed Credentials field and persist.
 */
private fun setCredentialByEnvKey(
    key: String,
    value: String,
): Boolean {
    ProviderSecretRegistry.definitionForEnvKey(key)?.let { definition ->
        CredentialsStore.update { credentials ->
            val secrets =
                if (value.isBlank()) {
                    credentials.providerSecrets - definition.id
                } else {
                    credentials.providerSecrets + (definition.id to value)
                }
            credentials.copy(providerSecrets = secrets)
        }
        return true
    }
    val canonicalKey =
        RuntimeConfigRegistry.canonicalKey(key) ?: run {
            logger.warn { "Unknown env key '$key'" }
            return false
        }
    CredentialsStore.update { credentials ->
        val runtimeConfig =
            if (value.isBlank()) {
                credentials.runtimeConfig - canonicalKey
            } else {
                credentials.runtimeConfig + (canonicalKey to value)
            }
        when (canonicalKey) {
            // Web Search
            "TAVILY_API_KEY" -> {
                credentials.copy(tavilyApiKey = value)
            }

            "SEARXNG_URL" -> {
                credentials.copy(searxngUrl = value)
            }

            // Browser
            "BROWSER_BACKEND" -> {
                credentials.copy(browserBackend = value)
            }

            // Voice
            "voice_s2s_provider" -> {
                credentials.copy(voiceS2sProvider = value)
            }

            "voice_s2s_model" -> {
                credentials.copy(voiceS2sModel = value)
            }

            "voice_s2s_voice" -> {
                credentials.copy(voiceS2sVoice = value)
            }

            "voice_s2s_enabled" -> {
                credentials.copy(voiceS2sEnabled = value.lowercase() == "true")
            }

            "voice_tts_provider" -> {
                credentials.copy(voiceTtsProvider = value)
            }

            "voice_tts_model" -> {
                credentials.copy(voiceTtsModel = value)
            }

            "voice_tts_voice" -> {
                credentials.copy(voiceTtsVoice = value)
            }

            "voice_tts_enabled" -> {
                credentials.copy(voiceTtsEnabled = value.lowercase() == "true")
            }

            "voice_stt_provider" -> {
                credentials.copy(voiceSttProvider = value)
            }

            "voice_stt_model" -> {
                credentials.copy(voiceSttModel = value)
            }

            "voice_stt_enabled" -> {
                credentials.copy(voiceSttEnabled = value.lowercase() == "true")
            }

            "voice_translate_enabled" -> {
                credentials.copy(voiceTranslateEnabled = value.lowercase() == "true")
            }

            "voice_translate_target_lang" -> {
                credentials.copy(voiceTranslateTargetLang = value)
            }

            else -> {
                credentials
            }
        }.copy(runtimeConfig = runtimeConfig)
    }
    return true
}

/**
 * @deprecated Use CredentialsStore directly. Kept temporarily for backward compatibility.
 */
@Deprecated("Use CredentialsStore.load()?.toEnvMap() instead", ReplaceWith("CredentialsStore"))
object EnvConfigStore {
    fun loadAll(): Map<String, String> = CredentialsStore.load()?.toEnvMap() ?: emptyMap()

    fun get(key: String): String? = loadAll()[key]

    fun set(
        key: String,
        value: String,
    ): Boolean = setCredentialByEnvKey(key, value)

    fun remove(key: String): Boolean = setCredentialByEnvKey(key, "")
}
