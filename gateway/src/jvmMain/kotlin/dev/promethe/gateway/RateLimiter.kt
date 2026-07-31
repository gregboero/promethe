package dev.promethe.gateway

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple in-memory rate limiter using a sliding window approach.
 *
 * Configurable per-IP request limits with automatic window reset.
 * For production at scale, replace with Redis-backed rate limiting.
 */
object RateLimiter {
    private data class WindowCounter(
        val count: AtomicInteger = AtomicInteger(0),
        val windowStart: AtomicLong = AtomicLong(System.currentTimeMillis()),
    )

    private val counters = ConcurrentHashMap<String, WindowCounter>()

    // Default: 60 requests per minute per IP
    private var maxRequests: Int = 60
    private var webhookMaxRequests: Int = 120
    private var windowMs: Long = 60_000L
    private val cleanupTicker = AtomicLong(0)

    fun configure(
        maxRequestsPerWindow: Int = 60,
        windowDurationMs: Long = 60_000L,
        maxWebhookRequestsPerWindow: Int = 120,
    ) {
        maxRequests = maxRequestsPerWindow
        webhookMaxRequests = maxWebhookRequestsPerWindow
        windowMs = windowDurationMs
    }

    /**
     * Install the rate limiter as a Ktor interceptor.
     * Health checks are skipped; public webhooks have their own stricter bucket.
     */
    fun install(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()

            if (path == "/health") {
                return@intercept
            }

            val clientIp = call.request.local.remoteHost
            val isWebhook = path.startsWith("/webhook/")
            val requestLimit = if (isWebhook) webhookMaxRequests else maxRequests
            val bucket = if (isWebhook) "webhook" else "default"
            val counterKey = "$bucket:$clientIp"
            val counter = counters.getOrPut(counterKey) { WindowCounter() }

            if (cleanupTicker.incrementAndGet() % CLEANUP_INTERVAL == 0L || counters.size > MAX_COUNTERS) {
                cleanup()
                if (counters.size > MAX_COUNTERS) {
                    counters.entries.minByOrNull { it.value.windowStart.get() }?.let { counters.remove(it.key) }
                }
            }

            val now = System.currentTimeMillis()
            val elapsed = now - counter.windowStart.get()

            // Reset window if expired
            if (elapsed > windowMs) {
                counter.count.set(0)
                counter.windowStart.set(now)
            }

            val currentCount = counter.count.incrementAndGet()
            val remaining = (requestLimit - currentCount).coerceAtLeast(0)

            // Add rate limit headers
            call.response.header("X-RateLimit-Limit", requestLimit.toString())
            call.response.header("X-RateLimit-Remaining", remaining.toString())
            call.response.header("X-RateLimit-Reset", ((windowMs - elapsed) / 1000).toString())

            if (currentCount > requestLimit) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf("error" to "Rate limit exceeded. Try again in ${(windowMs - elapsed) / 1000}s."),
                )
                finish()
                return@intercept
            }
        }
    }

    /**
     * Periodically clean up stale entries (call from a scheduled task).
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        counters.entries.removeIf { (_, counter) ->
            now - counter.windowStart.get() > windowMs * 2
        }
    }

    private const val MAX_COUNTERS = 10_000
    private const val CLEANUP_INTERVAL = 256L
}
