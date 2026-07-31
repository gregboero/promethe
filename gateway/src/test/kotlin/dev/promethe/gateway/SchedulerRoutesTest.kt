package dev.promethe.gateway

import dev.promethe.core.TaskScheduler
import kotlin.test.*

/**
 * Unit tests for TaskScheduler companion functions.
 *
 * Tests cron expression matching and next-run computation
 * without needing an AIAgent or database connection.
 */
class TaskSchedulerCronTest {
    // ── matchesCron ──

    @Test
    fun `star in all fields matches any timestamp`() {
        val ts = System.currentTimeMillis()
        assertTrue(TaskScheduler.matchesCron("* * * * *", ts))
    }

    @Test
    fun `invalid expression returns false`() {
        assertFalse(TaskScheduler.matchesCron("bad", System.currentTimeMillis()))
        assertFalse(TaskScheduler.matchesCron("* * *", System.currentTimeMillis()))
    }

    @Test
    fun `exact minute match works`() {
        // 2024-01-15 09:30:00 UTC = 1705308600000
        val ts = 1705311000000L
        assertTrue(TaskScheduler.matchesCron("30 9 * * *", ts), "Should match minute=30, hour=9")
        assertFalse(TaskScheduler.matchesCron("31 9 * * *", ts), "Should NOT match minute=31")
        assertFalse(TaskScheduler.matchesCron("30 10 * * *", ts), "Should NOT match hour=10")
    }

    @Test
    fun `step expression works`() {
        // minute=30 → 30 % 5 == 0 → should match */5
        val ts = 1705311000000L // 09:30 UTC
        assertTrue(TaskScheduler.matchesCron("*/5 * * * *", ts))
        assertTrue(TaskScheduler.matchesCron("*/10 * * * *", ts))
        assertTrue(TaskScheduler.matchesCron("*/15 * * * *", ts))
        assertFalse(TaskScheduler.matchesCron("*/7 * * * *", ts), "30 % 7 != 0")
    }

    @Test
    fun `range expression works`() {
        val ts = 1705311000000L // 09:30 UTC
        assertTrue(TaskScheduler.matchesCron("25-35 * * * *", ts), "30 is in 25-35")
        assertFalse(TaskScheduler.matchesCron("0-10 * * * *", ts), "30 is NOT in 0-10")
    }

    @Test
    fun `list expression works`() {
        val ts = 1705311000000L // 09:30 UTC
        assertTrue(TaskScheduler.matchesCron("15,30,45 * * * *", ts), "30 is in list")
        assertFalse(TaskScheduler.matchesCron("15,25,45 * * * *", ts), "30 is NOT in list")
    }

    // ── computeNextRun ──

    @Test
    fun `computeNextRun returns a future timestamp`() {
        val now = System.currentTimeMillis()
        val next = TaskScheduler.computeNextRun("* * * * *", now)
        assertNotNull(next, "Every-minute cron should always find a next run")
        assertTrue(next > now, "Next run should be after now")
    }

    @Test
    fun `computeNextRun respects cron pattern`() {
        val now = System.currentTimeMillis()
        val next = TaskScheduler.computeNextRun("0 0 * * *", now) // midnight daily
        assertNotNull(next)
        assertTrue(next > now)

        // Verify the result actually matches the cron
        assertTrue(TaskScheduler.matchesCron("0 0 * * *", next), "Next run should match the cron pattern")
    }

    @Test
    fun `computeNextRun returns null for impossible cron`() {
        // Feb 30th doesn't exist — should return null (or scan far enough)
        // Actually "0 0 30 2 *" could match in some edge cases with leap years
        // Let's use a truly impossible one: minute=60 (out of range)
        val next = TaskScheduler.computeNextRun("60 * * * *", System.currentTimeMillis())
        assertNull(next, "Minute 60 is out of range, should never match")
    }
}
