package dev.promethe.gateway.voice

import dev.promethe.api.voice.AudioChunk
import dev.promethe.api.voice.ToolSchema
import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * VoiceRelay — abstraction for any voice provider relay.
 *
 * Implementations:
 * - [GeminiLiveRelay]: S2S via Gemini Live API
 * - OpenAIRealtimeRelay: S2S via OpenAI Realtime API
 * - SttAgentTtsRelay: Pipeline STT → Agent → TTS
 *
 * The relay receives audio from the client, forwards it to the provider,
 * and emits [VoiceEvent]s back (audio out, transcripts, tool calls, etc.).
 *
 * Tool schemas are injected at connect() time. Public S2S sessions expose only
 * `promethe_agent`, which delegates tasks to the normal secured agent loop.
 */
interface VoiceRelay {
    /** Stream of events from the provider → client. */
    val events: SharedFlow<VoiceEvent>

    /**
     * Connect to the voice provider and start the session.
     *
     * @param config Session configuration (model, voice, system instructions)
     * @param toolSchemas Tools to expose to the voice model (from ToolRegistry)
     * @param scope Coroutine scope for the relay lifecycle
     */
    suspend fun connect(
        config: VoiceSessionConfig,
        toolSchemas: List<ToolSchema>,
        scope: CoroutineScope,
    )

    /**
     * Send an audio chunk from the client microphone to the provider.
     */
    suspend fun sendAudio(chunk: AudioChunk)

    /**
     * Send a tool execution result back to the provider.
     * Called after [ActionExecutor] processes a [VoiceEvent.ToolCall].
     */
    suspend fun sendToolResponse(
        callId: String,
        toolName: String,
        result: String,
    )

    /**
     * Disconnect from the provider and clean up resources.
     */
    suspend fun disconnect()
}
