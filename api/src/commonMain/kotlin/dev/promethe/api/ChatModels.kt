package dev.promethe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatRequest(
    val sessionId: String,
    val message: String,
    val profileId: String? = null, // optional: route to a specific agent profile
)

@Serializable
data class ChatEvent(
    // Core types: "thought", "action", "observation", "response", "done", "error"
    // A2UI types: "ui_layout", "ui_data", "ui_clear", "ui_action"
    // Voice types: "voice_start", "voice_audio", "voice_end", "voice_transcript"
    // TTS types:  "audio_message"
    val type: String,
    val content: String? = null,
    val tool: String? = null,
    val args: JsonObject? = null,
    val binaryData: String? = null, // base64 audio chunks or protobuf-encoded UI
    val metadata: JsonObject? = null, // structured metadata (voice config, UI state, etc.)
)

/**
 * Structured payload for `ChatEvent(type = "audio_message")`.
 *
 * Sent by the TTS route or voice pipeline when synthesized audio is ready.
 * The [data] field contains base64-encoded PCM audio; [text] is the original
 * transcript that was synthesized (useful for captions / accessibility).
 */
@Serializable
@SerialName("audio_message")
data class AudioMessage(
    /** Base64-encoded PCM audio data */
    val data: String,
    /** Sample rate in Hz (default 24kHz for OpenAI/Gemini, 16kHz for ElevenLabs) */
    val sampleRate: Int = 24000,
    /** Original text that was synthesized (optional, for captions) */
    val text: String? = null,
)
