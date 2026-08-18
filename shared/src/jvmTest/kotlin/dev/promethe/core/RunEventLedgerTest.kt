package dev.promethe.core

import dev.promethe.api.AgentRunEventRecord
import dev.promethe.api.AgentRunEventType
import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.api.ToolIntentStatus
import dev.promethe.db.DatabaseFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunEventLedgerTest {
    @Test
    fun `run snapshot is reconstructed from ordered events`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val runLedger = PersistentRunLedger(database)
            val eventLedger = PersistentRunEventLedger(database)
            val run = pendingRun("event-run-0001")

            assertTrue(runLedger.begin(run))
            assertTrue(runLedger.recordStep(run.runId, "event-run-0001-step-0001", 1, 110))
            assertTrue(runLedger.complete(run.runId, 1, "event-run-0001-step-0001", 120))

            assertEquals(runLedger.get(run.runId), eventLedger.reconstructRun(run.runId))
            val events = eventLedger.events(run.runId)
            assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
            assertEquals(
                listOf(
                    AgentRunEventType.RUN_STARTED,
                    AgentRunEventType.RUN_STEP_RECORDED,
                    AgentRunEventType.RUN_FINISHED,
                ),
                events.map { it.type },
            )
        }

    @Test
    fun `tool snapshot and approval are reconstructed without secrets`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val run = pendingRun("event-tool-run-0001")
            assertTrue(PersistentRunLedger(database).begin(run))
            val toolLedger = PersistentToolIntentLedger(database, SequentialIds())
            val eventLedger = PersistentRunEventLedger(database)
            val request =
                ToolExecutionRequest(
                    toolName = "send_message",
                    arguments = buildJsonObject { put("token", "top-secret-token") },
                    sessionId = run.sessionId,
                    origin = ToolCallOrigin.A2A,
                    runId = run.runId,
                    stepId = "event-tool-run-0001-step-0001",
                )

            val admission = assertIs<ToolIntentAdmission.Proceed>(toolLedger.prepare(request, ToolRisk.EXTERNAL_EFFECT, 110))
            val intentId = requireNotNull(admission.intentId)
            assertTrue(
                toolLedger.recordApproval(
                    intentId,
                    request,
                    ApprovalGate.ApprovalResult(
                        allowed = true,
                        reason = "secret approval reason",
                        scope = ApprovalGate.ApprovalScope.ONCE,
                        approvalId = "approval-00000001",
                    ),
                    120,
                ),
            )
            assertTrue(toolLedger.markExecuting(intentId, 130))
            assertTrue(toolLedger.markSucceeded(intentId, "secret raw result", 140))

            val snapshot = assertNotNull(database.getToolIntent(intentId))
            assertEquals(snapshot, eventLedger.reconstructToolIntents(run.runId).single())
            val renderedEvents = eventLedger.events(run.runId).toString()
            assertFalse(renderedEvents.contains("top-secret-token"))
            assertFalse(renderedEvents.contains("secret approval reason"))
            assertFalse(renderedEvents.contains("secret raw result"))
            assertTrue(eventLedger.events(run.runId).any { it.type == AgentRunEventType.APPROVAL_RESOLVED })
        }

    @Test
    fun `executing external effect is detected as uncertain`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val run = pendingRun("event-uncertain-run-0001")
            assertTrue(PersistentRunLedger(database).begin(run))
            val toolLedger = PersistentToolIntentLedger(database, SequentialIds())
            val request =
                ToolExecutionRequest(
                    toolName = "send_message",
                    arguments = buildJsonObject { put("value", "hello") },
                    sessionId = run.sessionId,
                    origin = ToolCallOrigin.A2A,
                    runId = run.runId,
                )
            val admission = assertIs<ToolIntentAdmission.Proceed>(toolLedger.prepare(request, ToolRisk.EXTERNAL_EFFECT, 110))
            assertTrue(toolLedger.markExecuting(requireNotNull(admission.intentId), 120))

            val uncertain = PersistentRunEventLedger(database).uncertainToolIntents(run.runId).single()
            assertEquals(ToolIntentStatus.EXECUTING, uncertain.status)
            assertEquals(ToolRisk.EXTERNAL_EFFECT, uncertain.risk)
        }

    @Test
    fun `events survive restart and reject duplicate event identifiers`() =
        runTest {
            val directory = createTempDirectory("promethe-run-event-reopen").toFile()
            val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            val run = pendingRun("event-restart-run-0001")
            try {
                val firstDatabase = DatabaseFactory.create(url)
                val firstLedger = PersistentRunLedger(firstDatabase)
                assertTrue(firstLedger.begin(run))
                assertTrue(firstLedger.recordStep(run.runId, "event-restart-run-0001-step-0001", 1, 110))
                val startEvent = firstDatabase.getAgentRunEvents(run.runId).first()
                assertFalse(firstDatabase.appendAgentRunEvent(startEvent.copy(sequence = 0)))

                val reopenedDatabase = DatabaseFactory.create(url)
                assertEquals(
                    reopenedDatabase.getAgentRun(run.runId),
                    PersistentRunEventLedger(reopenedDatabase).reconstructRun(run.runId),
                )
                assertEquals(listOf(1L, 2L), reopenedDatabase.getAgentRunEvents(run.runId).map { it.sequence })
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `concurrent event writes allocate a contiguous run sequence`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val run = pendingRun("event-concurrent-run-0001")
            assertTrue(PersistentRunLedger(database).begin(run))

            val results =
                (1..20).map { index ->
                    async {
                        database.appendAgentRunEvent(
                            AgentRunEventRecord(
                                eventId = "event-concurrent-${index.toString().padStart(4, '0')}",
                                runId = run.runId,
                                type = AgentRunEventType.APPROVAL_RESOLVED,
                                approvalAllowed = index % 2 == 0,
                                createdAt = 100L + index,
                            ),
                        )
                    }
                }.awaitAll()

            assertTrue(results.all { it })
            assertEquals((1L..21L).toList(), database.getAgentRunEvents(run.runId).map { it.sequence })
        }

    private fun pendingRun(runId: String): AgentRunRecord =
        AgentRunRecord(
            runId = runId,
            parentRunId = "parent-run-0001",
            sessionId = "event-session",
            origin = AgentExecutionOrigin.A2A.name,
            projectId = "project-1",
            status = AgentRunStatus.PENDING,
            createdAt = 100,
            updatedAt = 100,
        )

    private class SequentialIds : ExecutionIdGenerator {
        private val sequence = AtomicInteger()

        override fun nextId(prefix: String): String = "$prefix-event-${sequence.incrementAndGet().toString().padStart(8, '0')}"
    }
}
