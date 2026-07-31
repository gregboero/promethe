package dev.promethe.gateway.voice

import dev.promethe.core.Log
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val logger = Log.create("VoiceConfigRoute")

@Serializable
private data class PreviewRequest(
    val provider: String,
    val voice: String,
    val text: String? = null,
)

/**
 * Voice configuration routes — `/api/v1/voice/...`
 *
 * 100% dynamic: providers discovered from API keys, voices fetched from APIs.
 *
 *   GET  /api/v1/voice/providers              → all available providers
 *   GET  /api/v1/voice/providers?cap=S2S      → only S2S providers
 *   GET  /api/v1/voice/providers?cap=TTS      → only TTS providers
 *   GET  /api/v1/voice/providers?cap=STT      → only STT providers
 *   GET  /api/v1/voice/voices?provider=X      → voices for provider X
 *   GET  /api/v1/voice/models?provider=X&cap=S2S → models for provider X + capability
 *   POST /api/v1/voice/preview                → synthesize a short voice sample
 */
fun Route.voiceConfigRoutes(
    registry: VoiceProviderRegistry,
    httpClient: HttpClient? = null,
) {
    route("voice") {
        get("/providers") {
            val capParam = call.request.queryParameters["cap"]
            val capability = capParam?.let {
                try {
                    VoiceCapability.valueOf(it.uppercase())
                } catch (_: Exception) {
                    null
                }
            }

            val providers = if (capability != null) {
                registry.getProvidersByCapability(capability)
            } else {
                registry.getAvailableProviders()
            }
            call.respond(providers)
        }

        get("/voices") {
            val providerId = call.request.queryParameters["provider"]
            if (providerId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'provider' query param"))
                return@get
            }
            val voices = registry.getVoices(providerId)
            call.respond(voices)
        }

        get("/models") {
            val providerId = call.request.queryParameters["provider"]
            val capParam = call.request.queryParameters["cap"]
            if (providerId.isNullOrBlank() || capParam.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'provider' or 'cap' query param"))
                return@get
            }
            val capability = try {
                VoiceCapability.valueOf(capParam.uppercase())
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid capability: $capParam"))
                return@get
            }
            val models = registry.getModels(providerId, capability)
            call.respond(models)
        }

        // ── Voice preview — synthesize a short sample ──
        post("/preview") {
            val request = try {
                call.receive<PreviewRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
                return@post
            }

            val client = httpClient ?: HttpClient()

            // Map S2S providers to their TTS equivalents for preview
            val ttsProvider = when (request.provider) {
                "gemini_live" -> "gemini_live"
                "openai_realtime" -> "openai_tts"
                else -> request.provider
            }

            val result = TtsSynthesizer.synthesize(
                providerId = ttsProvider,
                voice = request.voice,
                text = request.text,
                client = client,
            )

            if (result == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "TTS synthesis failed"))
                return@post
            }

            call.respond(result)
        }
    }
}
