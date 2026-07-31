package dev.promethe.app.audio

/**
 * WasmJs stub — audio capture/playback not supported in browser WASM target.
 * This provides a no-op implementation to satisfy the expect/actual contract.
 */
actual class AudioEngine actual constructor() {
    private var _isCapturing = false
    private var _isPlaying = false

    actual fun startCapture(onChunk: (base64Data: String) -> Unit) {
        // No-op: browser audio capture not implemented for WASM
        println("AudioEngine: capture not supported on wasmJs")
    }

    actual fun stopCapture() {
        _isCapturing = false
    }

    actual fun playChunk(
        base64Data: String,
        sampleRate: Int,
    ) {
        // No-op: browser audio playback not implemented for WASM
    }

    actual fun stopPlayback() {
        _isPlaying = false
    }

    actual fun release() {
        _isCapturing = false
        _isPlaying = false
    }

    actual val isCapturing: Boolean
        get() = _isCapturing

    actual val isPlaying: Boolean
        get() = _isPlaying
}
