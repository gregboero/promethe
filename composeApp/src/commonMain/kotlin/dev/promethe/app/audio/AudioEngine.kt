package dev.promethe.app.audio

/**
 * AudioEngine — cross-platform audio capture and playback.
 *
 * expect/actual pattern:
 * - JVM: TargetDataLine/SourceDataLine (javax.sound)
 * - iOS: AVAudioEngine (future)
 *
 * Audio format: PCM 16-bit mono
 * - Capture: 16kHz, 20ms chunks (640 bytes)
 * - Playback: 24kHz
 */
expect class AudioEngine() {
    /**
     * Start capturing audio from the microphone.
     * Calls [onChunk] with base64-encoded PCM chunks every 20ms.
     */
    fun startCapture(onChunk: (base64Data: String) -> Unit)

    /**
     * Stop capturing audio.
     */
    fun stopCapture()

    /**
     * Play a base64-encoded PCM audio chunk.
     */
    fun playChunk(
        base64Data: String,
        sampleRate: Int = 24000,
    )

    /**
     * Stop playback immediately (barge-in).
     */
    fun stopPlayback()

    /**
     * Release all audio resources.
     */
    fun release()

    /**
     * Whether the engine is currently capturing.
     */
    val isCapturing: Boolean

    /**
     * Whether the engine is currently playing.
     */
    val isPlaying: Boolean
}
