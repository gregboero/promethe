package dev.promethe.gateway

import kotlin.test.*

/**
 * Unit tests for RateLimiter logic.
 */
class RateLimiterTest {
    @BeforeTest
    fun resetLimiter() {
        // Reset to default config for each test
        RateLimiter.configure(maxRequestsPerWindow = 60, windowDurationMs = 60_000L)
    }

    @Test
    fun `configure sets custom limits`() {
        // Smoke test — configure doesn't throw
        RateLimiter.configure(maxRequestsPerWindow = 5, windowDurationMs = 1000L)
    }

    @Test
    fun `cleanup does not throw on empty state`() {
        RateLimiter.cleanup()
    }
}
