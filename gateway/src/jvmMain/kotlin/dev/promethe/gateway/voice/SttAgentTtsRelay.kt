package dev.promethe.gateway.voice

import dev.promethe.api.voice.AudioChunk
import dev.promethe.api.voice.ToolSchema
import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.AgentExecutionOrigin
import dev.promethe.core.AgentExecutionRequest
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.CredentialsStore
import dev.promethe.core.Log
import dev.promethe.core.config.ConfigProvider
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * SttAgentTtsRelay — pipeline relay for non-S2S voice providers.
 *
 * Architecture: Client → [STT] → transcript → [Agent] → response → [TTS] → audio
 *
 * Unlike [GeminiLiveRelay] which does end-to-end audio via a single S2S model,
 * this relay composes three separate stages:
 * 1. **STT** — Deepgram streaming WebSocket: PCM audio → text transcript
 * 2. **Agent** — [AgentExecutionPort]: text → secured agent trajectory → response text
 * 3. **TTS** — ElevenLabs/OpenAI HTTP API: text → PCM audio
 *
 * Tool schemas are ignored — the agent already has all tools available
 * through [AgentExecutionPort]. Tool calls are handled internally, never
 * surfaced to the client via [VoiceEvent.ToolCall].
 */
class SttAgentTtsRelay(
    private val config: VoiceSessionConfig,
    private val registry: VoiceProviderRegistry,
    private val database: PrometheDatabaseApi,
    private val executionService: AgentExecutionPort,
    private val sessionId: String,
) : VoiceRelay {
    private val logger = Log.create("SttAgentTtsRelay")

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events

    private var sttProvider: SttProvider? = null
    private var relayJob: Job? = null
    private var httpClient: HttpClient? = null

    // Provider API keys (resolved at connect time)
    private var ttsApiKey: String? = null

    // TTS/STT provider settings (resolved from DB at connect time)
    private var ttsProviderId: String = "gemini_tts"
    private var ttsVoiceId: String = "Puck"
    private var ttsModelId: String = "gemini-3.1-flash-tts-preview"
    private var sttProviderId: String = "deepgram"

    /**
     * Connect to the STT provider and prepare the TTS pipeline.
     *
     * Tool schemas are ignored — the agent handles tools internally
     * via [AgentExecutionPort].
     */
    override suspend fun connect(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
        scope: CoroutineScope,
    ) {
        // ── Resolve providers from user settings (DB) ──
        sttProviderId = database.getSetting("voice_stt_provider")?.takeIf { it.isNotBlank() } ?: "deepgram"
        ttsProviderId = database.getSetting("voice_tts_provider")?.takeIf { it.isNotBlank() } ?: "gemini_tts"
        ttsModelId = database.getSetting("voice_tts_model")?.takeIf { it.isNotBlank() } ?: ""
        ttsVoiceId = database.getSetting("voice_tts_voice")?.takeIf { it.isNotBlank() } ?: config.voice

        // Apply voice from session config if still blank
        if (ttsVoiceId.isBlank() && config.voice.isNotBlank()) {
            ttsVoiceId = config.voice
        }

        // ── Resolve API keys via registry ──
        val sttRegistryProvider = registry.getProvider(sttProviderId)
        if (sttRegistryProvider == null) {
            _events.emit(VoiceEvent.Error("Unknown STT provider: $sttProviderId"))
            return
        }
        val sttApiKey = resolveApiKey(sttRegistryProvider.requiredSettingKey)
        if (sttApiKey.isNullOrBlank()) {
            _events.emit(VoiceEvent.Error("No API key for STT provider '$sttProviderId' (setting: ${sttRegistryProvider.requiredSettingKey})"))
            return
        }

        val ttsRegistryProvider = registry.getProvider(ttsProviderId)
        if (ttsRegistryProvider == null) {
            _events.emit(VoiceEvent.Error("Unknown TTS provider: $ttsProviderId"))
            return
        }
        ttsApiKey = resolveApiKey(ttsRegistryProvider.requiredSettingKey)
        if (ttsApiKey.isNullOrBlank()) {
            _events.emit(VoiceEvent.Error("No API key for TTS provider '$ttsProviderId' (setting: ${ttsRegistryProvider.requiredSettingKey})"))
            return
        }

        logger.info { "Pipeline relay: STT=$sttProviderId, TTS=$ttsProviderId, voice=$ttsVoiceId" }

        // ── Create HTTP client ──
        val client = HttpClient {
            install(WebSockets)
        }
        httpClient = client

        // ── Create STT provider ──
        val sttModel = config.model.takeIf { it.isNotBlank() }
        if (sttModel == null) {
            _events.emit(VoiceEvent.Error("STT model non configuré. Allez dans Paramètres > Voix > STT pour choisir un modèle."))
            return
        }
        val provider = createSttProvider(sttProviderId, client, sttApiKey, sttModel)
        if (provider == null) {
            _events.emit(VoiceEvent.Error("Unsupported STT provider: $sttProviderId"))
            return
        }
        sttProvider = provider

        // ── Start STT session ──
        relayJob = scope.launch {
            try {
                provider.start(
                    config,
                    scope,
                    onPartialTranscript = { text, isFinal ->
                        // Emit every transcript (partial or final) for real-time captions
                        _events.emit(
                            VoiceEvent.Transcript(
                                text = text,
                                isFinal = isFinal,
                                speaker = "user",
                            ),
                        )
                    },
                    onFinalTranscript = { transcript ->
                        // Trigger agent + TTS pipeline
                        processAgentAndTts(transcript)
                    },
                )
                // Keep the job alive — BatchSttProvider.start() returns immediately
                // but audio processing continues via sendAudio() callbacks.
                // This suspends until disconnect() cancels the job.
                kotlinx.coroutines.awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "STT connection error ($sttProviderId)" }
                _events.emit(VoiceEvent.Error(message = "STT connection lost: ${e.message}"))
            } finally {
                logger.info { "STT session closed ($sttProviderId)" }
            }
        }
    }

    /**
     * Send audio chunk to the active STT provider.
     */
    override suspend fun sendAudio(chunk: AudioChunk) {
        val audioBytes = kotlin.io.encoding.Base64.decode(chunk.data)
        sttProvider?.sendAudio(audioBytes)
    }

    /**
     * No-op for pipeline relay.
     *
     * Tool calls are handled internally by the agent via [ActionExecutor].
     * The S2S protocol's tool response flow does not apply here.
     */
    override suspend fun sendToolResponse(
        callId: String,
        toolName: String,
        result: String,
    ) {
        // No-op: tools are executed internally by ActionExecutor
        logger.debug { "sendToolResponse ignored in pipeline relay (callId=$callId, tool=$toolName)" }
    }

    /**
     * Disconnect from STT provider and clean up resources.
     */
    override suspend fun disconnect() {
        // Stop STT provider first — this flushes remaining audio buffer
        // before the relay job scope is cancelled
        sttProvider?.stop()
        sttProvider = null
        relayJob?.cancel()
        httpClient?.close()
        httpClient = null
        logger.info { "Pipeline relay disconnected" }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Agent — Process transcript through ActionExecutor
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the full pipeline: transcript → agent → TTS → audio events.
     */
    private suspend fun processAgentAndTts(userText: String) {
        logger.info { "Processing utterance: \"${userText.take(80)}\"" }

        // ── Agent phase ──
        val responseText = runAgent(userText)
        if (responseText.isBlank()) {
            logger.debug { "Agent returned empty response, skipping TTS" }
            _events.emit(VoiceEvent.TurnComplete)
            return
        }

        // Emit model transcript
        _events.emit(
            VoiceEvent.Transcript(
                text = responseText,
                isFinal = true,
                speaker = "model",
            ),
        )

        // ── TTS phase ──
        try {
            val audioBytes = synthesizeSpeech(responseText)
            if (audioBytes.isNotEmpty()) {
                // Emit audio in chunks (max 32KB per chunk to avoid WS frame limits)
                val chunkSize = 32_000
                var offset = 0
                while (offset < audioBytes.size) {
                    val end = minOf(offset + chunkSize, audioBytes.size)
                    val slice = audioBytes.copyOfRange(offset, end)
                    val base64 = kotlin.io.encoding.Base64.encode(slice)
                    _events.emit(
                        VoiceEvent.AudioOut(
                            AudioChunk(
                                data = base64,
                                sampleRate = ttsSampleRate(),
                                encoding = "pcm16",
                            ),
                        ),
                    )
                    offset = end
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "TTS synthesis failed" }
            _events.emit(VoiceEvent.Error(message = "TTS error: ${e.message}"))
        }

        _events.emit(VoiceEvent.TurnComplete)
    }

    /**
     * Send user text to the agent and get a response.
     *
     * Uses the normal agent loop so any tool call follows the secure executor.
     */
    private suspend fun runAgent(userText: String): String =
        try {
            executionService.executeToCompletion(
                AgentExecutionRequest(
                    sessionId = sessionId,
                    text = userText,
                    origin = AgentExecutionOrigin.VOICE,
                    channelHint = "stt-agent-tts",
                ),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Agent execution failed" }
            "I'm sorry, I encountered an error processing your request."
        }

    // ═══════════════════════════════════════════════════════════════
    //  TTS — Synthesize speech from text (delegates to TtsSynthesizer)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Synthesize speech from text using the user-configured TTS provider.
     * Delegates to [TtsSynthesizer] which supports OpenAI, ElevenLabs, and Gemini TTS.
     *
     * @return Raw PCM audio bytes
     */
    private suspend fun synthesizeSpeech(text: String): ByteArray {
        val client = httpClient ?: error("HTTP client not initialized")
        val result = TtsSynthesizer.synthesize(
            providerId = ttsProviderId,
            voice = ttsVoiceId,
            text = text,
            client = client,
        ) ?: return ByteArray(0)

        return kotlin.io.encoding.Base64.decode(result.data)
    }

    /**
     * Get the TTS sample rate for audio chunk metadata.
     */
    private fun ttsSampleRate(): Int =
        when (ttsProviderId) {
            "elevenlabs" -> 16000
            else -> 24000
        }

    // ═══════════════════════════════════════════════════════════════
    //  STT Provider Factory
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create the appropriate [SttProvider] for the given provider ID.
     */
    private fun createSttProvider(
        providerId: String,
        client: HttpClient,
        apiKey: String,
        sttModel: String,
    ): SttProvider? =
        when (providerId) {
            "deepgram" -> DeepgramSttBackend(
                client = client,
                apiKey = apiKey,
                sttModel = sttModel,
            )

            "openai_stt" -> BatchSttProvider(
                transcriber = OpenAiSttBackend(client, apiKey, sttModel)::transcribe,
                providerName = "OpenAI STT (Whisper)",
            )

            else -> null
        }

    // ═══════════════════════════════════════════════════════════════
    //  API Key Resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve an API key by checking DB settings, then credentials.json.
     */
    private suspend fun resolveApiKey(settingKey: String): String? {
        // 1. DB settings
        database.getSetting(settingKey)?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Unified config (credentials.json + env vars)
        try {
            ConfigProvider.get().get(settingKey)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
        }

        // 3. credentials.json llmApiKeys
        try {
            val creds = CredentialsStore.load()
            creds?.llmApiKeys?.forEach { (provider, key) ->
                if (key.isNotBlank() && "${provider.uppercase()}_API_KEY" == settingKey) {
                    return key
                }
            }
        } catch (_: Exception) {
        }

        return null
    }
}
