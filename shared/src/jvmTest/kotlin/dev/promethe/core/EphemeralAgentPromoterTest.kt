package dev.promethe.core

import dev.promethe.core.tools.builtin.FakePrometheDatabase
import dev.promethe.db.AgentProfileRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EphemeralAgentPromoterTest {
    // ── Helpers ──

    private fun makeProfile(
        id: String = "ephemeral-1",
        ephemeral: Boolean = true,
    ) = AgentProfileRow(
        id = id,
        name = "Test Agent",
        provider = "openai",
        model = "gpt-4o-mini",
        systemPrompt = "You are a test agent",
        tools = "[]",
        maxIterations = 10,
        temperature = 0.2,
        isSystem = false,
        ephemeral = ephemeral,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private fun setup(
        profiles: List<AgentProfileRow> = emptyList(),
        usageThreshold: Int = EphemeralAgentPromoter.DEFAULT_USAGE_THRESHOLD,
    ): Pair<FakePrometheDatabase, EphemeralAgentPromoter> {
        val db = FakePrometheDatabase()
        profiles.forEach { db.profiles[it.id] = it }
        val promoter = EphemeralAgentPromoter(db, usageThreshold)
        return db to promoter
    }

    // ══════════════════════════════════════════════════════════════════
    // Auto-Promotion Logic
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `does not track non-ephemeral agents`() =
        runTest {
            val (_, promoter) = setup(listOf(makeProfile(ephemeral = false)))

            val promoted = promoter.onDelegationComplete("ephemeral-1", success = true)

            assertFalse(promoted, "Permanent agent should never be promoted")
            assertTrue(promoter.getTrackingStatus().isEmpty(), "No tracking should occur for permanent agents")
        }

    @Test
    fun `tracks ephemeral agent usage`() =
        runTest {
            val (_, promoter) = setup(listOf(makeProfile()))

            promoter.onDelegationComplete("ephemeral-1", success = true)

            val status = promoter.getTrackingStatus()
            assertEquals(1, status.size, "Should track one agent")
            assertEquals(1, status[0].totalUses, "Should have 1 use")
            assertEquals(1, status[0].successes, "Should have 1 success")
            assertEquals(0, status[0].failures, "Should have 0 failures")
        }

    @Test
    fun `does not promote below threshold`() =
        runTest {
            val (db, promoter) = setup(listOf(makeProfile()))

            // 2 uses < default threshold of 3
            val r1 = promoter.onDelegationComplete("ephemeral-1", success = true)
            val r2 = promoter.onDelegationComplete("ephemeral-1", success = true)

            assertFalse(r1, "First use should not promote")
            assertFalse(r2, "Second use should not promote")
            // Profile should still be ephemeral
            assertTrue(db.profiles["ephemeral-1"]!!.ephemeral, "Agent should remain ephemeral")
        }

    @Test
    fun `auto-promotes at threshold with good success rate`() =
        runTest {
            val (db, promoter) = setup(listOf(makeProfile()))

            // 3 successes = 100% rate, meets threshold
            promoter.onDelegationComplete("ephemeral-1", success = true)
            promoter.onDelegationComplete("ephemeral-1", success = true)
            val promoted = promoter.onDelegationComplete("ephemeral-1", success = true)

            assertTrue(promoted, "Should be promoted after 3 successful uses")
            assertFalse(db.profiles["ephemeral-1"]!!.ephemeral, "Profile should no longer be ephemeral")
        }

    @Test
    fun `does not promote with low success rate`() =
        runTest {
            val (db, promoter) = setup(listOf(makeProfile()))

            // 1 success + 2 failures = 33% rate — below 70% threshold
            promoter.onDelegationComplete("ephemeral-1", success = true)
            promoter.onDelegationComplete("ephemeral-1", success = false)
            val promoted = promoter.onDelegationComplete("ephemeral-1", success = false)

            assertFalse(promoted, "Should not promote with only 33% success rate")
            assertTrue(db.profiles["ephemeral-1"]!!.ephemeral, "Agent should remain ephemeral")
        }

    @Test
    fun `promotes at threshold with exactly 70 percent success`() =
        runTest {
            // Need successes/total >= 0.7. With 10 uses: 7 successes = exactly 70%
            val (db, promoter) = setup(listOf(makeProfile()), usageThreshold = 10)

            // 7 successes then 3 failures
            repeat(7) { promoter.onDelegationComplete("ephemeral-1", success = true) }
            repeat(2) { promoter.onDelegationComplete("ephemeral-1", success = false) }
            val promoted = promoter.onDelegationComplete("ephemeral-1", success = false)

            // 7/10 = 0.7 which is >= MIN_SUCCESS_RATE (0.7)
            assertTrue(promoted, "Should promote at exactly 70% success rate")
            assertFalse(db.profiles["ephemeral-1"]!!.ephemeral, "Profile should no longer be ephemeral")
        }

    @Test
    fun `cleans up counters after promotion`() =
        runTest {
            val (_, promoter) = setup(listOf(makeProfile()))

            // Promote
            repeat(3) { promoter.onDelegationComplete("ephemeral-1", success = true) }

            // Counters should be cleaned up
            val status = promoter.getTrackingStatus()
            assertTrue(status.isEmpty(), "All counters should be cleaned up after promotion")
        }

    // ══════════════════════════════════════════════════════════════════
    // Status Tracking
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `getTrackingStatus returns empty initially`() {
        val (_, promoter) = setup(listOf(makeProfile()))

        val status = promoter.getTrackingStatus()

        assertTrue(status.isEmpty(), "Should be empty before any delegations")
    }

    @Test
    fun `getTrackingStatus shows correct counters`() =
        runTest {
            val (_, promoter) = setup(listOf(makeProfile()))

            promoter.onDelegationComplete("ephemeral-1", success = true)
            promoter.onDelegationComplete("ephemeral-1", success = false)

            val status = promoter.getTrackingStatus()
            assertEquals(1, status.size)
            val s = status[0]
            assertEquals("ephemeral-1", s.profileId)
            assertEquals(2, s.totalUses)
            assertEquals(1, s.successes)
            assertEquals(1, s.failures)
            assertEquals(0.5, s.successRate, "Success rate should be 50%")
        }

    @Test
    fun `getTrackingStatus shows uses until promotion`() =
        runTest {
            val (_, promoter) = setup(listOf(makeProfile()))

            promoter.onDelegationComplete("ephemeral-1", success = true)

            val status = promoter.getTrackingStatus()
            assertEquals(1, status.size)
            assertEquals(2, status[0].usesUntilPromotion, "Should need 2 more uses (threshold=3, used=1)")
        }

    // ══════════════════════════════════════════════════════════════════
    // Edge Cases
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `nonexistent profile returns false`() =
        runTest {
            val (_, promoter) = setup() // no profiles

            val promoted = promoter.onDelegationComplete("does-not-exist", success = true)

            assertFalse(promoted, "Nonexistent profile should return false")
            assertTrue(promoter.getTrackingStatus().isEmpty(), "No tracking for nonexistent profiles")
        }

    @Test
    fun `custom usage threshold works`() =
        runTest {
            val (db, promoter) = setup(listOf(makeProfile()), usageThreshold = 5)

            // 3 uses should not promote with threshold=5
            repeat(3) { promoter.onDelegationComplete("ephemeral-1", success = true) }
            val statusMid = promoter.getTrackingStatus()
            assertEquals(2, statusMid[0].usesUntilPromotion, "Should need 2 more uses (threshold=5, used=3)")
            assertTrue(db.profiles["ephemeral-1"]!!.ephemeral, "Should still be ephemeral at 3/5")

            // 4th use — still below
            val r4 = promoter.onDelegationComplete("ephemeral-1", success = true)
            assertFalse(r4, "4th use should not promote (threshold=5)")

            // 5th use — promotes
            val r5 = promoter.onDelegationComplete("ephemeral-1", success = true)
            assertTrue(r5, "5th use should promote (threshold=5)")
            assertFalse(db.profiles["ephemeral-1"]!!.ephemeral, "Should be permanent after promotion")
        }
}
