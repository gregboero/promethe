package dev.promethe.gateway.voice

/**
 * Convert raw PCM bytes to a minimal WAV file (for Whisper upload and similar APIs).
 *
 * Produces a standards-compliant WAV with a 44-byte header.
 */
internal fun pcmToWav(
    pcm: ByteArray,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int,
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val fileSize = 36 + dataSize

    val buffer = java.nio.ByteBuffer.allocate(44 + dataSize)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // RIFF header
    buffer.put("RIFF".toByteArray())
    buffer.putInt(fileSize)
    buffer.put("WAVE".toByteArray())
    // fmt chunk
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16) // chunk size
    buffer.putShort(1) // PCM format
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(byteRate)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    // data chunk
    buffer.put("data".toByteArray())
    buffer.putInt(dataSize)
    buffer.put(pcm)

    return buffer.array()
}
