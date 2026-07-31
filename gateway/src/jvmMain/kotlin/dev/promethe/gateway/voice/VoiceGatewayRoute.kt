package dev.promethe.gateway.voice

import dev.promethe.api.voice.AudioChunk
import dev.promethe.api.voice.ToolSchema
import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.AgentExecutionOrigin
import dev.promethe.core.AgentExecutionRequest
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.Log
import dev.promethe.core.MemoryLayer
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private val logger = Log.create("VoiceGatewayRoute")

/**
 * Voice WebSocket route — `/ws/chat/voice`
 *
 * Protocol:
 * 1. Client sends VoiceSessionConfig as first text frame
 * 2. Client sends VoiceEvent.AudioIn frames (audio from mic)
 * 3. Server sends VoiceEvent.AudioOut frames (audio from provider)
 * 4. Server sends VoiceEvent.Transcript frames (text transcription)
 * 5. Server sends VoiceEvent.ToolCall, gateway executes via ActionExecutor
 * 6. Client sends VoiceEvent.SessionEnd to disconnect
 *
 * Architecture:
 * - Uses [VoiceRelayFactory] to create the right relay for the selected provider
 * - Injects [ToolSchema]s from [ToolRegistry] into the relay setup
 * - Tool calls are intercepted and executed via [ActionExecutor]
 *   (which includes `delegate_task` for complex orchestration → AIAgent system)
 */
fun Route.voiceRoutes(
    sessionManager: AudioSessionManager,
    registry: VoiceProviderRegistry,
    database: PrometheDatabaseApi,
    executionService: AgentExecutionPort,
    memoryLayer: MemoryLayer? = null,
) {
    webSocket("/ws/chat/voice") {
        val voiceSessionId = UUID.randomUUID().toString()
        val userId = call.request.queryParameters["userId"]
            ?: call.request.queryParameters["user"]
            ?: "default"

        logger.info { "Voice WS connected: $voiceSessionId" }

        // Accumulate transcripts for memory extraction (must be outside try for finally access)
        val transcriptLog = mutableListOf<String>()

        try {
            // 1. Wait for config (first text frame)
            val configFrame = incoming.receive()
            val config = if (configFrame is Frame.Text) {
                try {
                    json.decodeFromString<VoiceSessionConfig>(configFrame.readText())
                } catch (e: Exception) {
                    logger.warn { "Invalid config, using defaults" }
                    VoiceSessionConfig()
                }
            } else {
                VoiceSessionConfig()
            }

            // 1b. Enrich systemInstructions with memory context
            val enrichedConfig = if (memoryLayer != null) {
                val memoryContext = try {
                    memoryLayer.buildMemoryContext("voice conversation context")
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to load memory context for voice session" }
                    ""
                }
                config.copy(
                    systemInstructions = buildString {
                        config.systemInstructions?.let { appendLine(it) }
                        if (memoryContext.isNotBlank()) {
                            appendLine()
                            append(memoryContext)
                        }
                    }.takeIf { it.isNotBlank() },
                )
            } else {
                config
            }
            logger.info { "Voice config: provider=${enrichedConfig.provider}, model=${enrichedConfig.model}, voice=${enrichedConfig.voice}, hasMemory=${memoryLayer != null}" }

            // 2. Create relay via factory (resolves provider → implementation)
            val relay = VoiceRelayFactory.create(
                providerId = enrichedConfig.provider,
                config = enrichedConfig,
                registry = registry,
                database = database,
                executionService = executionService,
                sessionId = voiceSessionId,
            )

            if (!sessionManager.createSession(voiceSessionId, userId, relay)) {
                send(
                    Frame.Text(
                        json.encodeToString(
                            VoiceEvent.serializer(),
                            VoiceEvent.Error("Max concurrent voice sessions reached"),
                        ),
                    ),
                )
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Session limit"))
                return@webSocket
            }

            val toolSchemas = listOf(prometheAgentToolSchema())
            logger.info { "Exposed promethe_agent as the only voice task tool" }

            // 4. Connect relay to provider (with tools)
            relay.connect(enrichedConfig, toolSchemas, this)

            // 5. Forward provider events → client
            var audioOutCount = 0
            val forwardJob = launch {
                relay.events.collect { event ->
                    when (event) {
                        is VoiceEvent.ToolCall -> {
                            launch {
                                try {
                                    logger.info { "Voice tool call: ${event.name}" }
                                    require(event.name == PROMETHE_AGENT_TOOL) {
                                        "Voice tool '${event.name}' is not available"
                                    }
                                    val task = event.args["task"]?.jsonPrimitive?.content?.trim().orEmpty()
                                    require(task.isNotBlank()) { "promethe_agent requires a non-empty task" }
                                    val result = executionService.executeToCompletion(
                                        AgentExecutionRequest(
                                            sessionId = voiceSessionId,
                                            text = task,
                                            origin = AgentExecutionOrigin.VOICE,
                                            channelHint = "s2s",
                                        ),
                                    )
                                    relay.sendToolResponse(event.id, event.name, result)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Tool call failed: ${event.name}" }
                                    relay.sendToolResponse(event.id, event.name, "Error: ${e.message}")
                                }
                            }
                        }

                        is VoiceEvent.Transcript -> {
                            // Accumulate for memory extraction
                            if (event.isFinal) {
                                transcriptLog.add("[${event.speaker}] ${event.text}")
                            }
                            send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                        }

                        is VoiceEvent.AudioOut -> {
                            send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                            audioOutCount++
                            if (audioOutCount == 1) {
                                logger.info { "First AudioOut forwarded to client (rate=${event.chunk.sampleRate}, data=${event.chunk.data.length} base64 chars)" }
                            }
                        }

                        else -> {
                            send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                        }
                    }
                    sessionManager.touch(voiceSessionId)
                }
            }

            // 6. Receive client events → relay
            var audioChunksReceived = 0
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val event = try {
                        json.decodeFromString<VoiceEvent>(frame.readText())
                    } catch (e: Exception) {
                        logger.debug { "Invalid client event: ${e.message}" }
                        continue
                    }

                    when (event) {
                        is VoiceEvent.AudioIn -> {
                            relay.sendAudio(event.chunk)
                            sessionManager.touch(voiceSessionId)
                            audioChunksReceived++
                            if (audioChunksReceived == 1) {
                                logger.info { "First audio chunk received from client (size=${event.chunk.data.length} base64 chars, rate=${event.chunk.sampleRate})" }
                            } else if (audioChunksReceived % 100 == 0) {
                                logger.debug { "Audio chunks received: $audioChunksReceived" }
                            }
                        }

                        is VoiceEvent.ToolResponse -> {
                            logger.warn { "Ignoring client-supplied voice tool response ${event.id}" }
                        }

                        is VoiceEvent.SessionEnd -> {
                            break
                        }

                        else -> {}
                    }
                }
            }
            logger.info { "Voice receive loop ended (total audio chunks: $audioChunksReceived)" }

            forwardJob.cancel()
            relay.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Voice WS error" }
        } finally {
            sessionManager.removeSession(voiceSessionId)

            // Extract facts from voice transcripts into memory
            if (memoryLayer != null && transcriptLog.isNotEmpty()) {
                try {
                    // Create a voice session in DB for fact extraction
                    val now = System.currentTimeMillis()
                    database.insertSessionOrIgnore(voiceSessionId, now, """{"type":"voice"}""")
                    // Store the voice conversation as messages
                    transcriptLog.forEachIndexed { idx, line ->
                        val role = if (line.startsWith("[user]")) "user" else "assistant"
                        val content = line.substringAfter("] ")
                        database.insertMessage(voiceSessionId, role, content, now + idx)
                    }
                    val facts = memoryLayer.extractFacts(voiceSessionId)
                    logger.info { "Extracted ${facts.size} facts from voice session (${transcriptLog.size} turns)" }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to extract facts from voice session" }
                }
            }

            logger.info { "Voice WS closed: $voiceSessionId" }
        }
    }
}

internal const val PROMETHE_AGENT_TOOL = "promethe_agent"

internal fun prometheAgentToolSchema(): ToolSchema =
    ToolSchema(
        name = PROMETHE_AGENT_TOOL,
        description =
            "Delegate any task, tool use, external effect, or device action to the secured Promethe agent loop.",
        parameters =
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "task",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "The complete task to execute")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("task")) })
            },
    )
