package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PersistentResourceGovernorRegistryTest {
    @Test
    fun `usage and exhaustion survive a database restart`() =
        runTest {
            withPersistentDatabase { url ->
                val budget = ResourceBudget(maxTokens = 10, maxCostDollars = 1.0, maxToolStarts = 1)
                val first = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 100 })
                val governor =
                    assertIs<ResourceGovernorAcquisition.Acquired>(
                        first.acquire("run-restart", "session-restart", budget),
                    ).governor

                assertIs<ResourceAdmission.Allowed>(governor.admit(GovernedResource.TOOL_START))
                governor.recordLlmUsage(tokens = 10, cost = 0.25)

                val reopened = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 200 })
                val restored =
                    assertIs<ResourceGovernorAcquisition.Acquired>(
                        reopened.acquire("run-restart", "session-restart", budget),
                    ).governor
                val snapshot = restored.snapshot()

                assertEquals(10, snapshot.tokensUsed)
                assertEquals(0.25, snapshot.costDollars)
                assertEquals(1, snapshot.toolsStarted)
                val denied = assertIs<ResourceAdmission.Denied>(restored.admit(GovernedResource.TOOL_START))
                assertEquals(ResourceLimit.TOKENS, denied.limit)
            }
        }

    @Test
    fun `child run keeps the root budget after a database restart`() =
        runTest {
            withPersistentDatabase { url ->
                val first = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 100 })
                val root =
                    assertIs<ResourceGovernorAcquisition.Acquired>(
                        first.acquire(
                            runId = "run-root",
                            sessionId = "session-root",
                            budget = ResourceBudget(maxSubAgents = 2, maxToolStarts = 1),
                        ),
                    ).governor
                assertIs<ChildResourceBinding.Bound>(first.bindChild("session-root", "session-child"))

                val reopened = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 200 })
                val child =
                    assertIs<ResourceGovernorAcquisition.Acquired>(
                        reopened.acquire("run-child", "session-child", ResourceBudget.DEFAULT),
                    ).governor

                assertEquals(root.rootRunId, child.rootRunId)
                assertEquals(1, child.snapshot().subAgentsStarted)
                assertIs<ResourceAdmission.Allowed>(child.admit(GovernedResource.TOOL_START))

                val restoredAgain = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 300 })
                val restoredChild = requireNotNull(restoredAgain.governorForRun("run-child"))
                val denied = assertIs<ResourceAdmission.Denied>(restoredChild.admit(GovernedResource.TOOL_START))
                assertEquals(ResourceLimit.TOOL_STARTS, denied.limit)
            }
        }

    @Test
    fun `stale concurrent registry fails closed`() =
        runTest {
            withPersistentDatabase { url ->
                val first = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 100 })
                val budget = ResourceBudget(maxLlmCalls = 3)
                val current =
                    assertIs<ResourceGovernorAcquisition.Acquired>(
                        first.acquire("run-concurrent", "session-concurrent", budget),
                    ).governor
                val stale =
                    requireNotNull(
                        PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 100 })
                            .governorForRun("run-concurrent"),
                    )

                assertIs<ResourceAdmission.Allowed>(current.admit(GovernedResource.LLM_CALL))
                assertFailsWith<IllegalStateException> {
                    stale.admit(GovernedResource.LLM_CALL)
                }
                assertEquals(1, DatabaseFactory.create(url).getResourceGovernorState("run-concurrent")?.llmCallsStarted)
            }
        }

    @Test
    fun `terminal run binding left by a crash does not block the next run`() =
        runTest {
            withPersistentDatabase { url ->
                val database = DatabaseFactory.create(url)
                val ledger = PersistentRunLedger(database)
                val run =
                    dev.promethe.api.AgentRunRecord(
                        runId = "run-finished",
                        sessionId = "session-reused",
                        origin = AgentExecutionOrigin.A2A.name,
                        status = dev.promethe.api.AgentRunStatus.PENDING,
                        createdAt = 100,
                        updatedAt = 100,
                    )
                kotlin.test.assertTrue(ledger.begin(run))
                val first = PersistentResourceGovernorRegistry(database, now = { 110 })
                assertIs<ResourceGovernorAcquisition.Acquired>(
                    first.acquire(run.runId, run.sessionId, ResourceBudget.DEFAULT),
                )
                kotlin.test.assertTrue(ledger.complete(run.runId, 0, null, 120))

                val reopened = PersistentResourceGovernorRegistry(DatabaseFactory.create(url), now = { 130 })
                assertIs<ResourceGovernorAcquisition.Acquired>(
                    reopened.acquire("run-next", run.sessionId, ResourceBudget.DEFAULT),
                )
            }
        }

    private suspend fun withPersistentDatabase(block: suspend (String) -> Unit) {
        val directory = createTempDirectory("promethe-resource-governor-test").toFile()
        val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
        try {
            block(url)
        } finally {
            directory.deleteRecursively()
        }
    }
}
