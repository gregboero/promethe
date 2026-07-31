package dev.promethe.gateway.voice

import dev.promethe.core.Log
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val logger = Log.create("TtsRoute")

@Serializable
data class TtsSynthesizeRequest(
    val text: String,
    val provider: String? = null,
    val voice: String? = null,
    val model: String? = null,
)

/**
 * TTS synthesis routes — `/api/v1/tts/...`
 *
 * Exposes POST /api/v1/tts/synthesize for the chat UI's message-bubble TTS button.
 * Delegates to [TtsSynthesizer] which handles OpenAI, ElevenLabs, and Gemini TTS.
 */
fun Route.ttsRoutes(
    httpClient: HttpClient? = null,
) {
    route("tts") {
        post("/synthesize") {
            val request = try {
                call.receive<TtsSynthesizeRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.message}"))
                return@post
            }

            if (request.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Text is required"))
                return@post
            }

            val client = httpClient ?: HttpClient()
            val provider = request.provider ?: "openai_tts"
            val voice = request.voice ?: "alloy"

            logger.info { "TTS synthesize: provider=$provider, voice=$voice, text=${request.text.take(50)}..." }

            val result = TtsSynthesizer.synthesize(
                providerId = provider,
                voice = voice,
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
