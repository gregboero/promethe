package dev.promethe.gateway.voice

import dev.promethe.core.CredentialsStore
import dev.promethe.core.Log
import dev.promethe.core.config.ConfigProvider
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val logger = Log.create("TtsSynthesizer")

/**
 * TtsSynthesizer — reusable TTS synthesis for voice preview and STT→Agent→TTS pipeline.
 *
 * Supports:
 * - **OpenAI TTS** — `POST api.openai.com/v1/audio/speech` → PCM 24kHz
 * - **ElevenLabs** — `POST api.elevenlabs.io/v1/text-to-speech/{voice}/stream` → PCM 16kHz
 * - **Gemini TTS** — `POST generativelanguage.googleapis.com` → PCM 24kHz
 */
object TtsSynthesizer {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class TtsResult(
        /** Base64-encoded PCM audio */
        val data: String,
        /** Sample rate in Hz */
        val sampleRate: Int,
        /** Encoding type */
        val encoding: String = "pcm16",
    )

    private val DEFAULT_TEXT = "Bonjour, je suis votre assistant. Comment puis-je vous aider ?"

    /**
     * Synthesize a short voice preview sample.
     *
     * @param providerId Provider: "openai_tts", "elevenlabs", "gemini_tts", "gemini_live"
     * @param voice Voice identifier (e.g. "alloy", "Puck", ElevenLabs voice ID)
     * @param text Text to synthesize (defaults to a short French greeting)
     * @param client HTTP client to use
     * @return TtsResult with base64 PCM audio, or null on failure
     */
    suspend fun synthesize(
        providerId: String,
        voice: String,
        text: String? = null,
        client: HttpClient,
    ): TtsResult? {
        val apiKey = resolveApiKey(providerId) ?: run {
            logger.warn { "No API key found for TTS provider: $providerId" }
            return null
        }
        val phrase = text?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT

        return try {
            when (providerId) {
                "openai_tts" -> {
                    synthesizeOpenAI(client, apiKey, voice, phrase)
                }

                "elevenlabs" -> {
                    synthesizeElevenLabs(client, apiKey, voice, phrase)
                }

                "gemini_tts", "gemini_live" -> {
                    synthesizeGemini(client, apiKey, voice, phrase)
                }

                else -> {
                    logger.warn { "Unsupported TTS provider for preview: $providerId" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "TTS synthesis failed for provider=$providerId voice=$voice" }
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Provider implementations
    // ═══════════════════════════════════════════════════════════════

    private suspend fun synthesizeOpenAI(
        client: HttpClient,
        apiKey: String,
        voice: String,
        text: String,
    ): TtsResult? {
        val body = buildJsonObject {
            put("model", "gpt-4o-mini-tts")
            put("voice", voice.ifBlank { "alloy" })
            put("input", text)
            put("response_format", "pcm")
        }

        val response = client.post("https://api.openai.com/v1/audio/speech") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.bodyAsText()
            logger.warn { "OpenAI TTS error (${response.status}): $errorBody" }
            return null
        }

        val bytes = response.readRawBytes()
        return TtsResult(
            data = kotlin.io.encoding.Base64.encode(bytes),
            sampleRate = 24000,
        )
    }

    private suspend fun synthesizeElevenLabs(
        client: HttpClient,
        apiKey: String,
        voice: String,
        text: String,
    ): TtsResult? {
        val voiceId = voice.ifBlank { "21m00Tcm4TlvDq8ikWAM" } // Rachel default
        val body = buildJsonObject {
            put("text", text)
            put("model_id", "eleven_flash_v2_5")
            put("output_format", "pcm_16000")
        }

        val response = client.post("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream") {
            header("xi-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.bodyAsText()
            logger.warn { "ElevenLabs TTS error (${response.status}): $errorBody" }
            return null
        }

        val bytes = response.readRawBytes()
        return TtsResult(
            data = kotlin.io.encoding.Base64.encode(bytes),
            sampleRate = 16000,
        )
    }

    private suspend fun synthesizeGemini(
        client: HttpClient,
        apiKey: String,
        voice: String,
        text: String,
    ): TtsResult? {
        // Use Gemini TTS model for preview (works with same voices as Live)
        val model = "gemini-3.1-flash-tts-preview"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val body = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("text", text)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                    put(
                        "speechConfig",
                        buildJsonObject {
                            put(
                                "voiceConfig",
                                buildJsonObject {
                                    put(
                                        "prebuiltVoiceConfig",
                                        buildJsonObject {
                                            put("voiceName", voice.ifBlank { "Puck" })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.bodyAsText()
            logger.warn { "Gemini TTS error (${response.status}): $errorBody" }
            return null
        }

        // Parse Gemini response: candidates[0].content.parts[0].inlineData.data (base64)
        val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val inlineData = responseJson["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("inlineData")?.jsonObject

        val audioBase64 = inlineData?.get("data")?.jsonPrimitive?.content ?: run {
            logger.warn { "Gemini TTS: no audio data in response" }
            return null
        }

        return TtsResult(
            data = audioBase64,
            sampleRate = 24000,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  Key resolution
    // ═══════════════════════════════════════════════════════════════

    private fun resolveApiKey(providerId: String): String? {
        val envKey = when (providerId) {
            "openai_tts" -> "OPENAI_API_KEY"
            "elevenlabs" -> "ELEVENLABS_API_KEY"
            "gemini_tts", "gemini_live" -> "GOOGLE_API_KEY"
            else -> return null
        }

        // Check unified config first
        try {
            ConfigProvider.get().get(envKey)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
        }

        // Fall back to credentials.json llmApiKeys
        try {
            val creds = CredentialsStore.load()
            creds?.llmApiKeys?.forEach { (provider, key) ->
                if (key.isNotBlank() && "${provider.uppercase()}_API_KEY" == envKey) {
                    return key
                }
            }
        } catch (_: Exception) {
        }

        return null
    }
}
