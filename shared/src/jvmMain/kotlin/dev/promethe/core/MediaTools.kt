package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.providers.*
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════
//  Media Tools — Image Generation, Vision Analysis, TTS
//  Image & Vision: routed through CapabilityRouter
//  TTS: delegated to the voice gateway REST API
// ══════════════════════════════════════════════════════════════

// ── Argument data classes ──

@Serializable
data class ImageGenArgs(
    @property:LLMDescription("Description of the image to generate. Be specific and detailed.")
    val prompt: String,
    @property:LLMDescription("Image size: '1024x1024', '512x512', '1792x1024', or '1024x1792'.")
    val size: String = "1024x1024",
    @property:LLMDescription("Quality: 'standard' or 'hd' (OpenAI only).")
    val quality: String = "standard",
)

@Serializable
data class VisionArgs(
    @property:LLMDescription("URL of the image to analyze.")
    val imageUrl: String,
    @property:LLMDescription("Question or instruction about the image. E.g., 'Describe this image' or 'What text is visible?'")
    val prompt: String = "Describe this image in detail.",
)

@Serializable
data class TextToSpeechArgs(
    @property:LLMDescription("Text to convert to speech.")
    val text: String,
    @property:LLMDescription("TTS provider override (leave empty to use configured default). Options: 'openai_tts', 'elevenlabs_tts', 'gemini_tts'.")
    val provider: String = "",
    @property:LLMDescription("Voice to use (leave empty to use configured default). E.g. 'alloy', 'echo', 'fable', 'onyx', 'nova', 'shimmer' for OpenAI.")
    val voice: String = "",
    @property:LLMDescription("Output format: 'mp3', 'opus', 'aac', 'flac'.")
    val format: String = "mp3",
)

// ── Image Generation Tool (multi-provider) ──

/**
 * Generates images via multiple providers: OpenAI DALL-E 3, Google Imagen 3, Stability AI.
 * Uses [CapabilityRouter] to select the configured provider.
 */
class ImageGenerationTool(
    private val httpClient: HttpClient,
    private val apiKeys: Map<String, String>,
    private val router: CapabilityRouter,
) : SimpleTool<ImageGenArgs>(
        argsType = typeToken<ImageGenArgs>(),
        name = "generate_image",
        description = "Generate an image from a text description using AI (DALL-E, Imagen, Stability AI). Returns the image URL.",
    ) {
    private val json = PrometheJson

    override suspend fun execute(args: ImageGenArgs): String {
        val resolution = router.resolve(Capability.IMAGE_GENERATION)
        return when (resolution) {
            is CapabilityResolution.NotConfigured -> resolution.message
            is CapabilityResolution.Ready -> executeWith(resolution.provider, args)
            is CapabilityResolution.MultipleAvailable -> executeWith(resolution.providers.first(), args)
        }
    }

    private suspend fun executeWith(
        provider: ProviderEntry,
        args: ImageGenArgs,
    ): String =
        when (provider.id) {
            "openai-dalle3" -> generateViaDalle(args, apiKeys["openai"] ?: "")
            "google-imagen3" -> generateViaImagen(args, apiKeys["google"] ?: "")
            "stability-ai" -> generateViaStability(args, apiKeys["stability"] ?: "")
            else -> "[ERROR] Provider ${provider.id} not implemented for image generation"
        }

    private suspend fun generateViaDalle(
        args: ImageGenArgs,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) return "[ERROR] OpenAI API key not configured"
        return try {
            val response = httpClient.post("https://api.openai.com/v1/images/generations") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        put("model", "dall-e-3")
                        put("prompt", args.prompt)
                        put("size", args.size)
                        put("quality", args.quality)
                        put("n", 1)
                    }.toString(),
                )
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val imageUrl = body["data"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.content
            if (imageUrl != null) {
                "Image generated via DALL-E 3.\nURL: $imageUrl"
            } else {
                "[ERROR] No image URL in response: ${response.bodyAsText().take(200)}"
            }
        } catch (e: Exception) {
            "[ERROR] DALL-E image generation failed: ${e.message}"
        }
    }

    private suspend fun generateViaImagen(
        args: ImageGenArgs,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) return "[ERROR] Google API key not configured"
        return try {
            val response = httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict?key=$apiKey",
            ) {
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("instances") {
                            put("prompt", args.prompt)
                        }
                        putJsonObject("parameters") {
                            put("sampleCount", 1)
                            put("aspectRatio", if (args.size.contains("1792")) "16:9" else "1:1")
                        }
                    }.toString(),
                )
            }
            "[Imagen 3] Response: ${response.bodyAsText().take(1000)}"
        } catch (e: Exception) {
            "[ERROR] Imagen generation failed: ${e.message}"
        }
    }

    private suspend fun generateViaStability(
        args: ImageGenArgs,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) return "[ERROR] Stability API key not configured"
        return try {
            val response = httpClient.post("https://api.stability.ai/v2beta/stable-image/generate/sd3") {
                header("Authorization", "Bearer $apiKey")
                header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        put("prompt", args.prompt)
                        put("output_format", "png")
                    }.toString(),
                )
            }
            "[Stability AI] Response: ${response.bodyAsText().take(1000)}"
        } catch (e: Exception) {
            "[ERROR] Stability AI generation failed: ${e.message}"
        }
    }
}

// ── Vision Analysis Tool (multi-provider) ──

/**
 * Analyzes images using multimodal LLMs: GPT-4o, Gemini, Claude.
 * Uses [CapabilityRouter] to select the configured provider.
 */
class VisionTool(
    private val httpClient: HttpClient,
    private val apiKeys: Map<String, String>,
    private val router: CapabilityRouter,
) : SimpleTool<VisionArgs>(
        argsType = typeToken<VisionArgs>(),
        name = "analyze_image",
        description = "Analyze an image from a URL. Describe contents, read text, identify objects. Supports GPT-4o, Gemini, Claude.",
    ) {
    private val json = PrometheJson

    override suspend fun execute(args: VisionArgs): String {
        val resolution = router.resolve(Capability.VISION)
        return when (resolution) {
            is CapabilityResolution.NotConfigured -> resolution.message
            is CapabilityResolution.Ready -> executeWith(resolution.provider, args)
            is CapabilityResolution.MultipleAvailable -> executeWith(resolution.providers.first(), args)
        }
    }

    private suspend fun executeWith(
        provider: ProviderEntry,
        args: VisionArgs,
    ): String =
        when (provider.id) {
            "openai-vision" -> analyzeViaOpenAI(args, apiKeys["openai"] ?: "")
            "google-gemini-vision" -> analyzeViaGemini(args, apiKeys["google"] ?: "")
            "anthropic-claude-vision" -> analyzeViaClaude(args, apiKeys["anthropic"] ?: "")
            else -> "[ERROR] Provider ${provider.id} not implemented for vision"
        }

    private suspend fun analyzeViaOpenAI(
        args: VisionArgs,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) return "[ERROR] OpenAI API key not configured"
        return try {
            val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        put("model", "gpt-4o")
                        put("max_tokens", 1024)
                        putJsonArray("messages") {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", args.prompt)
                                    }
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", args.imageUrl) }
                                    }
                                }
                            }
                        }
                    }.toString(),
                )
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.content ?: "[ERROR] No content in vision response"
        } catch (e: Exception) {
            "[ERROR] OpenAI vision failed: ${e.message}"
        }
    }

    private suspend fun analyzeViaGemini(
        args: VisionArgs,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) return "[ERROR] Google API key not configured"
        return try {
            val response = httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey",
            ) {
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        putJsonArray("contents") {
                            addJsonObject {
                                putJsonArray("parts") {
                                    addJsonObject {
                                        putJsonObject("inline_data") {
                                            put("mime_type", "image/jpeg")
                                            put("data", args.imageUrl) // URL or base64
                                        }
                                    }
                                    addJsonObject { put("text", args.prompt) }
                                }
                            }
                        }
                    }.toString(),
                )
            }
            "[Gemini Vision] ${response.bodyAsText().take(2000)}"
        } catch (e: Exception) {
            "[ERROR] Gemini vision failed: ${e.message}"
        }
    }

    private suspend fun analyzeViaClaude(
        args: VisionArgs,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) return "[ERROR] Anthropic API key not configured"
        return try {
            val response = httpClient.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        put("model", "claude-sonnet-4-20250514")
                        put("max_tokens", 1024)
                        putJsonArray("messages") {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "url")
                                            put("url", args.imageUrl)
                                        }
                                    }
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", args.prompt)
                                    }
                                }
                            }
                        }
                    }.toString(),
                )
            }
            "[Claude Vision] ${response.bodyAsText().take(2000)}"
        } catch (e: Exception) {
            "[ERROR] Claude vision failed: ${e.message}"
        }
    }
}

// ── Text-to-Speech Tool (gateway-backed) ──

/**
 * Converts text to speech by delegating to the voice gateway REST API.
 *
 * Instead of routing through [CapabilityRouter] to individual provider APIs,
 * this tool POSTs to the gateway's `/api/tts/synthesize` endpoint, which
 * handles provider selection, credential management, and audio generation
 * through the dynamic voice provider system.
 */
class TextToSpeechTool(
    private val httpClient: HttpClient,
    private val gatewayBaseUrl: String = "http://localhost:8080",
) : SimpleTool<TextToSpeechArgs>(
        argsType = typeToken<TextToSpeechArgs>(),
        name = "text_to_speech",
        description = "Convert text to speech audio via the voice gateway. Returns a JSON result with audio file info.",
    ) {
    private val json = PrometheJson

    override suspend fun execute(args: TextToSpeechArgs): String {
        // Load credentials — includes TTS config (provider, voice)
        val creds = try {
            CredentialsStore.load()
        } catch (_: Exception) {
            null
        }
        val gatewayApiKey = creds?.apiKey ?: ""

        // Use configured TTS provider/voice as defaults — LLM args override if specified
        val resolvedProvider = args.provider.ifBlank {
            creds?.voiceTtsProvider?.takeIf { it.isNotBlank() } ?: "openai_tts"
        }
        val resolvedVoice = args.voice.ifBlank {
            creds?.voiceTtsVoice?.takeIf { it.isNotBlank() } ?: "alloy"
        }

        return try {
            val response = httpClient.post("$gatewayBaseUrl/api/tts/synthesize") {
                contentType(ContentType.Application.Json)
                if (gatewayApiKey.isNotBlank()) {
                    header("Authorization", "Bearer $gatewayApiKey")
                }
                timeout {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    buildJsonObject {
                        put("text", args.text)
                        put("provider", resolvedProvider)
                        put("voice", resolvedVoice)
                        put("format", args.format)
                    }.toString(),
                )
            }
            if (response.status.isSuccess()) {
                response.bodyAsText()
            } else {
                buildJsonObject {
                    put("error", true)
                    put("status", response.status.value)
                    put("message", "TTS gateway returned ${response.status}: ${response.bodyAsText().take(500)}")
                }.toString()
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", true)
                put("message", "TTS gateway call failed: ${e.message}")
            }.toString()
        }
    }
}
