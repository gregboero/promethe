package dev.promethe.api.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Voice session models for the Promethe voice assistant.
 *
 * Architecture: Option B — Gateway as Media Proxy A2A.
 * Client ↔ WS ↔ Gateway ↔ WS ↔ Gemini Live API
 *
 * Audio format: PCM 16-bit mono, 16kHz input / 24kHz output.
 */

// ── Session Configuration ──

@Serializable
data class VoiceSessionConfig(
    /** Provider: "gemini_live", "openai_realtime" */
    val provider: String = "gemini_live",
    /** Model identifier */
    val model: String = "gemini-3.1-flash-live-preview",
    /** System instructions for the voice agent */
    val systemInstructions: String? = null,
    /** Voice name (Gemini: Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr) */
    val voice: String = "Puck",
    /** Input sample rate in Hz */
    val sampleRate: Int = 16000,
    /** Audio encoding */
    val encoding: String = "pcm16",
    /** Session mode: "s2s" (full duplex voice), "stt" (dictation — audio in, text out only) */
    val mode: String = "s2s",
    /** Extra key-value pairs (e.g. targetLanguage for translate mode) */
    val extras: Map<String, String> = emptyMap(),
)

// ── Audio Data ──

@Serializable
data class AudioChunk(
    /** Base64-encoded audio data */
    val data: String,
    /** MIME type */
    val mimeType: String = "audio/pcm",
    /** Sample rate in Hz */
    val sampleRate: Int = 16000,
    /** Encoding format */
    val encoding: String = "pcm16",
)

// ── Voice Events (bidirectional WS protocol) ──

@Serializable
sealed interface VoiceEvent {
    /** Client → Gateway: audio input from microphone */
    @Serializable
    @SerialName("audio_in")
    data class AudioIn(
        val chunk: AudioChunk,
    ) : VoiceEvent

    /** Gateway → Client: audio output from model */
    @Serializable
    @SerialName("audio_out")
    data class AudioOut(
        val chunk: AudioChunk,
    ) : VoiceEvent

    /** Bidirectional: speech-to-text transcription */
    @Serializable
    @SerialName("transcript")
    data class Transcript(
        val text: String,
        val isFinal: Boolean = false,
        /** "user" or "model" */
        val speaker: String = "user",
    ) : VoiceEvent

    /** Gateway → Client: model wants to call a tool */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val args: JsonObject,
    ) : VoiceEvent

    /** Legacy client tool result. The gateway ignores it and executes voice tools internally. */
    @Serializable
    @SerialName("tool_response")
    data class ToolResponse(
        val id: String,
        val result: String,
    ) : VoiceEvent

    /** Model turn completed */
    @Serializable
    @SerialName("turn_complete")
    data object TurnComplete : VoiceEvent

    /** Model was interrupted (barge-in) */
    @Serializable
    @SerialName("interrupted")
    data object Interrupted : VoiceEvent

    /** Session ended */
    @Serializable
    @SerialName("session_end")
    data class SessionEnd(
        val reason: String = "user",
    ) : VoiceEvent

    /** Error event */
    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
        val code: String? = null,
    ) : VoiceEvent
}

// ── Tool Schema (portable tool description for voice providers) ──

/**
 * Portable tool schema that can be converted to each provider's format:
 * - Gemini: `tools.functionDeclarations`
 * - OpenAI: `session.tools`
 *
 * Extracted from [ToolRegistry] at session start and injected into the
 * S2S provider's setup message so the voice model can call agent tools.
 */
@Serializable
data class ToolSchema(
    /** Tool name (matches ToolRegistry key) */
    val name: String,
    /** Human-readable description for the LLM */
    val description: String,
    /** JSON Schema of parameters (from Koog tool descriptor) */
    val parameters: JsonObject = JsonObject(emptyMap()),
)
