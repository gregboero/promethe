package dev.promethe.core

import dev.promethe.api.AgentRunEventType
import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunRecoveryServiceTest {
    @Test
    fun `crash before a tool is classified recoverable and can be claimed once`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val ledger = PersistentRunLedger(database)
            assertTrue(ledger.begin(pendingRun("recovery-before-tool-0001")))
            val recovery = RunRecoveryService(ledger, PersistentRunEventLedger(database), now = { 200 })

            val assessment = recovery.auditInterruptedRuns().single()

            assertEquals(RunRecoveryDisposition.RECOVERABLE, assessment.disposition)
            assertEquals(AgentRunStatus.RECOVERABLE, ledger.get(assessment.run.runId)?.status)
            assertNotNull(recovery.claimResume(assessment.run.runId))
            assertNull(recovery.claimResume(assessment.run.runId), "Only one worker may claim a resume")
            assertTrue(recovery.markResumed(assessment.run.runId))
            assertEquals(AgentRunStatus.RUNNING, ledger.get(assessment.run.runId)?.status)
        }

    @Test
    fun `crash during an external effect requires review and cannot resume`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val runId = "recovery-during-effect-0001"
            val ledger = PersistentRunLedger(database)
            assertTrue(ledger.begin(pendingRun(runId)))
            val intents = PersistentToolIntentLedger(database, FixedExecutionIdGenerator("tool-recovery-0001"))
            val admission = assertIs<ToolIntentAdmission.Proceed>(
                intents.prepare(toolRequest(runId), ToolRisk.EXTERNAL_EFFECT, 110),
            )
            assertTrue(intents.markExecuting(requireNotNull(admission.intentId), 120))
            val recovery = RunRecoveryService(ledger, PersistentRunEventLedger(database), now = { 200 })

            val assessment = recovery.auditInterruptedRuns().single()

            assertEquals(RunRecoveryDisposition.NEEDS_REVIEW, assessment.disposition)
            assertEquals(listOf("tool-recovery-0001"), assessment.uncertainIntentIds)
            assertEquals(AgentRunStatus.NEEDS_REVIEW, ledger.get(runId)?.status)
            assertNull(recovery.claimResume(runId))
        }

    @Test
    fun `crash after an external effect preserves idempotence without a double effect`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val runId = "recovery-after-effect-0001"
            val ledger = PersistentRunLedger(database)
            assertTrue(ledger.begin(pendingRun(runId)))
            val intents = PersistentToolIntentLedger(database, FixedExecutionIdGenerator("tool-recovery-0002"))
            val request = toolRequest(runId)
            val admission = assertIs<ToolIntentAdmission.Proceed>(
                intents.prepare(request, ToolRisk.EXTERNAL_EFFECT, 110),
            )
            val intentId = requireNotNull(admission.intentId)
            assertTrue(intents.markExecuting(intentId, 120))
            assertTrue(intents.markSucceeded(intentId, "sent", 130))
            val recovery = RunRecoveryService(ledger, PersistentRunEventLedger(database), now = { 200 })

            val assessment = recovery.auditInterruptedRuns().single()
            val replay = assertIs<ToolIntentAdmission.Replay>(
                intents.prepare(request, ToolRisk.EXTERNAL_EFFECT, 210),
            )

            assertEquals(RunRecoveryDisposition.RECOVERABLE, assessment.disposition)
            assertEquals(intentId, replay.intentId)
            assertEquals(
                1,
                database.getAgentRunEvents(runId).count { it.type == AgentRunEventType.TOOL_STARTED },
                "The external effect must not start twice",
            )
        }

    @Test
    fun `recovery transitions survive database restart`() =
        runTest {
            val directory = kotlin.io.path.createTempDirectory("promethe-run-recovery-test").toFile()
            val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            try {
                val firstDatabase = DatabaseFactory.create(url)
                val firstLedger = PersistentRunLedger(firstDatabase)
                assertTrue(firstLedger.begin(pendingRun("recovery-restart-0001")))

                val reopenedDatabase = DatabaseFactory.create(url)
                val reopenedLedger = PersistentRunLedger(reopenedDatabase)
                val assessment =
                    RunRecoveryService(
                        reopenedLedger,
                        PersistentRunEventLedger(reopenedDatabase),
                        now = { 300 },
                    ).auditInterruptedRuns().single()

                assertEquals(RunRecoveryDisposition.RECOVERABLE, assessment.disposition)
                assertEquals(AgentRunStatus.RECOVERABLE, reopenedLedger.get(assessment.run.runId)?.status)
                assertEquals(
                    AgentRunStatus.RECOVERABLE,
                    PersistentRunEventLedger(reopenedDatabase).reconstructRun(assessment.run.runId)?.status,
                )
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `execution service resumes the same run and continues its steps`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val runId = "recovery-service-0001"
            val ledger = PersistentRunLedger(database)
            val request =
                AgentExecutionRequest(
                    sessionId = "recovery-session",
                    text = "continue",
                    origin = AgentExecutionOrigin.A2A,
                    runId = runId,
                )
            assertTrue(ledger.begin(pendingRun(runId, agentExecutionRequestFingerprint(request))))
            assertTrue(ledger.recordStep(runId, "$runId-step-0001", 1, 110))
            val graph =
                DurableExecutionGraph(
                    loopExecutor =
                        AgentLoopExecutor {
                            flowOf(
                                ConversationTrajectory(
                                    inputs = emptyMap(),
                                    outputs = mapOf("response" to "resumed"),
                                ),
                            )
                        },
                    runLedger = ledger,
                    now = { 220 },
                )
            val resources = TestAgentResources(database)
            try {
                val service =
                    AgentExecutionService(
                        agent = resources.agent,
                        database = database,
                        runLedger = ledger,
                        executionGraph = graph,
                        recoveryService =
                            RunRecoveryService(
                                ledger,
                                PersistentRunEventLedger(database),
                                now = { 200 },
                            ),
                    )

                val events =
                    service
                        .resume(request)
                        .toList()

                val completed = assertIs<AgentExecutionEvent.Completed>(events.last())
                assertEquals("resumed", completed.response)
                assertEquals(2, completed.steps)
                assertEquals(AgentRunStatus.SUCCEEDED, ledger.get(runId)?.status)
                assertEquals("$runId-step-0002", ledger.get(runId)?.lastStepId)
                assertEquals(
                    listOf(
                        AgentRunEventType.RUN_RECOVERY_CLASSIFIED,
                        AgentRunEventType.RUN_RESUME_CLAIMED,
                        AgentRunEventType.RUN_RESUMED,
                    ),
                    database
                        .getAgentRunEvents(runId)
                        .map { it.type }
                        .filter {
                            it == AgentRunEventType.RUN_RECOVERY_CLASSIFIED ||
                                it == AgentRunEventType.RUN_RESUME_CLAIMED ||
                                it == AgentRunEventType.RUN_RESUMED
                        },
                )
            } finally {
                resources.close()
            }
        }

    @Test
    fun `execution service refuses a resume with changed input`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val runId = "recovery-request-mismatch-0001"
            val original =
                AgentExecutionRequest(
                    sessionId = "recovery-session",
                    text = "send the approved report",
                    origin = AgentExecutionOrigin.A2A,
                    runId = runId,
                )
            val ledger = PersistentRunLedger(database)
            assertTrue(ledger.begin(pendingRun(runId, agentExecutionRequestFingerprint(original))))
            var graphEntered = false
            val graph =
                object : ExecutionGraph {
                    override fun execute(request: ExecutionGraphRequest) = flowOf<ExecutionGraphTransition>().also { graphEntered = true }
                }
            val resources = TestAgentResources(database)
            try {
                val service =
                    AgentExecutionService(
                        agent = resources.agent,
                        database = database,
                        runLedger = ledger,
                        executionGraph = graph,
                        recoveryService =
                            RunRecoveryService(
                                ledger,
                                PersistentRunEventLedger(database),
                                now = { 200 },
                            ),
                    )

                val failed = assertIs<AgentExecutionEvent.Failed>(
                    service.resume(original.copy(text = "send a different report")).toList().single(),
                )

                assertEquals("resume_context_mismatch", failed.code)
                assertEquals(false, graphEntered)
                assertEquals(AgentRunStatus.RUNNING, ledger.get(runId)?.status)
            } finally {
                resources.close()
            }
        }

    @Test
    fun `resource conflict leaves a recoverable run unclaimed`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val runId = "recovery-resource-conflict-0001"
            val request =
                AgentExecutionRequest(
                    sessionId = "recovery-session",
                    text = "continue safely",
                    origin = AgentExecutionOrigin.A2A,
                    runId = runId,
                )
            val ledger = PersistentRunLedger(database)
            assertTrue(ledger.begin(pendingRun(runId, agentExecutionRequestFingerprint(request))))
            val governors = InMemoryResourceGovernorRegistry()
            assertIs<ResourceGovernorAcquisition.Acquired>(
                governors.acquire("active-run-0001", request.sessionId, ResourceBudget.DEFAULT),
            )
            val resources = TestAgentResources(database)
            try {
                val service =
                    AgentExecutionService(
                        agent = resources.agent,
                        database = database,
                        runLedger = ledger,
                        executionGraph = object : ExecutionGraph {
                            override fun execute(request: ExecutionGraphRequest) = flowOf<ExecutionGraphTransition>()
                        },
                        recoveryService =
                            RunRecoveryService(
                                ledger,
                                PersistentRunEventLedger(database),
                                now = { 200 },
                            ),
                        resourceGovernors = governors,
                    )

                val failed = assertIs<AgentExecutionEvent.Failed>(service.resume(request).toList().single())

                assertEquals("run_resume_conflict", failed.code)
                assertEquals(AgentRunStatus.RECOVERABLE, ledger.get(runId)?.status)
                assertTrue(
                    database.getAgentRunEvents(runId).none { it.type == AgentRunEventType.RUN_RESUME_CLAIMED },
                )
            } finally {
                resources.close()
            }
        }

    private fun pendingRun(
        runId: String,
        requestFingerprint: String = "test-request-fingerprint",
    ): AgentRunRecord =
        AgentRunRecord(
            runId = runId,
            sessionId = "recovery-session",
            origin = AgentExecutionOrigin.A2A.name,
            requestFingerprint = requestFingerprint,
            status = AgentRunStatus.PENDING,
            createdAt = 100,
            updatedAt = 100,
        )

    private fun toolRequest(runId: String): ToolExecutionRequest =
        ToolExecutionRequest(
            toolName = "send_message",
            arguments = buildJsonObject { put("message", "hello") },
            sessionId = "recovery-session",
            origin = ToolCallOrigin.A2A,
            runId = runId,
            stepId = "$runId-step-0001",
        )

    private class FixedExecutionIdGenerator(
        private val id: String,
    ) : ExecutionIdGenerator {
        override fun nextId(prefix: String): String = id
    }

    private class TestAgentResources(
        database: PrometheDatabaseApi,
    ) {
        private val config = AgentConfig()
        private val httpClient = HttpClient()
        private val adapter = KoogLlmAdapter(config)
        private val skillsDirectory = getProfileDirectoryPath(config) / "skills"
        val agent =
            AIAgent(
                config = config,
                database = database,
                llmAdapter = adapter,
                profileManager = ProfileManager(null),
                actionExecutor = ActionExecutor(config, httpClient),
                skillLoader = SkillLoader(getFileSystem(), skillsDirectory),
                trajectoryEvaluator = TrajectoryEvaluator(adapter, config),
                skillWriter = SkillWriter(getFileSystem(), skillsDirectory),
            )

        fun close() {
            httpClient.close()
        }
    }
}
