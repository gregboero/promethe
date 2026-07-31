package dev.promethe.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Android AudioEngine — uses AudioRecord for capture and AudioTrack for playback.
 *
 * Capture: AudioRecord, PCM 16-bit mono 16kHz, 20ms chunks (640 bytes).
 * Playback: AudioTrack, PCM 16-bit mono (variable rate).
 */
@OptIn(ExperimentalEncodingApi::class)
actual class AudioEngine actual constructor() {
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private var _isCapturing: Boolean = false
    actual val isCapturing: Boolean get() = _isCapturing

    private var _isPlaying: Boolean = false
    actual val isPlaying: Boolean get() = _isPlaying

    private val captureSampleRate = 16000
    private val captureChannelConfig = AudioFormat.CHANNEL_IN_MONO
    private val captureAudioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 20ms of 16kHz 16-bit mono = 640 bytes
    private val chunkSize = 640

    @SuppressLint("MissingPermission")
    actual fun startCapture(onChunk: (base64Data: String) -> Unit) {
        if (isCapturing) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                captureSampleRate,
                captureChannelConfig,
                captureAudioFormat,
            )
            val bufferSize = maxOf(minBufferSize, chunkSize * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                captureSampleRate,
                captureChannelConfig,
                captureAudioFormat,
                bufferSize,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                println("[AudioEngine] AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            _isCapturing = true

            captureThread = Thread({
                val buffer = ByteArray(chunkSize)
                val record = audioRecord ?: return@Thread

                while (isCapturing && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = record.read(buffer, 0, chunkSize)
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
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }

    actual fun playChunk(
        base64Data: String,
        sampleRate: Int,
    ) {
        try {
            val pcmBytes = Base64.decode(base64Data)

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            // Recreate AudioTrack if sample rate changed
            if (audioTrack == null || audioTrack?.sampleRate != sampleRate) {
                audioTrack?.apply {
                    stop()
                    release()
                }

                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBufferSize, pcmBytes.size * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
            }

            _isPlaying = true
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            println("[AudioEngine] Playback error: ${e.message}")
        }
    }

    actual fun stopPlayback() {
        _isPlaying = false
        audioTrack?.apply {
            stop()
            flush()
        }
    }

    actual fun release() {
        stopCapture()
        stopPlayback()
        audioTrack?.release()
        audioTrack = null
    }
}
