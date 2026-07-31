package dev.promethe.gateway.voice

import dev.promethe.api.voice.AudioChunk
import dev.promethe.api.voice.ToolSchema
import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.Log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * OpenAIRealtimeRelay — WebSocket relay to OpenAI Realtime API.
 *
 * Architecture: Gateway ↔ WS ↔ OpenAI Realtime
 *
 * Handles:
 * - Session update with model, voice, system instructions, tools
 * - Audio relay (PCM16 base64 in/out)
 * - Tool call interception (runs tools locally, returns results)
 * - Barge-in (interrupt on user speech via server VAD)
 * - Transcript streaming
 */
class OpenAIRealtimeRelay(
    private val httpClient: HttpClient,
    private val apiKey: String,
) : VoiceRelay {
    private val logger = Log.create("OpenAIRealtimeRelay")
    private val json = Json { ignoreUnknownKeys = true }

    private var session: WebSocketSession? = null
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events

    private var relayJob: Job? = null

    /**
     * Connect to OpenAI Realtime API and start relaying.
     */
    override suspend fun connect(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
        scope: CoroutineScope,
    ) {
        val wsUrl = buildOpenAIWsUrl(config.model)

        relayJob = scope.launch {
            try {
                httpClient.webSocket(
                    urlString = wsUrl,
                    request = {
                        header("Authorization", "Bearer $apiKey")
                    },
                ) {
                    session = this
                    logger.info { "Connected to OpenAI Realtime API (model=${config.model})" }

                    // Check if connection is already closing
                    val earlyClose = closeReason.isCompleted
                    if (earlyClose) {
                        val reason = closeReason.await()
                        logger.warn { "OpenAI WS already closing on entry: code=${reason?.code}, reason=${reason?.message}" }
                        return@webSocket
                    }

                    // Send session.update message (includes tool declarations)
                    try {
                        val sessionUpdate = buildSessionUpdate(config, toolSchemas)
                        send(Frame.Text(sessionUpdate))
                        logger.info { "Session update sent with ${toolSchemas.size} tools" }
                    } catch (e: Exception) {
                        val reason = try {
                            closeReason.await()
                        } catch (_: Exception) {
                            null
                        }
                        logger.warn(e) { "Failed to send session update: ${e.message}, close reason: code=${reason?.code}, reason=${reason?.message}" }
                        return@webSocket
                    }

                    // Receive loop
                    var frameCount = 0
                    for (frame in incoming) {
                        frameCount++
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                processServerEvent(text)
                            }

                            else -> {}
                        }
                    }

                    val reason = closeReason.await()
                    logger.info { "OpenAI receive loop ended after $frameCount frames, close reason: code=${reason?.code}, reason=${reason?.message}" }
                }
            } catch (e: CancellationException) {
                logger.info { "OpenAI Realtime cancelled" }
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "OpenAI Realtime connection error: ${e.message}" }
                _events.emit(VoiceEvent.Error(message = "Connection lost: ${e.message}"))
            } finally {
                session = null
                logger.info { "OpenAI Realtime session closed" }
            }
        }
    }

    /**
     * Send audio chunk to OpenAI Realtime (input_audio_buffer.append).
     */
    override suspend fun sendAudio(chunk: AudioChunk) {
        val audioBytes = try {
            java.util.Base64.getDecoder().decode(chunk.data)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to decode base64 audio chunk data" }
            return
        }

        val processedBytes = if (chunk.sampleRate == 16000) {
            resample16To24kHz(audioBytes)
        } else {
            audioBytes
        }

        val base64Data = java.util.Base64.getEncoder().encodeToString(processedBytes)
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", base64Data)
        }
        session?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), msg)))
    }

    /**
     * Send a tool response back to OpenAI Realtime.
     *
     * Two-step protocol:
     * 1. conversation.item.create with function_call_output
     * 2. response.create to resume generation
     */
    override suspend fun sendToolResponse(
        callId: String,
        toolName: String,
        result: String,
    ) {
        logger.debug { "Returning result for OpenAI realtime tool '$toolName'" }
        // Step 1: Submit tool output
        val itemCreate = buildJsonObject {
            put("type", "conversation.item.create")
            put(
                "item",
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                },
            )
        }
        session?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), itemCreate)))

        // Step 2: Resume model generation
        val responseCreate = buildJsonObject {
            put("type", "response.create")
        }
        session?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), responseCreate)))
    }

    /**
     * Disconnect from OpenAI Realtime.
     */
    override suspend fun disconnect() {
        relayJob?.cancel()
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnect"))
        session = null
    }

    // ── Internal ──

    private fun buildOpenAIWsUrl(model: String): String {
        val isTranslate = model.contains("translate", ignoreCase = true)
        val basePath = if (isTranslate) "/v1/realtime/translations" else "/v1/realtime"
        return "wss://api.openai.com$basePath?model=$model"
    }

    private fun isTranslateModel(model: String): Boolean = model.contains("translate", ignoreCase = true)

    private fun buildSessionUpdate(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
    ): String {
        val translate = isTranslateModel(config.model)
        val msg = if (translate) {
            buildTranslateSessionUpdate(config)
        } else {
            buildRealtimeSessionUpdate(config, toolSchemas)
        }
        logger.info { "Session update JSON: ${json.encodeToString(JsonElement.serializer(), msg).take(500)}" }
        return json.encodeToString(JsonElement.serializer(), msg)
    }

    /**
     * Build session.update for gpt-realtime-translate.
     * Translate mode uses /v1/realtime/translations endpoint.
     * No tools, no instructions — translation starts automatically on audio input.
     * Only needs output language configuration.
     */
    private fun buildTranslateSessionUpdate(config: VoiceSessionConfig): JsonObject =
        buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("type", "realtime")
                    put(
                        "audio",
                        buildJsonObject {
                            put(
                                "input",
                                buildJsonObject {
                                    put(
                                        "transcription",
                                        buildJsonObject {
                                            put("model", "gpt-4o-mini-transcribe")
                                        },
                                    )
                                },
                            )
                            put(
                                "output",
                                buildJsonObject {
                                    // Default target language from config or French
                                    put("language", config.extras["targetLanguage"] ?: "fr")
                                },
                            )
                        },
                    )
                },
            )
        }

    /**
     * Build session.update for gpt-realtime-2 (standard conversational model).
     * Full configuration with audio format, VAD, tools, and instructions.
     */
    private fun buildRealtimeSessionUpdate(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
    ): JsonObject =
        buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("type", "realtime")
                    put(
                        "audio",
                        buildJsonObject {
                            put(
                                "input",
                                buildJsonObject {
                                    put(
                                        "format",
                                        buildJsonObject {
                                            put("type", "audio/pcm")
                                            put("rate", 24000)
                                        },
                                    )
                                    put(
                                        "transcription",
                                        buildJsonObject {
                                            put("model", "gpt-4o-mini-transcribe")
                                        },
                                    )
                                    put(
                                        "turn_detection",
                                        buildJsonObject {
                                            put("type", "server_vad")
                                            put("threshold", 0.6)
                                            put("prefix_padding_ms", 300)
                                            put("silence_duration_ms", 500)
                                        },
                                    )
                                },
                            )
                            put(
                                "output",
                                buildJsonObject {
                                    put(
                                        "format",
                                        buildJsonObject {
                                            put("type", "audio/pcm")
                                            put("rate", 24000)
                                        },
                                    )
                                    put("voice", mapVoiceToOpenAI(config.voice))
                                },
                            )
                        },
                    )
                    val instructions = buildString {
                        if (config.systemInstructions != null) {
                            append(config.systemInstructions)
                            append("\n\n")
                        }
                        append("You are a helpful assistant. You must respond in the same language as the user. If the user speaks French, you must respond in French.")
                    }
                    put("instructions", instructions)
                    if (toolSchemas.isNotEmpty()) {
                        put("tools", ToolSchemaExporter.toOpenAITools(toolSchemas))
                    }
                },
            )
        }

    private fun mapVoiceToOpenAI(voice: String): String {
        val voiceLower = voice.lowercase()
        val validVoices = setOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar")
        if (voiceLower in validVoices) return voiceLower

        return when (voiceLower) {
            "puck" -> "ash"
            "charon" -> "echo"
            "kore" -> "coral"
            "fenrir" -> "echo"
            "aoede" -> "shimmer"
            "leda" -> "shimmer"
            "orus" -> "alloy"
            "zephyr" -> "sage"
            "nova" -> "ash"
            "fable" -> "coral"
            "onyx" -> "echo"
            else -> "alloy"
        }
    }

    private suspend fun processServerEvent(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                // Session confirmed
                "session.created", "session.updated" -> {
                    logger.info { "OpenAI Realtime session event: $type" }
                }

                // Audio output delta (base64 PCM)
                "response.audio.delta", "response.output_audio.delta" -> {
                    val delta = obj["delta"]?.jsonPrimitive?.content ?: return
                    _events.emit(
                        VoiceEvent.AudioOut(
                            AudioChunk(data = delta, sampleRate = 24000, encoding = "pcm16"),
                        ),
                    )
                }

                // Model transcript delta
                "response.audio_transcript.delta", "response.output_audio_transcript.delta" -> {
                    val delta = obj["delta"]?.jsonPrimitive?.content ?: return
                    _events.emit(
                        VoiceEvent.Transcript(text = delta, isFinal = false, speaker = "model"),
                    )
                }

                // Model transcript done
                "response.audio_transcript.done", "response.output_audio_transcript.done" -> {
                    val transcript = obj["transcript"]?.jsonPrimitive?.content
                    if (transcript != null) {
                        _events.emit(
                            VoiceEvent.Transcript(text = transcript, isFinal = true, speaker = "model"),
                        )
                    }
                }

                // User input transcript (from input_audio_transcription)
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = obj["transcript"]?.jsonPrimitive?.content
                    if (transcript != null) {
                        _events.emit(
                            VoiceEvent.Transcript(text = transcript, isFinal = true, speaker = "user"),
                        )
                    }
                }

                // Tool call completed
                "response.function_call_arguments.done" -> {
                    val callId = obj["call_id"]?.jsonPrimitive?.content ?: ""
                    val name = obj["name"]?.jsonPrimitive?.content ?: ""
                    val argsStr = obj["arguments"]?.jsonPrimitive?.content ?: "{}"
                    val args = try {
                        json.parseToJsonElement(argsStr).jsonObject
                    } catch (_: Exception) {
                        buildJsonObject {}
                    }
                    _events.emit(VoiceEvent.ToolCall(id = callId, name = name, args = args))
                }

                // Turn complete
                "response.done" -> {
                    _events.emit(VoiceEvent.TurnComplete)
                }

                // Interrupted / cancelled
                "response.cancelled" -> {
                    _events.emit(VoiceEvent.Interrupted)
                }

                // Input audio committed (server VAD detected end of speech)
                "input_audio_buffer.committed" -> {
                    logger.debug { "Input audio committed" }
                }

                // Speech started (server VAD)
                "input_audio_buffer.speech_started" -> {
                    logger.debug { "Speech started (VAD)" }
                }

                // Speech stopped (server VAD)
                "input_audio_buffer.speech_stopped" -> {
                    logger.debug { "Speech stopped (VAD)" }
                }

                // Error from OpenAI
                "error" -> {
                    val errorObj = obj["error"]?.jsonObject
                    val message = errorObj?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                    val code = errorObj?.get("code")?.jsonPrimitive?.content
                    logger.warn { "OpenAI Realtime error: $message (code=$code)" }
                    _events.emit(VoiceEvent.Error(message = message, code = code))
                }

                else -> {
                    logger.debug { "Unhandled OpenAI event: $type" }
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse OpenAI message" }
        }
    }

    private fun resample16To24kHz(inputBytes: ByteArray): ByteArray {
        val shortCount = inputBytes.size / 2
        val input = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            val low = inputBytes[i * 2].toInt() and 0xFF
            val high = inputBytes[i * 2 + 1].toInt()
            input[i] = ((high shl 8) or low).toShort()
        }

        val outputCount = (shortCount * 3) / 2
        val output = ShortArray(outputCount)
        for (j in 0 until outputCount) {
            val iFloat = j * 2.0f / 3.0f
            val lowIndex = iFloat.toInt()
            val highIndex = if (lowIndex + 1 < shortCount) lowIndex + 1 else lowIndex
            val weight = iFloat - lowIndex
            val interpolated = input[lowIndex] * (1.0f - weight) + input[highIndex] * weight
            output[j] = interpolated.toInt().coerceIn(-32768, 32767).toShort()
        }

        val outputBytes = ByteArray(outputCount * 2)
        for (i in 0 until outputCount) {
            val value = output[i].toInt()
            outputBytes[i * 2] = (value and 0xFF).toByte()
            outputBytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return outputBytes
    }
}
