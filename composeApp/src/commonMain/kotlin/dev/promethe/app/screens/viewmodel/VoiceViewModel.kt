package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.voice.AudioChunk
import dev.promethe.api.voice.VoiceEvent
import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.app.audio.AudioEngine
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

/**
 * VoiceViewModel — manages voice session state and audio.
 */
class VoiceViewModel(
    private val wsBaseUrl: String = "ws://localhost:8080",
    private val apiKey: String = "",
) : ViewModel() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _transcripts = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<TranscriptItem>> = _transcripts.asStateFlow()

    private var audioEngine: AudioEngine? = null
    private var wsSession: WebSocketSession? = null
    private var sessionJob: Job? = null

    /**
     * Start a voice session.
     */
    fun startSession(config: VoiceSessionConfig = VoiceSessionConfig()) {
        if (_state.value.isActive) return

        _state.update { it.copy(isActive = true, isConnecting = true, error = null) }
        _transcripts.value = emptyList()

        sessionJob = viewModelScope.launch {
            try {
                // Init audio engine
                audioEngine = AudioEngine()

                // Connect WebSocket
                val wsUrl = "$wsBaseUrl/ws/chat/voice"
                val client = HttpClient {
                    install(WebSockets)
                    if (apiKey.isNotBlank()) defaultRequest { header(HttpHeaders.Authorization, "Bearer $apiKey") }
                }
                client.webSocket(wsUrl) {
                    wsSession = this

                    // Send config
                    send(Frame.Text(json.encodeToString(VoiceSessionConfig.serializer(), config)))
                    _state.update { it.copy(isConnecting = false) }

                    // Start mic capture → send audio
                    audioEngine?.startCapture { base64 ->
                        viewModelScope.launch {
                            val event = VoiceEvent.AudioIn(AudioChunk(data = base64))
                            try {
                                send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                            } catch (_: Exception) {
                            }
                        }
                    }

                    // Receive events
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val event = try {
                                json.decodeFromString<VoiceEvent>(frame.readText())
                            } catch (_: Exception) {
                                continue
                            }

                            handleEvent(event)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                cleanup()
            }
        }
    }

    /**
     * Stop the current voice session.
     */
    fun stopSession() {
        viewModelScope.launch {
            try {
                wsSession?.send(
                    Frame.Text(
                        json.encodeToString(VoiceEvent.serializer(), VoiceEvent.SessionEnd()),
                    ),
                )
            } catch (_: Exception) {
            }
            sessionJob?.cancel()
            cleanup()
        }
    }

    /**
     * Toggle mute state.
     */
    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        _state.update { it.copy(isMuted = newMuted) }
        if (newMuted) {
            audioEngine?.stopCapture()
        } else {
            audioEngine?.startCapture { base64 ->
                viewModelScope.launch {
                    val event = VoiceEvent.AudioIn(AudioChunk(data = base64))
                    try {
                        wsSession?.send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * Toggle echo suppression state (Headset Mode).
     */
    fun toggleEchoSuppression() {
        val newEchoSuppression = !_state.value.echoSuppression
        _state.update { it.copy(echoSuppression = newEchoSuppression) }

        // If the AI is speaking and we just turned off echo suppression (headset mode ON),
        // we should immediately resume capturing mic input.
        // If we turned it ON (headset mode OFF), we stop capturing immediately.
        if (_state.value.isSpeaking) {
            if (newEchoSuppression) {
                audioEngine?.stopCapture()
            } else {
                resumeCaptureIfNeeded()
            }
        }
    }

    private fun handleEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.AudioOut -> {
                val wasSpeaking = _state.value.isSpeaking
                _state.update { it.copy(isSpeaking = true) }

                // Echo suppression: stop sending mic audio while model speaks
                // to prevent the model from hearing its own output
                if (_state.value.echoSuppression && !wasSpeaking && !_state.value.isMuted) {
                    audioEngine?.stopCapture()
                }

                audioEngine?.playChunk(event.chunk.data, event.chunk.sampleRate)
            }

            is VoiceEvent.Transcript -> {
                _transcripts.update { list ->
                    val last = list.lastOrNull()
                    if (last != null && !last.isFinal && last.speaker == event.speaker) {
                        val updatedText = if (event.isFinal) {
                            event.text
                        } else {
                            last.text + event.text
                        }
                        list.dropLast(1) + last.copy(text = updatedText, isFinal = event.isFinal)
                    } else {
                        list + TranscriptItem(
                            text = event.text,
                            speaker = event.speaker,
                            isFinal = event.isFinal,
                        )
                    }
                }
            }

            is VoiceEvent.TurnComplete -> {
                _state.update { it.copy(isSpeaking = false) }
                // Finalize the last model transcript if it's still non-final
                _transcripts.update { list ->
                    val lastIndex = list.indexOfLast { it.speaker == "model" && !it.isFinal }
                    if (lastIndex != -1) {
                        list.mapIndexed { idx, item ->
                            if (idx == lastIndex) item.copy(isFinal = true) else item
                        }
                    } else {
                        list
                    }
                }
                // Resume mic capture after model finishes speaking
                resumeCaptureIfNeeded()
            }

            is VoiceEvent.Interrupted -> {
                audioEngine?.stopPlayback()
                _state.update { it.copy(isSpeaking = false) }
                // Finalize the last model transcript if it's still non-final
                _transcripts.update { list ->
                    val lastIndex = list.indexOfLast { it.speaker == "model" && !it.isFinal }
                    if (lastIndex != -1) {
                        list.mapIndexed { idx, item ->
                            if (idx == lastIndex) item.copy(isFinal = true) else item
                        }
                    } else {
                        list
                    }
                }
                // Resume mic capture after interruption
                resumeCaptureIfNeeded()
            }

            is VoiceEvent.Error -> {
                _state.update { it.copy(error = event.message) }
            }

            is VoiceEvent.SessionEnd -> {
                stopSession()
            }

            else -> {}
        }
    }

    /**
     * Resume mic capture if the session is active and not muted.
     */
    private fun resumeCaptureIfNeeded() {
        if (_state.value.isActive && !_state.value.isMuted && audioEngine?.isCapturing != true) {
            viewModelScope.launch {
                // Wait until the audio engine has finished playing the buffered audio
                while (audioEngine?.isPlaying == true) {
                    delay(50)
                }
                if (_state.value.isActive && !_state.value.isMuted && audioEngine?.isCapturing != true) {
                    audioEngine?.startCapture { base64 ->
                        viewModelScope.launch {
                            val event = VoiceEvent.AudioIn(AudioChunk(data = base64))
                            try {
                                wsSession?.send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cleanup() {
        audioEngine?.release()
        audioEngine = null
        wsSession = null
        _state.update {
            VoiceUiState() // reset
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STT Dictation Mode
    // ═══════════════════════════════════════════════════════════════════════

    private val _dictationText = MutableStateFlow("")

    /** Accumulated dictation text (final transcripts only). */
    val dictationText: StateFlow<String> = _dictationText.asStateFlow()

    /**
     * Start a dictation session — mic capture → text transcription only.
     * Uses the same WS endpoint but with mode="stt" so Gemini responds with TEXT modality.
     */
    fun startDictation(config: VoiceSessionConfig = VoiceSessionConfig(mode = "stt")) {
        if (_state.value.isActive) return

        val sttConfig = config.copy(mode = "stt")
        _state.update { it.copy(isActive = true, isConnecting = true, isDictating = true, error = null) }
        _dictationText.value = ""
        _transcripts.value = emptyList()

        sessionJob = viewModelScope.launch {
            try {
                audioEngine = AudioEngine()

                val wsUrl = "$wsBaseUrl/ws/chat/voice"
                val client = HttpClient {
                    install(WebSockets)
                    if (apiKey.isNotBlank()) defaultRequest { header(HttpHeaders.Authorization, "Bearer $apiKey") }
                }
                client.webSocket(wsUrl) {
                    wsSession = this
                    send(Frame.Text(json.encodeToString(VoiceSessionConfig.serializer(), sttConfig)))
                    _state.update { it.copy(isConnecting = false) }

                    // Capture mic → send audio chunks
                    audioEngine?.startCapture { base64 ->
                        viewModelScope.launch {
                            val event = VoiceEvent.AudioIn(AudioChunk(data = base64))
                            try {
                                send(Frame.Text(json.encodeToString(VoiceEvent.serializer(), event)))
                            } catch (_: Exception) {
                            }
                        }
                    }

                    // Receive events — only care about transcripts in STT mode
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val event = try {
                                json.decodeFromString<VoiceEvent>(frame.readText())
                            } catch (_: Exception) {
                                continue
                            }

                            when (event) {
                                is VoiceEvent.Transcript -> {
                                    if (event.speaker == "user") {
                                        // Update live transcripts
                                        _transcripts.update { list ->
                                            val last = list.lastOrNull()
                                            if (last != null && !last.isFinal) {
                                                list.dropLast(1) + last.copy(text = event.text, isFinal = event.isFinal)
                                            } else {
                                                list + TranscriptItem(text = event.text, speaker = "user", isFinal = event.isFinal)
                                            }
                                        }
                                        // Accumulate final text
                                        if (event.isFinal) {
                                            _dictationText.update { existing ->
                                                if (existing.isBlank()) {
                                                    event.text
                                                } else {
                                                    "$existing ${event.text}"
                                                }
                                            }
                                        }
                                    }
                                }

                                is VoiceEvent.Error -> {
                                    _state.update { it.copy(error = event.message) }
                                }

                                is VoiceEvent.SessionEnd -> {
                                    stopSession()
                                    return@webSocket
                                }

                                else -> {} // Ignore AudioOut in STT mode
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                cleanup()
            }
        }
    }
}

data class VoiceUiState(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeaking: Boolean = false,
    val isDictating: Boolean = false,
    val error: String? = null,
    val echoSuppression: Boolean = true,
)

data class TranscriptItem(
    val text: String,
    val speaker: String,
    val isFinal: Boolean,
)
