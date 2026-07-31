package dev.promethe.app.audio

import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * JVM AudioEngine — uses javax.sound for capture and playback.
 *
 * Capture: TargetDataLine, PCM 16-bit mono 16kHz, 20ms chunks.
 * Playback: SourceDataLine on a dedicated thread with a blocking queue
 *           to ensure smooth, uninterrupted audio output.
 */
@OptIn(ExperimentalEncodingApi::class)
actual class AudioEngine actual constructor() {
    private var captureLine: TargetDataLine? = null
    private var captureThread: Thread? = null
    private var playbackLine: SourceDataLine? = null
    private var playbackThread: Thread? = null

    private var _isCapturing: Boolean = false
    actual val isCapturing: Boolean get() = _isCapturing

    private var _isPlaying: Boolean = false

    @Suppress("ktlint:standard:backing-property-naming")
    private var _isPlayingAudio: Boolean = false
    actual val isPlaying: Boolean get() = _isPlayingAudio

    private val captureFormat = AudioFormat(16000f, 16, 1, true, false)

    // 20ms of 16kHz 16-bit mono = 640 bytes
    private val chunkSize = 640

    // Queue for audio chunks to be played on the playback thread
    private val playbackQueue = LinkedBlockingQueue<PlaybackChunk>()

    private data class PlaybackChunk(
        val pcmBytes: ByteArray,
        val sampleRate: Int,
    )

    actual fun startCapture(onChunk: (base64Data: String) -> Unit) {
        if (isCapturing) return

        try {
            val info = DataLine.Info(TargetDataLine::class.java, captureFormat)
            if (!AudioSystem.isLineSupported(info)) {
                println("[AudioEngine] Microphone not available")
                return
            }

            captureLine = (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(captureFormat, chunkSize * 4)
                start()
            }

            _isCapturing = true

            captureThread = Thread({
                val buffer = ByteArray(chunkSize)
                val line = captureLine ?: return@Thread

                while (isCapturing && line.isOpen) {
                    val bytesRead = line.read(buffer, 0, chunkSize)
                    if (bytesRead > 0) {
                        val encoded = Base64.encode(buffer, 0, bytesRead)
                        onChunk(encoded)
                    }
                }
            }, "AudioEngine-Capture").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            println("[AudioEngine] Failed to start capture: ${e.message}")
            _isCapturing = false
        }
    }

    actual fun stopCapture() {
        _isCapturing = false
        captureThread?.interrupt()
        captureThread = null
        captureLine?.apply {
            stop()
            close()
        }
        captureLine = null
    }

    // Buffer for leftover byte when PCM chunk has odd length
    private var leftoverByte: Byte? = null

    actual fun playChunk(
        base64Data: String,
        sampleRate: Int,
    ) {
        try {
            val rawBytes = Base64.decode(base64Data)

            // Prepend any leftover byte from previous chunk
            val pcmBytes = if (leftoverByte != null) {
                ByteArray(1 + rawBytes.size).also {
                    it[0] = leftoverByte!!
                    rawBytes.copyInto(it, 1)
                }
            } else {
                rawBytes
            }
            leftoverByte = null

            // Frame-align: 16-bit mono = 2 bytes per frame
            val frameSize = 2
            val alignedLen = (pcmBytes.size / frameSize) * frameSize
            if (alignedLen < pcmBytes.size) {
                leftoverByte = pcmBytes[alignedLen]
            }
            if (alignedLen == 0) return

            val aligned = if (alignedLen == pcmBytes.size) pcmBytes else pcmBytes.copyOf(alignedLen)

            // Enqueue for the playback thread
            playbackQueue.put(PlaybackChunk(aligned, sampleRate))
            _isPlaying = true
            _isPlayingAudio = true

            // Start playback thread if not already running
            if (playbackThread == null || !playbackThread!!.isAlive) {
                playbackThread = Thread({
                    playbackLoop()
                }, "AudioEngine-Playback").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (e: Exception) {
            println("[AudioEngine] Playback enqueue error: ${e.message}")
        }
    }

    private fun playbackLoop() {
        var currentRate = 0
        try {
            while (_isPlaying) {
                // Block up to 100ms waiting for the next chunk
                val chunk = playbackQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    _isPlayingAudio = false
                    playbackLine?.drain()
                    continue
                }
                _isPlayingAudio = true

                // Only open/reconfigure line if sample rate actually changes
                if (chunk.sampleRate != currentRate || playbackLine == null || !playbackLine!!.isOpen) {
                    playbackLine?.apply {
                        drain()
                        stop()
                        close()
                    }
                    currentRate = chunk.sampleRate
                    val format = AudioFormat(currentRate.toFloat(), 16, 1, true, false)
                    val info = DataLine.Info(SourceDataLine::class.java, format)
                    // Buffer = 1 second of audio for smooth playback
                    val bufferSize = currentRate * 2 // 1 second of 16-bit mono
                    playbackLine = (AudioSystem.getLine(info) as SourceDataLine).apply {
                        open(format, bufferSize)
                        start()
                    }
                    println("[AudioEngine] Playback line opened: ${currentRate}Hz, buffer=$bufferSize bytes")
                }

                playbackLine?.write(chunk.pcmBytes, 0, chunk.pcmBytes.size)
            }
        } catch (e: InterruptedException) {
            // Thread interrupted, exit gracefully
        } catch (e: Exception) {
            println("[AudioEngine] Playback loop error: ${e.message}")
        } finally {
            playbackLine?.apply {
                drain()
                stop()
                close()
            }
            playbackLine = null
            currentRate = 0
            _isPlaying = false
            _isPlayingAudio = false
        }
    }

    actual fun stopPlayback() {
        _isPlaying = false
        _isPlayingAudio = false
        leftoverByte = null
        playbackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
    }

    actual fun release() {
        stopCapture()
        stopPlayback()
    }
}
