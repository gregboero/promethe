package dev.promethe.gateway.voice

import dev.promethe.api.voice.AudioChunk
import dev.promethe.api.voice.ToolSchema
import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.Log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GeminiLiveRelay — WebSocket relay to Gemini Live API.
 *
 * Architecture: Gateway ↔ WS ↔ Gemini Multimodal Live
 *
 * Handles:
 * - Setup message with model, voice, system instructions
 * - Audio relay (PCM base64 in/out)
 * - Tool call interception (runs tools locally, returns results)
 * - Barge-in (interrupt on user speech)
 * - Auto-reconnect on disconnect
 */
class GeminiLiveRelay(
    private val httpClient: HttpClient,
    private val apiKey: String,
) : VoiceRelay {
    private val logger = Log.create("GeminiLiveRelay")
    private val json = Json { ignoreUnknownKeys = true }

    private var session: WebSocketSession? = null
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events

    private var relayJob: Job? = null

    // Diagnostic: capture audio for WAV dump
    private val audioDumpBuffer = ByteArrayOutputStream()
    private var audioDumpSampleRate = 24000
    private var audioChunkCount = 0

    /**
     * Connect to Gemini Live API and start relaying.
     */
    override suspend fun connect(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
        scope: CoroutineScope,
    ) {
        val wsUrl = buildGeminiWsUrl(config.model)

        relayJob = scope.launch {
            var attempt = 0
            val maxRetries = 3

            while (attempt < maxRetries) {
                attempt++
                var setupSuccess = false
                var permanentClosure = false

                try {
                    httpClient.webSocket(urlString = wsUrl) {
                        session = this
                        logger.info { "Connected to Gemini Live API (model=${config.model}) [attempt $attempt/$maxRetries]" }

                        // Send setup message immediately (includes tool declarations)
                        val setupMsg = buildSetupMessage(config, toolSchemas)
                        send(Frame.Text(setupMsg))
                        logger.info { "Setup sent with ${toolSchemas.size} tools" }

                        // Receive loop
                        var frameCount = 0
                        for (frame in incoming) {
                            frameCount++
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    if (frameCount <= 10) {
                                        logger.info { "Gemini frame #$frameCount (Text, ${text.length} chars): ${text.take(500)}" }
                                    } else if (frameCount % 50 == 0) {
                                        logger.info { "Gemini frame #$frameCount (Text, ${text.length} chars)" }
                                    }
                                    processGeminiMessage(text)
                                }

                                is Frame.Binary -> {
                                    val rawBytes = frame.readBytes()
                                    if (rawBytes.isEmpty()) continue

                                    // Gemini Live may send JSON responses as binary frames.
                                    // Try to parse as JSON first; treat as PCM audio only if not JSON.
                                    val asText = try {
                                        rawBytes.decodeToString()
                                    } catch (_: Exception) {
                                        null
                                    }
                                    if (asText != null && asText.trimStart().startsWith("{")) {
                                        // It's JSON — process as a Gemini message
                                        if (frameCount <= 10) {
                                            logger.info { "Gemini frame #$frameCount (Binary→JSON, ${asText.length} chars): ${asText.take(500)}" }
                                        } else if (frameCount % 50 == 0) {
                                            logger.info { "Gemini frame #$frameCount (Binary→JSON, ${asText.length} chars)" }
                                        }
                                        processGeminiMessage(asText)
                                    } else {
                                        // Raw PCM audio from Gemini Live
                                        if (frameCount <= 10) {
                                            logger.info { "Gemini frame #$frameCount (Binary→PCM, ${rawBytes.size} bytes)" }
                                        } else if (frameCount % 50 == 0) {
                                            logger.info { "Gemini frame #$frameCount (Binary→PCM, ${rawBytes.size} bytes)" }
                                        }
                                        val base64 = kotlin.io.encoding.Base64.encode(rawBytes)
                                        _events.emit(
                                            VoiceEvent.AudioOut(
                                                AudioChunk(
                                                    data = base64,
                                                    sampleRate = 24000,
                                                    encoding = "pcm16",
                                                ),
                                            ),
                                        )
                                    }
                                }

                                is Frame.Close -> {
                                    val reason = frame.readReason()
                                    logger.warn { "Gemini sent Close frame: code=${reason?.code}, reason=${reason?.message}" }
                                }

                                else -> {
                                    logger.debug { "Gemini frame #$frameCount (${frame.frameType}, ${frame.data.size} bytes)" }
                                }
                            }
                        }

                        // Log close reason after loop exits
                        val reason = closeReason.await()
                        logger.info { "Gemini WS close reason: code=${reason?.code}, message=${reason?.message}" }
                        logger.info { "Total frames received from Gemini: $frameCount" }
                        permanentClosure = reason?.code?.toInt() == 1008
                        if (permanentClosure) {
                            _events.emit(
                                VoiceEvent.Error(
                                    message = "Gemini rejected the voice model or session policy; update the configuration",
                                ),
                            )
                        }

                        // If we received frames beyond the setup, the session was successful
                        setupSuccess = frameCount > 0
                        if (!setupSuccess && reason?.code?.toInt() == 1007) {
                            logger.warn { "Setup rejected (1007), will retry ($attempt/$maxRetries)" }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Gemini Live connection error [attempt $attempt]" }
                    if (attempt >= maxRetries) {
                        _events.emit(VoiceEvent.Error(message = "Connection lost: ${e.message}"))
                    }
                } finally {
                    session = null
                }

                if (permanentClosure || setupSuccess || attempt >= maxRetries) {
                    if (!setupSuccess) {
                        logger.error { "Gemini Live setup failed after $maxRetries attempts" }
                        _events.emit(VoiceEvent.Error(message = "Gemini rejected setup after $maxRetries attempts"))
                    }
                    logger.info { "Gemini Live session closed" }
                    break
                }

                // Backoff before retry
                val delayMs = attempt * 500L
                logger.info { "Retrying Gemini connection in ${delayMs}ms..." }
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    /**
     * Send audio chunk to Gemini.
     */
    override suspend fun sendAudio(chunk: AudioChunk) {
        val msg = buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject {
                    put(
                        "audio",
                        buildJsonObject {
                            put("data", chunk.data)
                            put("mimeType", "audio/pcm;rate=${chunk.sampleRate}")
                        },
                    )
                },
            )
        }
        session?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), msg)))
    }

    /**
     * Send a tool response back to Gemini.
     */
    override suspend fun sendToolResponse(
        callId: String,
        toolName: String,
        result: String,
    ) {
        session?.send(Frame.Text(buildToolResponse(callId, toolName, result)))
    }

    internal fun buildToolResponse(
        callId: String,
        toolName: String,
        result: String,
    ): String {
        val msg = buildJsonObject {
            put(
                "toolResponse",
                buildJsonObject {
                    put(
                        "functionResponses",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", callId)
                                    put("name", toolName)
                                    put(
                                        "response",
                                        buildJsonObject {
                                            put("output", result)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return json.encodeToString(JsonElement.serializer(), msg)
    }

    /**
     * Disconnect from Gemini.
     */
    override suspend fun disconnect() {
        relayJob?.cancel()
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnect"))
        session = null
    }

    // ── Internal ──

    private fun buildGeminiWsUrl(model: String): String = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

    internal fun buildSetupMessage(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
    ): String {
        val isTranslateModel = config.model.contains("translate", ignoreCase = true)

        val msg = buildJsonObject {
            put(
                "setup",
                buildJsonObject {
                    put("model", "models/${config.model}")

                    if (isTranslateModel) {
                        // Translation models have a simplified config:
                        // No speechConfig, no systemInstruction, no tools.
                        // Requires translationConfig with targetLanguageCode.
                        put(
                            "generationConfig",
                            buildJsonObject {
                                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                                put("inputAudioTranscription", buildJsonObject {})
                                put("outputAudioTranscription", buildJsonObject {})
                                val targetLang = config.extras["targetLanguage"] ?: "fr"
                                put(
                                    "translationConfig",
                                    buildJsonObject {
                                        put("targetLanguageCode", targetLang)
                                        put("echoTargetLanguage", true)
                                    },
                                )
                            },
                        )
                        logger.info { "Translate model setup: targetLanguage=${config.extras["targetLanguage"] ?: "fr"}, no tools/systemInstruction" }
                    } else if (config.mode == "stt") {
                        // STT-only mode: respond with TEXT, no audio output
                        put(
                            "generationConfig",
                            buildJsonObject {
                                put("responseModalities", buildJsonArray { add(JsonPrimitive("TEXT")) })
                            },
                        )
                        // System instruction for dictation
                        put(
                            "systemInstruction",
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "text",
                                                    config.systemInstructions
                                                        ?: "Tu es un assistant de dictée. Transcris fidèlement ce que l'utilisateur dit, sans ajouter de commentaires.",
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        // Enable real-time input transcription for STT
                        put("inputAudioTranscription", buildJsonObject {})
                    } else {
                        // Standard S2S Live API models, including Gemini 3.1 Flash Live.
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
                                                        put("voiceName", config.voice)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        if (config.systemInstructions != null) {
                            put(
                                "systemInstruction",
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("text", config.systemInstructions)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                        // Inject tool declarations for models that support function calling
                        if (toolSchemas.isNotEmpty()) {
                            put("tools", ToolSchemaExporter.toGeminiTools(toolSchemas))
                            logger.info { "Tools injected: ${toolSchemas.size} tool declarations" }
                        }
                        // Enable real-time transcription (session-level)
                        put("inputAudioTranscription", buildJsonObject {})
                        put("outputAudioTranscription", buildJsonObject {})
                    }
                },
            )
        }
        return json.encodeToString(JsonElement.serializer(), msg)
    }

    private suspend fun processGeminiMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject

            // Setup complete
            if (obj.containsKey("setupComplete")) {
                logger.info { "Gemini Live setup complete" }
                return
            }

            // Server content (audio or text)
            obj["serverContent"]?.jsonObject?.let { sc ->
                if (sc.isEmpty()) {
                    logger.warn { "Gemini sent empty serverContent" }
                    return@let
                }
                val modelTurn = sc["modelTurn"]?.jsonObject
                modelTurn?.get("parts")?.jsonArray?.forEach { part ->
                    val partObj = part.jsonObject

                    // Text response
                    partObj["text"]?.jsonPrimitive?.content?.let { text ->
                        _events.emit(VoiceEvent.Transcript(text = text, isFinal = true, speaker = "model"))
                    }

                    // Inline audio data
                    partObj["inlineData"]?.jsonObject?.let { inlineData ->
                        val audioData = inlineData["data"]?.jsonPrimitive?.content ?: return@let
                        _events.emit(
                            VoiceEvent.AudioOut(
                                AudioChunk(data = audioData, sampleRate = 24000),
                            ),
                        )
                    }
                }

                // User transcription (inputTranscription)
                sc["inputTranscription"]?.jsonObject?.let { trans ->
                    val transcriptText = trans["text"]?.jsonPrimitive?.content ?: ""
                    if (transcriptText.isNotBlank()) {
                        _events.emit(
                            VoiceEvent.Transcript(
                                text = transcriptText,
                                isFinal = true,
                                speaker = "user",
                            ),
                        )
                    }
                }

                // Model transcription (outputTranscription)
                sc["outputTranscription"]?.jsonObject?.let { trans ->
                    val transcriptText = trans["text"]?.jsonPrimitive?.content ?: ""
                    val isFinal = trans["finished"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (transcriptText.isNotBlank()) {
                        _events.emit(
                            VoiceEvent.Transcript(
                                text = transcriptText,
                                isFinal = isFinal,
                                speaker = "model",
                            ),
                        )
                    }
                }

                // Turn complete
                if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                    _events.emit(VoiceEvent.TurnComplete)
                }

                // Interrupted
                if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                    _events.emit(VoiceEvent.Interrupted)
                }
            }

            // Tool calls
            obj["toolCall"]?.jsonObject?.let { tc ->
                tc["functionCalls"]?.jsonArray?.forEach { call ->
                    val callObj = call.jsonObject
                    val id = callObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = callObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = callObj["args"]?.jsonObject ?: buildJsonObject {}
                    _events.emit(VoiceEvent.ToolCall(id = id, name = name, args = args))
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Gemini message" }
        }
    }
}
