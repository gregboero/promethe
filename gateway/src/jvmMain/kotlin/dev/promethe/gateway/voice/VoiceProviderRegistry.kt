package dev.promethe.gateway.voice

import dev.promethe.core.CredentialsStore
import dev.promethe.core.Log
import dev.promethe.db.PrometheDatabaseApi

import kotlinx.serialization.Serializable

private val logger = Log.create("VoiceProviderRegistry")

// ── Capabilities ─────────────────────────────────────────────────

enum class VoiceCapability {
    /** Speech-to-Speech: audio in → audio out (single model) */
    S2S,

    /** Text-to-Speech: text → audio */
    TTS,

    /** Speech-to-Text: audio → text */
    STT,

    /** Real-time translation: audio in (language A) → audio out (language B) */
    TRANSLATE,
}

// ── Provider interface ───────────────────────────────────────────

/**
 * VoiceProvider — a provider that supports one or more voice capabilities.
 *
 * Discovery is dynamic: a provider only appears as "available"
 * if its required API key is configured in the database.
 */
interface VoiceProvider {
    val id: String
    val displayName: String
    val capabilities: Set<VoiceCapability>
    val implemented: Boolean get() = true

    /** Settings key that must be non-empty for this provider to be available. */
    val requiredSettingKey: String

    /** Known models for each capability. */
    fun defaultModels(capability: VoiceCapability): List<String>

    /** Known voices (for S2S and TTS). Empty for STT-only providers. */
    fun defaultVoices(): List<VoiceInfo> = emptyList()

    /** Fetch models live from provider API. Falls back to defaultModels(). */
    suspend fun fetchModels(
        apiKey: String,
        capability: VoiceCapability,
    ): List<String> = defaultModels(capability)

    /** Fetch voices live from provider API. Falls back to defaultVoices(). */
    suspend fun fetchVoices(apiKey: String): List<VoiceInfo> = defaultVoices()
}

// ── Registry ─────────────────────────────────────────────────────

/**
 * Discovers available voice providers based on configured API keys.
 * Filters by capability so the UI only shows relevant providers.
 */
class VoiceProviderRegistry(
    private val database: PrometheDatabaseApi,
    private val providers: List<VoiceProvider> = allProviders(),
) {
    /** All providers whose API key is configured (checks DB + credentials.json). */
    suspend fun getAvailableProviders(): List<VoiceProviderInfo> {
        val settings = mergedSettings()
        return providers.filter { p ->
            p.implemented && !settings[p.requiredSettingKey].isNullOrBlank()
        }.map { it.toInfo() }
    }

    /** Providers that support a specific capability AND have a configured key. */
    suspend fun getProvidersByCapability(capability: VoiceCapability): List<VoiceProviderInfo> {
        val settings = mergedSettings()
        return providers.filter { p ->
            p.implemented && capability in p.capabilities && !settings[p.requiredSettingKey].isNullOrBlank()
        }.map { it.toInfo() }
    }

    /** Voices for a provider (fetched dynamically, fallback to defaults). */
    suspend fun getVoices(providerId: String): List<VoiceInfo> {
        val provider = providers.find { it.id == providerId } ?: return emptyList()
        if (!provider.implemented) return emptyList()
        val settings = mergedSettings()
        val apiKey = settings[provider.requiredSettingKey]
        if (apiKey.isNullOrBlank()) return emptyList()
        return try {
            provider.fetchVoices(apiKey)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to fetch voices for $providerId, using defaults" }
            provider.defaultVoices()
        }
    }

    /** Models for a provider + capability. */
    suspend fun getModels(
        providerId: String,
        capability: VoiceCapability,
    ): List<String> {
        val provider = providers.find { it.id == providerId } ?: return emptyList()
        if (!provider.implemented) return emptyList()
        val settings = mergedSettings()
        val apiKey = settings[provider.requiredSettingKey]
        if (apiKey.isNullOrBlank()) return emptyList()
        return try {
            provider.fetchModels(apiKey, capability)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to fetch models for $providerId, using defaults" }
            provider.defaultModels(capability)
        }
    }

    fun getProvider(id: String): VoiceProvider? = providers.find { it.id == id && it.implemented }

    /** Merge DB settings with credentials.json (DB wins for overlapping keys). */
    private suspend fun mergedSettings(): Map<String, String> {
        val db = database.getAllSettings()
        val credsEnv = try {
            CredentialsStore.load()?.toEnvMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        return credsEnv + db // DB settings override credentials
    }

    private fun VoiceProvider.toInfo() =
        VoiceProviderInfo(
            id = id,
            name = displayName,
            capabilities = capabilities.map { it.name },
        )

    companion object {
        fun allProviders(): List<VoiceProvider> =
            listOf(
                // ── S2S ──
                GeminiLiveProvider(),
                OpenAIRealtimeProvider(),
                MoshiProvider(),
                // ── TRANSLATE ──
                GeminiTranslateProvider(),
                OpenAITranslateProvider(),
                // ── TTS ──
                GeminiTTSProvider(),
                ElevenLabsTTSProvider(),
                CartesiaTTSProvider(),
                OpenAITTSProvider(),
                // ── STT ──
                DeepgramSTTProvider(),
                AssemblyAISTTProvider(),
                OpenAISTTProvider(),
                WhisperSTTProvider(),
                GoogleSTTProvider(),
            )
    }
}

// ── DTOs ──────────────────────────────────────────────────────────

@Serializable
data class VoiceProviderInfo(
    val id: String,
    val name: String,
    val capabilities: List<String>,
)

@Serializable
data class VoiceInfo(
    val id: String,
    val description: String,
)

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — S2S
// ═══════════════════════════════════════════════════════════════════

class GeminiLiveProvider : VoiceProvider {
    override val id = "gemini_live"
    override val displayName = "Google Gemini Live"
    override val capabilities = setOf(VoiceCapability.S2S)
    override val requiredSettingKey = "GOOGLE_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "gemini-3.1-flash-live-preview", // Best for voice assistant (tools + reflection + search)
        )

    override fun defaultVoices() =
        listOf(
            VoiceInfo("Puck", "Upbeat, energetic"),
            VoiceInfo("Charon", "Calm, composed"),
            VoiceInfo("Kore", "Warm, friendly"),
            VoiceInfo("Fenrir", "Deep, authoritative"),
            VoiceInfo("Aoede", "Bright, melodic"),
            VoiceInfo("Leda", "Gentle, soothing"),
            VoiceInfo("Orus", "Clear, professional"),
            VoiceInfo("Zephyr", "Light, airy"),
        )
}

class OpenAIRealtimeProvider : VoiceProvider {
    override val id = "openai_realtime"
    override val displayName = "OpenAI Realtime"
    override val capabilities = setOf(VoiceCapability.S2S)
    override val requiredSettingKey = "OPENAI_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "gpt-realtime-2.1",
            "gpt-realtime-2.1-mini",
        )

    override fun defaultVoices() =
        listOf(
            VoiceInfo("alloy", "Neutral, balanced"),
            VoiceInfo("ash", "Warm, conversational"),
            VoiceInfo("ballad", "Warm, natural (realtime-only)"),
            VoiceInfo("coral", "Friendly, expressive (realtime-only)"),
            VoiceInfo("echo", "Clear, resonant"),
            VoiceInfo("sage", "Calm, professional (realtime-only)"),
            VoiceInfo("shimmer", "Warm, polished"),
            VoiceInfo("verse", "Energetic, dynamic (realtime-only)"),
            VoiceInfo("marin", "Authority, clear (realtime-only)"),
            VoiceInfo("cedar", "Deep, warm (realtime-only)"),
        )
}

class MoshiProvider : VoiceProvider {
    override val id = "moshi"
    override val displayName = "Moshi (Kyutai)"
    override val capabilities = setOf(VoiceCapability.S2S)
    override val requiredSettingKey = "moshi_endpoint"
    override val implemented = false

    override fun defaultModels(capability: VoiceCapability) = listOf("moshi-v1")

    override fun defaultVoices() =
        listOf(
            VoiceInfo("default", "Moshi default voice"),
        )
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — TRANSLATE
// ═══════════════════════════════════════════════════════════════════

class GeminiTranslateProvider : VoiceProvider {
    override val id = "gemini_translate"
    override val displayName = "Google Gemini Translate"
    override val capabilities = setOf(VoiceCapability.TRANSLATE)
    override val requiredSettingKey = "GOOGLE_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "gemini-3.5-live-translate-preview",
        )

    override fun defaultVoices() =
        listOf(
            VoiceInfo("Puck", "Upbeat, energetic"),
            VoiceInfo("Charon", "Calm, composed"),
            VoiceInfo("Kore", "Warm, friendly"),
            VoiceInfo("Aoede", "Bright, melodic"),
            VoiceInfo("Zephyr", "Light, airy"),
        )
}

class OpenAITranslateProvider : VoiceProvider {
    override val id = "openai_translate"
    override val displayName = "OpenAI Realtime Translate"
    override val capabilities = setOf(VoiceCapability.TRANSLATE)
    override val requiredSettingKey = "OPENAI_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "gpt-realtime-translate",
        )

    // Translate mode uses dynamic voice adaptation (mirrors speaker tone)
    override fun defaultVoices() =
        listOf(
            VoiceInfo("auto", "Dynamic — mirrors speaker tone and style"),
        )
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — TTS
// ═══════════════════════════════════════════════════════════════════

class ElevenLabsTTSProvider : VoiceProvider {
    override val id = "elevenlabs"
    override val displayName = "ElevenLabs"
    override val capabilities = setOf(VoiceCapability.TTS)
    override val requiredSettingKey = "ELEVENLABS_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "eleven_v3",
            "eleven_flash_v2_5",
            "eleven_multilingual_v2",
        )

    // Voices fetched live from API — no static fallback
    override fun defaultVoices() = emptyList<VoiceInfo>()

    // TODO: override suspend fun fetchVoices(apiKey) →
    //   GET https://api.elevenlabs.io/v1/voices
    //   header("xi-api-key", apiKey)
    //   parse response.voices → VoiceInfo(voice_id, "$name — $category")
}

class CartesiaTTSProvider : VoiceProvider {
    override val id = "cartesia"
    override val displayName = "Cartesia"
    override val capabilities = setOf(VoiceCapability.TTS)
    override val requiredSettingKey = "CARTESIA_API_KEY"
    override val implemented = false

    override fun defaultModels(capability: VoiceCapability) = listOf("sonic-3.5", "sonic-3")

    override fun defaultVoices() = emptyList<VoiceInfo>()

    // TODO: override fetchVoices() → GET https://api.cartesia.ai/voices
}

class OpenAITTSProvider : VoiceProvider {
    override val id = "openai_tts"
    override val displayName = "OpenAI TTS"
    override val capabilities = setOf(VoiceCapability.TTS)
    override val requiredSettingKey = "OPENAI_API_KEY"

    override fun defaultModels(capability: VoiceCapability) = listOf("tts-1", "tts-1-hd")
    // NOTE: gpt-4o-mini-tts is deprecated by OpenAI as of 2026

    override fun defaultVoices() =
        listOf(
            VoiceInfo("alloy", "Neutral, balanced"),
            VoiceInfo("echo", "Clear, resonant"),
            VoiceInfo("fable", "Expressive, narrative"),
            VoiceInfo("onyx", "Deep, authoritative"),
            VoiceInfo("nova", "Bright, conversational"),
            VoiceInfo("shimmer", "Warm, polished"),
        )
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — STT
// ═══════════════════════════════════════════════════════════════════

class DeepgramSTTProvider : VoiceProvider {
    override val id = "deepgram"
    override val displayName = "Deepgram"
    override val capabilities = setOf(VoiceCapability.STT)
    override val requiredSettingKey = "DEEPGRAM_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "nova-3",
            "flux",
            "nova-3-medical",
        )
}

class AssemblyAISTTProvider : VoiceProvider {
    override val id = "assemblyai"
    override val displayName = "AssemblyAI"
    override val capabilities = setOf(VoiceCapability.STT)
    override val requiredSettingKey = "ASSEMBLYAI_API_KEY"
    override val implemented = false

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "universal-3-pro",
            "universal-2",
        )
}

class WhisperSTTProvider : VoiceProvider {
    override val id = "whisper"
    override val displayName = "Whisper (self-hosted)"
    override val capabilities = setOf(VoiceCapability.STT)
    override val requiredSettingKey = "WHISPER_ENDPOINT"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "whisper-large-v3-turbo",
            "whisper-large-v3",
        )
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — Gemini TTS
// ═══════════════════════════════════════════════════════════════════

class GeminiTTSProvider : VoiceProvider {
    override val id = "gemini_tts"
    override val displayName = "Google Gemini TTS"
    override val capabilities = setOf(VoiceCapability.TTS)
    override val requiredSettingKey = "GOOGLE_API_KEY"

    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "gemini-3.1-flash-tts-preview",
        )

    override fun defaultVoices() =
        listOf(
            VoiceInfo("Puck", "Upbeat, energetic"),
            VoiceInfo("Charon", "Calm, composed"),
            VoiceInfo("Kore", "Warm, friendly"),
            VoiceInfo("Fenrir", "Deep, authoritative"),
            VoiceInfo("Aoede", "Bright, melodic"),
            VoiceInfo("Leda", "Gentle, soothing"),
            VoiceInfo("Orus", "Clear, professional"),
            VoiceInfo("Zephyr", "Light, airy"),
        )
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — OpenAI STT (via multimodal audio input)
// ═══════════════════════════════════════════════════════════════════

class OpenAISTTProvider : VoiceProvider {
    override val id = "openai_stt"
    override val displayName = "OpenAI STT"
    override val capabilities = setOf(VoiceCapability.STT)
    override val requiredSettingKey = "OPENAI_API_KEY"
    override val implemented = false

    // Multimodal models + dedicated STT
    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "gpt-realtime-whisper",
            "gpt-4o-transcribe",
            "whisper-1",
        )
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDERS — Google STT (via multimodal audio input)
// ═══════════════════════════════════════════════════════════════════

class GoogleSTTProvider : VoiceProvider {
    override val id = "google_stt"
    override val displayName = "Google Cloud Speech-to-Text"
    override val capabilities = setOf(VoiceCapability.STT)
    override val requiredSettingKey = "GOOGLE_CLOUD_PROJECT"
    override val implemented = false

    // Requires Speech-to-Text V2 authentication and a dedicated Chirp backend.
    override fun defaultModels(capability: VoiceCapability) =
        listOf(
            "chirp_3",
        )
}
