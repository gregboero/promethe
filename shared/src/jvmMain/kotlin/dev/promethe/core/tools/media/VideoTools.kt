package dev.promethe.core.tools.media

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.providers.Capability
import dev.promethe.core.providers.CapabilityResolution
import dev.promethe.core.providers.CapabilityRouter
import dev.promethe.core.providers.ProviderEntry
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ── video_generate ──────────────────────────────────────────────

@Serializable
data class VideoGenerateArgs(
    @property:LLMDescription("Text description of the video to generate.")
    val prompt: String,
    @property:LLMDescription("Duration in seconds (4-16). Default 4.")
    val duration: Int = 4,
    @property:LLMDescription("Aspect ratio: '16:9', '9:16', '1:1'. Default '16:9'.")
    val aspectRatio: String = "16:9",
)

class VideoGenerateTool(
    private val httpClient: HttpClient,
    private val apiKeys: Map<String, String>,
    private val router: CapabilityRouter,
    private val outputDir: String,
) : SimpleTool<VideoGenerateArgs>(
        argsType = typeToken<VideoGenerateArgs>(),
        name = "video_generate",
        description = "Generate a short video from a text description using AI (Google Veo 3, Replicate, fal.ai, Runway).",
    ) {
    override suspend fun execute(args: VideoGenerateArgs): String {
        val resolution = router.resolve(Capability.VIDEO_GENERATION)
        return when (resolution) {
            is CapabilityResolution.NotConfigured -> resolution.message
            is CapabilityResolution.Ready -> generateWith(resolution.provider, args)
            is CapabilityResolution.MultipleAvailable -> generateWith(resolution.providers.first(), args)
        }
    }

    private suspend fun generateWith(
        provider: ProviderEntry,
        args: VideoGenerateArgs,
    ): String {
        return when (provider.id) {
            "google-veo3" -> {
                val apiKey = apiKeys["google"] ?: ""
                if (apiKey.isBlank()) return "[ERROR] Google API key not configured."
                generateViaVeo3(args, apiKey)
            }

            "replicate-luma" -> {
                val token = apiKeys["replicate"] ?: ""
                if (token.isBlank()) return "[ERROR] Replicate API token not configured."
                generateViaReplicate(args, token)
            }

            "fal-minimax" -> {
                val key = apiKeys["fal"] ?: ""
                if (key.isBlank()) return "[ERROR] fal.ai API key not configured."
                generateViaFal(args, key)
            }

            "runway-gen3" -> {
                val key = apiKeys["runway"] ?: ""
                if (key.isBlank()) return "[ERROR] Runway API key not configured."
                generateViaRunway(args, key)
            }

            else -> {
                "[ERROR] Unknown video generation provider: ${provider.id}"
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun generateViaVeo3(
        args: VideoGenerateArgs,
        apiKey: String,
    ): String {
        // TODO: Implement Google Veo 3 API
        return "[INFO] Google Veo 3 video generation is not yet implemented. " +
            "Use Replicate, fal.ai, or Runway in the meantime."
    }

    private suspend fun generateViaReplicate(
        args: VideoGenerateArgs,
        token: String,
    ): String =
        try {
            val response = httpClient.post("https://api.replicate.com/v1/predictions") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("version", "luma/ray")
                        putJsonObject("input") {
                            put("prompt", args.prompt)
                            put("num_frames", args.duration * 24)
                        }
                    }.toString(),
                )
            }
            "[Replicate] Prediction submitted (async). Response: ${response.bodyAsText().take(500)}"
        } catch (e: Exception) {
            "[ERROR] Replicate: ${e.message}"
        }

    private suspend fun generateViaFal(
        args: VideoGenerateArgs,
        key: String,
    ): String =
        try {
            val response = httpClient.post("https://queue.fal.run/fal-ai/minimax-video/image-to-video") {
                header("Authorization", "Key $key")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("prompt", args.prompt)
                        put("aspect_ratio", args.aspectRatio)
                    }.toString(),
                )
            }
            "[fal.ai] Submitted. Response: ${response.bodyAsText().take(500)}"
        } catch (e: Exception) {
            "[ERROR] fal.ai: ${e.message}"
        }

    private suspend fun generateViaRunway(
        args: VideoGenerateArgs,
        key: String,
    ): String =
        try {
            val response = httpClient.post("https://api.dev.runwayml.com/v1/image_to_video") {
                header("Authorization", "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("promptText", args.prompt)
                        put("duration", args.duration)
                        put("ratio", args.aspectRatio)
                    }.toString(),
                )
            }
            "[Runway] Submitted. Response: ${response.bodyAsText().take(500)}"
        } catch (e: Exception) {
            "[ERROR] Runway: ${e.message}"
        }
}

// ── video_analyze ───────────────────────────────────────────────

@Serializable
data class VideoAnalyzeArgs(
    @property:LLMDescription("URL of the video to analyze.")
    val url: String,
    @property:LLMDescription("Question about the video content. Default: describe the video.")
    val question: String = "Describe this video content",
)

class VideoAnalyzeTool(
    private val httpClient: HttpClient,
    private val apiKeys: Map<String, String>,
    private val router: CapabilityRouter,
) : SimpleTool<VideoAnalyzeArgs>(
        argsType = typeToken<VideoAnalyzeArgs>(),
        name = "video_analyze",
        description = "Analyze video content — describe scenes, summarize, answer questions (Gemini or OpenAI).",
    ) {
    override suspend fun execute(args: VideoAnalyzeArgs): String {
        val resolution = router.resolve(Capability.VIDEO_ANALYSIS)
        return when (resolution) {
            is CapabilityResolution.NotConfigured -> resolution.message
            is CapabilityResolution.Ready -> analyzeWith(resolution.provider, args)
            is CapabilityResolution.MultipleAvailable -> analyzeWith(resolution.providers.first(), args)
        }
    }

    private suspend fun analyzeWith(
        provider: ProviderEntry,
        args: VideoAnalyzeArgs,
    ): String {
        return when (provider.id) {
            "google-gemini-video" -> {
                val apiKey = apiKeys["google"] ?: ""
                if (apiKey.isBlank()) return "[ERROR] Google API key not configured."
                analyzeViaGemini(args, apiKey)
            }

            "openai-vision-frames" -> {
                val apiKey = apiKeys["openai"] ?: ""
                if (apiKey.isBlank()) return "[ERROR] OpenAI API key not configured."
                analyzeViaOpenAIFrames(args, apiKey)
            }

            else -> {
                "[ERROR] Unknown video analysis provider: ${provider.id}"
            }
        }
    }

    private suspend fun analyzeViaGemini(
        args: VideoAnalyzeArgs,
        apiKey: String,
    ): String =
        try {
            val response = httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put(
                            "contents",
                            kotlinx.serialization.json.buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "parts",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        putJsonObject("file_data") {
                                                            put("file_uri", args.url)
                                                            put("mime_type", "video/mp4")
                                                        }
                                                    },
                                                )
                                                add(buildJsonObject { put("text", args.question) })
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }.toString(),
                )
            }
            "[Gemini] Analysis: ${response.bodyAsText().take(2000)}"
        } catch (e: Exception) {
            "[ERROR] Gemini video analysis: ${e.message}"
        }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun analyzeViaOpenAIFrames(
        args: VideoAnalyzeArgs,
        apiKey: String,
    ): String {
        // TODO: Implement OpenAI GPT-4o frame-based video analysis
        //  (extract keyframes from the video, send as images to GPT-4o Vision)
        return "[INFO] OpenAI GPT-4o frame-based video analysis is not yet implemented. " +
            "Use Google Gemini for native video analysis in the meantime."
    }
}
