package dev.promethe.gateway.voice

import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BatchSttProvider — accumulates PCM audio and flushes after a silence timeout.
 *
 * Used for REST-based STT providers (OpenAI Whisper, Google Gemini multimodal)
 * that don't support streaming WebSocket input. Audio is buffered in memory
 * and sent as a single batch when the user pauses speaking.
 *
 * @param transcriber A suspend function that takes raw PCM bytes and returns a transcript.
 *   Typically wraps [OpenAiSttBackend.transcribe].
 * @param providerName Human-readable name for logging (e.g. "OpenAI STT", "Google STT")
 */
internal class BatchSttProvider(
    private val transcriber: suspend (ByteArray) -> String,
    private val providerName: String,
) : SttProvider {
    private val logger = Log.create("BatchSttProvider[$providerName]")

    // Batch STT buffer
    private val audioBuffer = java.io.ByteArrayOutputStream()
    private var silenceTimer: Job? = null
    private var scope: CoroutineScope? = null
    private var onPartial: (suspend (String, Boolean) -> Unit)? = null
    private var onFinal: (suspend (String) -> Unit)? = null
    private var speechDetected = false

    companion object {
        /** Flush after 1.5s of silence */
        private const val SILENCE_TIMEOUT_MS = 1500L

        /** RMS threshold below which audio is considered silence.
         *  16-bit PCM range is -32768..32767; ~500 covers typical mic noise. */
        private const val SILENCE_RMS_THRESHOLD = 500.0

        /** Compute RMS (root mean square) of 16-bit little-endian PCM audio. */
        fun computeRms(pcmBytes: ByteArray): Double {
            if (pcmBytes.size < 2) return 0.0
            val sampleCount = pcmBytes.size / 2
            var sumSquares = 0.0
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo // 16-bit LE signed
                sumSquares += sample.toDouble() * sample.toDouble()
            }
            return kotlin.math.sqrt(sumSquares / sampleCount)
        }
    }

    override suspend fun start(
        config: VoiceSessionConfig,
        scope: CoroutineScope,
        onPartialTranscript: (suspend (text: String, isFinal: Boolean) -> Unit)?,
        onFinalTranscript: suspend (text: String) -> Unit,
    ) {
        this.scope = scope
        this.onPartial = onPartialTranscript
        this.onFinal = onFinalTranscript
        logger.info { "$providerName: batch mode, silence timeout=${SILENCE_TIMEOUT_MS}ms" }
        // Audio buffering happens in sendAudio(), transcription in flush()
    }

    /**
     * Accumulate audio bytes and reset the silence timer only if speech is detected.
     *
     * The mic sends chunks continuously (even silence), so we use a simple
     * energy-based VAD: only reset the silence timer when the RMS of the
     * chunk is above a noise threshold. This way the timer fires after
     * ~1.5s of actual silence.
     */
    override suspend fun sendAudio(audioBytes: ByteArray) {
        synchronized(audioBuffer) {
            audioBuffer.write(audioBytes)
        }

        // Simple energy-based VAD: compute RMS of 16-bit LE PCM samples
        val isSpeech = computeRms(audioBytes) > SILENCE_RMS_THRESHOLD
        if (isSpeech) {
            speechDetected = true
            // Speech detected — reset the silence timer
            silenceTimer?.cancel()
            silenceTimer = scope?.launch {
                delay(SILENCE_TIMEOUT_MS)
                flush()
            }
        }
        // Only start silence timer after speech was detected —
        // don't flush pre-speech noise
        // If silence but timer already running → let it fire naturally
    }

    override suspend fun stop() {
        silenceTimer?.cancel()
        // Flush any remaining audio before stopping — the silence timer
        // may not have fired yet when the user disconnects
        flush()
        synchronized(audioBuffer) { audioBuffer.reset() }
        scope = null
        onPartial = null
        onFinal = null
        speechDetected = false
    }

    /**
     * Flush the accumulated audio buffer to the configured batch STT API.
     * Called after [SILENCE_TIMEOUT_MS] of silence.
     */
    private suspend fun flush() {
        val pcmBytes: ByteArray
        synchronized(audioBuffer) {
            pcmBytes = audioBuffer.toByteArray()
            audioBuffer.reset()
        }
        if (pcmBytes.size < 16000) return // < 500ms of 16kHz audio — skip fragments

        logger.info { "Flushing batch STT: ${pcmBytes.size} bytes (${pcmBytes.size / 32}ms @ 16kHz)" }

        try {
            val transcript = transcriber(pcmBytes)

            if (transcript.isNotBlank()) {
                // Call callbacks directly (not via scope?.launch) to ensure
                // they execute even when called from stop() during disconnect
                onPartial?.invoke(transcript, true)
                onFinal?.invoke(transcript)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Batch STT transcription failed ($providerName)" }
        }
    }
}
