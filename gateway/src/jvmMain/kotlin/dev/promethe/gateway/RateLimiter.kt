package dev.promethe.gateway

import dev.promethe.api.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    // Remote interactive clients generate several REST and SSE requests per action.
    private var maxRequests: Int = 600
    private var webhookMaxRequests: Int = 120
    private var windowMs: Long = 60_000L
    private val cleanupTicker = AtomicLong(0)
    private val jsonEncoder = Json { encodeDefaults = true }

    fun configure(
        maxRequestsPerWindow: Int = 600,
        windowDurationMs: Long = 60_000L,
        maxWebhookRequestsPerWindow: Int = 120,
    ) {
        maxRequests = maxRequestsPerWindow
        webhookMaxRequests = maxWebhookRequestsPerWindow
        windowMs = windowDurationMs
    }

    /**
     * Install the rate limiter as a Ktor interceptor.
     * Health/discovery and the authenticated local desktop client are skipped;
     * public webhooks have their own stricter bucket.
     */
    fun install(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()

            if (
                path == "/health" ||
                path.startsWith("/.well-known/") ||
                AuthMiddleware.isLocalApiKeyAuthentication(call)
            ) {
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
            val retryAfterSeconds = ((windowMs - elapsed).coerceAtLeast(0L) / 1000L).coerceAtLeast(1L)
            call.response.header("X-RateLimit-Reset", retryAfterSeconds.toString())

            if (currentCount > requestLimit) {
                // This interceptor runs before route-scoped ContentNegotiation. Serializing here
                // avoids turning an intended 429 into Ktor's 406 Not Acceptable response.
                call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respondText(
                    text =
                        jsonEncoder.encodeToString(
                            ErrorResponse("Rate limit exceeded. Try again in ${retryAfterSeconds}s."),
                        ),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.TooManyRequests,
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
