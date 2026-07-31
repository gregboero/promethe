package dev.promethe.gateway.voice

import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.Log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * DeepgramSttBackend — streaming STT via Deepgram WebSocket.
 *
 * Opens a persistent WebSocket to `wss://api.deepgram.com/v1/listen`
 * and forwards raw PCM audio frames. Deepgram returns incremental
 * transcription results as JSON.
 *
 * Expected Deepgram JSON format:
 * ```json
 * {
 *   "channel": {
 *     "alternatives": [{ "transcript": "hello world", "confidence": 0.98 }]
 *   },
 *   "is_final": true,
 *   "speech_final": true
 * }
 * ```
 *
 * Only emits a transcript callback when both `is_final` and `speech_final` are true,
 * indicating a complete utterance boundary.
 */
internal class DeepgramSttBackend(
    private val client: HttpClient,
    private val apiKey: String,
    private val sttModel: String,
) : SttProvider {
    private val logger = Log.create("DeepgramSttBackend")
    private val json = Json { ignoreUnknownKeys = true }
    private var session: WebSocketSession? = null

    override suspend fun start(
        config: VoiceSessionConfig,
        scope: CoroutineScope,
        onPartialTranscript: (suspend (text: String, isFinal: Boolean) -> Unit)?,
        onFinalTranscript: suspend (text: String) -> Unit,
    ) {
        val sttUrl = "wss://api.deepgram.com/v1/listen" +
            "?encoding=linear16&sample_rate=${config.sampleRate}&model=$sttModel"

        client.webSocket(
            urlString = sttUrl,
            request = { header("Authorization", "Token $apiKey") },
        ) {
            session = this
            logger.info { "Connected to Deepgram STT (model=$sttModel, sampleRate=${config.sampleRate})" }
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    processResult(frame.readText(), scope, onPartialTranscript, onFinalTranscript)
                }
            }
        }
    }

    override suspend fun sendAudio(audioBytes: ByteArray) {
        try {
            session?.send(Frame.Binary(true, audioBytes))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to send audio to STT WebSocket" }
        }
    }

    override suspend fun stop() {
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnect"))
        session = null
    }

    /**
     * Process a Deepgram STT JSON result.
     *
     * Expected format:
     * ```json
     * {
     *   "channel": {
     *     "alternatives": [{ "transcript": "hello world", "confidence": 0.98 }]
     *   },
     *   "is_final": true,
     *   "speech_final": true
     * }
     * ```
     */
    private suspend fun processResult(
        text: String,
        scope: CoroutineScope,
        onPartialTranscript: (suspend (String, Boolean) -> Unit)?,
        onFinalTranscript: suspend (String) -> Unit,
    ) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject

            val channel = obj["channel"]?.jsonObject ?: return
            val alternatives = channel["alternatives"]?.jsonArray ?: return
            if (alternatives.isEmpty()) return

            val transcript = alternatives[0].jsonObject["transcript"]?.jsonPrimitive?.content ?: return
            if (transcript.isBlank()) return

            val isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull ?: false
            val speechFinal = obj["speech_final"]?.jsonPrimitive?.booleanOrNull ?: false

            // Emit every transcript (partial or final) for real-time captions
            onPartialTranscript?.invoke(transcript, isFinal)

            // Only trigger agent + TTS pipeline on final, complete utterances
            if (isFinal && speechFinal) {
                scope.launch {
                    onFinalTranscript(transcript)
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Deepgram STT result" }
        }
    }
}
