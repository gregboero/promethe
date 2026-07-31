package dev.promethe.gateway.voice

import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * SttProvider — runtime interface for STT backends used by [SttAgentTtsRelay].
 *
 * Each implementation handles:
 * - Connection lifecycle (streaming WebSocket or batch setup)
 * - Receiving raw PCM audio bytes
 * - Producing transcripts (emitted via [events])
 *
 * This is distinct from [VoiceProvider] in [VoiceProviderRegistry], which
 * only describes provider metadata (models, voices, API key requirements).
 */
internal interface SttProvider {
    /**
     * Start the STT session.
     *
     * @param config Voice session configuration (sample rate, etc.)
     * @param scope Coroutine scope for background work
     * @param onPartialTranscript Called for every transcript result (including partials)
     *   with `(text, isFinal)`. Useful for showing real-time captions to the user.
     * @param onFinalTranscript Called when a final transcript is ready for the agent pipeline
     */
    suspend fun start(
        config: VoiceSessionConfig,
        scope: CoroutineScope,
        onPartialTranscript: (suspend (text: String, isFinal: Boolean) -> Unit)? = null,
        onFinalTranscript: suspend (text: String) -> Unit,
    )

    /**
     * Feed raw PCM audio bytes to the STT provider.
     *
     * Streaming providers forward immediately; batch providers accumulate
     * and flush after a silence timeout.
     */
    suspend fun sendAudio(audioBytes: ByteArray)

    /**
     * Stop the STT session and release resources.
     */
    suspend fun stop()
}
