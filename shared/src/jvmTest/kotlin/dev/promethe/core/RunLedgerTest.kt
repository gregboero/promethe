package dev.promethe.core

import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.DatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RunLedgerTest {
    @Test
    fun `ledger enforces lifecycle and terminal states`() =
        runTest {
            val ledger = PersistentRunLedger(DatabaseFactory.createInMemory())
            val run = pendingRun("ledger-run-0001")

            assertTrue(ledger.begin(run))
            assertFalse(ledger.begin(run), "Run IDs must be unique")
            assertEquals(AgentRunStatus.RUNNING, ledger.get(run.runId)?.status)

            assertTrue(ledger.recordStep(run.runId, "ledger-run-0001-step-0001", 1, 110))
            assertTrue(ledger.complete(run.runId, 1, "ledger-run-0001-step-0001", 120))

            val completed = assertNotNull(ledger.get(run.runId))
            assertEquals(AgentRunStatus.SUCCEEDED, completed.status)
            assertEquals(1, completed.stepCount)
            assertEquals("ledger-run-0001-step-0001", completed.lastStepId)
            assertEquals(120, completed.finishedAt)
            assertFalse(ledger.recordStep(run.runId, "ledger-run-0001-step-0002", 2, 130))
            assertFalse(ledger.fail(run.runId, 2, null, "late_failure", 140))
        }

    @Test
    fun `failed cancelled and recoverable runs are queryable`() =
        runTest {
            val ledger = PersistentRunLedger(DatabaseFactory.createInMemory())
            assertTrue(ledger.begin(pendingRun("ledger-failed-0001")))
            assertTrue(ledger.fail("ledger-failed-0001", 0, null, "provider_timeout", 120))
            assertEquals(AgentRunStatus.FAILED, ledger.get("ledger-failed-0001")?.status)
            assertEquals("provider_timeout", ledger.get("ledger-failed-0001")?.errorCode)

            assertTrue(ledger.begin(pendingRun("ledger-cancelled-0001")))
            assertTrue(ledger.cancel("ledger-cancelled-0001", 0, null, 130))
            assertEquals(AgentRunStatus.CANCELLED, ledger.get("ledger-cancelled-0001")?.status)

            assertTrue(ledger.begin(pendingRun("ledger-running-0001")))
            assertEquals(listOf("ledger-running-0001"), ledger.recoverable().map(AgentRunRecord::runId))
        }

    @Test
    fun `run survives reopening a persistent database`() =
        runTest {
            val directory = createTempDirectory("promethe-run-ledger-test").toFile()
            val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            try {
                val firstLedger = PersistentRunLedger(DatabaseFactory.create(url))
                assertTrue(firstLedger.begin(pendingRun("ledger-restart-0001")))
                assertTrue(firstLedger.recordStep("ledger-restart-0001", "ledger-restart-0001-step-0001", 1, 120))

                val reopenedLedger = PersistentRunLedger(DatabaseFactory.create(url))
                val recovered = assertNotNull(reopenedLedger.get("ledger-restart-0001"))
                assertEquals(AgentRunStatus.RUNNING, recovered.status)
                assertEquals(1, recovered.stepCount)
                assertEquals("ledger-restart-0001-step-0001", recovered.lastStepId)
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun pendingRun(runId: String): AgentRunRecord =
        AgentRunRecord(
            runId = runId,
            sessionId = "ledger-session",
            origin = AgentExecutionOrigin.INTERNAL.name,
            status = AgentRunStatus.PENDING,
            createdAt = 100,
            updatedAt = 100,
        )
}
