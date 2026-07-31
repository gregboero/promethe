package dev.promethe.gateway

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object WebhookReplayGuard {
    private const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 20_000
    private val acceptedEvents = ConcurrentHashMap<String, Long>()

    fun accept(
        channel: String,
        eventId: String,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        val key = "$channel:$eventId"
        while (true) {
            val previous = acceptedEvents[key]
            if (previous != null && now - previous <= ttlMs) return false
            if (previous == null) {
                if (acceptedEvents.putIfAbsent(key, now) == null) break
            } else if (acceptedEvents.replace(key, previous, now)) {
                break
            }
        }

        if (acceptedEvents.size > MAX_ENTRIES) cleanup(now, ttlMs)
        if (acceptedEvents.size > MAX_ENTRIES) {
            acceptedEvents.entries.minByOrNull { it.value }?.let { acceptedEvents.remove(it.key) }
        }
        return true
    }

    fun fingerprint(rawBody: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(rawBody.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    fun clear() = acceptedEvents.clear()

    private fun cleanup(
        now: Long,
        ttlMs: Long,
    ) {
        acceptedEvents.entries.removeIf { now - it.value > ttlMs }
    }
}
