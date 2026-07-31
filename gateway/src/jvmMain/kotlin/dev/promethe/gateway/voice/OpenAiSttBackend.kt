package dev.promethe.gateway.voice

import dev.promethe.core.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * OpenAiSttBackend — transcribes PCM audio using OpenAI Whisper REST API.
 *
 * POST `https://api.openai.com/v1/audio/transcriptions`
 *
 * Audio is wrapped in a minimal WAV header before upload, since Whisper
 * expects a file attachment (not raw PCM).
 */
internal class OpenAiSttBackend(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String,
) {
    private val logger = Log.create("OpenAiSttBackend")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribe PCM audio using OpenAI Whisper REST API.
     * POST https://api.openai.com/v1/audio/transcriptions
     */
    suspend fun transcribe(pcmBytes: ByteArray): String {
        // Whisper expects a file upload — we send raw WAV
        val wavBytes = pcmToWav(pcmBytes, sampleRate = 16000, channels = 1, bitsPerSample = 16)

        val response = client.post("https://api.openai.com/v1/audio/transcriptions") {
            header("Authorization", "Bearer $apiKey")
            setBody(
                io.ktor.client.request.forms.MultiPartFormDataContent(
                    io.ktor.client.request.forms.formData {
                        append("model", model)
                        append(
                            "file",
                            wavBytes,
                            io.ktor.http.Headers.build {
                                append(io.ktor.http.HttpHeaders.ContentType, "audio/wav")
                                append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                            },
                        )
                    },
                ),
            )
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.bodyAsText()
            logger.warn { "OpenAI Whisper error (${response.status}): $errorBody" }
            return ""
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["text"]?.jsonPrimitive?.content ?: ""
    }
}
