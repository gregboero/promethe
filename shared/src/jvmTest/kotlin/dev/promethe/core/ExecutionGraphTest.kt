package dev.promethe.core

import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.DatabaseFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionGraphTest {
    @Test
    fun `graph persists each step before exposing it`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val ledger = PersistentRunLedger(database)
            val identity = AgentRunIdentity("graph-run-0001")
            assertTrue(ledger.begin(pendingRun(identity.runId)))
            val trajectories =
                listOf(
                    ConversationTrajectory(inputs = mapOf("query" to "hello"), outputs = emptyMap(), thought = "thinking"),
                    ConversationTrajectory(inputs = mapOf("query" to "hello"), outputs = mapOf("response" to "done")),
                )
            val graph =
                DurableExecutionGraph(
                    loopExecutor = AgentLoopExecutor { flowOf(*trajectories.toTypedArray()) },
                    runLedger = ledger,
                    now = { 200L },
                )

            val transitions = mutableListOf<ExecutionGraphTransition>()
            graph.execute(request(identity)).collect { transition ->
                if (transition is ExecutionGraphTransition.StepPersisted) {
                    assertEquals(transition.index, ledger.get(identity.runId)?.stepCount)
                    assertEquals(transition.stepId, ledger.get(identity.runId)?.lastStepId)
                }
                transitions += transition
            }

            assertEquals(
                listOf(
                    ExecutionGraphTransition.NodeEntered::class,
                    ExecutionGraphTransition.NodeEntered::class,
                    ExecutionGraphTransition.StepPersisted::class,
                    ExecutionGraphTransition.NodeCompleted::class,
                    ExecutionGraphTransition.NodeEntered::class,
                    ExecutionGraphTransition.StepPersisted::class,
                    ExecutionGraphTransition.NodeCompleted::class,
                    ExecutionGraphTransition.NodeCompleted::class,
                ),
                transitions.map { it::class },
            )
            assertEquals(
                listOf(
                    ExecutionGraphNode.AGENT_LOOP,
                    ExecutionGraphNode.PERSIST_STEP,
                    ExecutionGraphNode.PERSIST_STEP,
                    ExecutionGraphNode.PERSIST_STEP,
                    ExecutionGraphNode.PERSIST_STEP,
                    ExecutionGraphNode.PERSIST_STEP,
                    ExecutionGraphNode.PERSIST_STEP,
                    ExecutionGraphNode.AGENT_LOOP,
                ),
                transitions.map(ExecutionGraphTransition::node),
            )
            assertEquals(2, ledger.get(identity.runId)?.stepCount)
        }

    @Test
    fun `graph fails closed when step persistence is unavailable`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val graph =
                DurableExecutionGraph(
                    loopExecutor =
                        AgentLoopExecutor {
                            flowOf(ConversationTrajectory(inputs = mapOf("query" to "hello"), outputs = emptyMap()))
                        },
                    runLedger = PersistentRunLedger(database),
                    now = { 200L },
                )
            val transitions = mutableListOf<ExecutionGraphTransition>()

            val error =
                assertFailsWith<AgentExecutionException> {
                    graph.execute(request(AgentRunIdentity("graph-run-0002"))).collect(transitions::add)
                }

            assertEquals("run_ledger_write_failed", error.code)
            assertEquals(3, transitions.size)
            assertIs<ExecutionGraphTransition.NodeEntered>(transitions[0])
            assertIs<ExecutionGraphTransition.NodeEntered>(transitions[1])
            val failed = assertIs<ExecutionGraphTransition.NodeFailed>(transitions[2])
            assertEquals(ExecutionGraphNode.PERSIST_STEP, failed.node)
        }

    @Test
    fun `graph request reaches the loop executor unchanged`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val ledger = PersistentRunLedger(database)
            val identity = AgentRunIdentity("graph-run-0003", "graph-parent-0001")
            assertTrue(ledger.begin(pendingRun(identity.runId)))
            var captured: ExecutionGraphRequest? = null
            val expected =
                request(identity).copy(
                    overrideProvider = "openai",
                    overrideModel = "test-model",
                    projectId = "project-1",
                    memoryNamespace = "project:1",
                )
            val graph =
                DurableExecutionGraph(
                    loopExecutor = AgentLoopExecutor { request ->
                        captured = request
                        flowOf(ConversationTrajectory(inputs = emptyMap(), outputs = mapOf("response" to "done")))
                    },
                    runLedger = ledger,
                )

            graph.execute(expected).toList()

            assertEquals(expected, captured)
        }

    @Test
    fun `graph reports an upstream loop failure on the active node`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val ledger = PersistentRunLedger(database)
            val identity = AgentRunIdentity("graph-run-0004")
            assertTrue(ledger.begin(pendingRun(identity.runId)))
            val graph =
                DurableExecutionGraph(
                    loopExecutor = AgentLoopExecutor { flow { throw AgentExecutionException("provider_failed", "offline") } },
                    runLedger = ledger,
                )
            val transitions = mutableListOf<ExecutionGraphTransition>()

            val error = assertFailsWith<AgentExecutionException> { graph.execute(request(identity)).collect(transitions::add) }

            assertEquals("provider_failed", error.code)
            assertEquals(2, transitions.size)
            val failed = assertIs<ExecutionGraphTransition.NodeFailed>(transitions.last())
            assertEquals(ExecutionGraphNode.AGENT_LOOP, failed.node)
        }

    @Test
    fun `graph does not reinterpret a downstream failure as a node failure`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val ledger = PersistentRunLedger(database)
            val identity = AgentRunIdentity("graph-run-0005")
            assertTrue(ledger.begin(pendingRun(identity.runId)))
            val graph =
                DurableExecutionGraph(
                    loopExecutor =
                        AgentLoopExecutor {
                            flowOf(ConversationTrajectory(inputs = emptyMap(), outputs = mapOf("response" to "done")))
                        },
                    runLedger = ledger,
                )
            val transitions = mutableListOf<ExecutionGraphTransition>()

            assertFailsWith<IllegalStateException> {
                graph.execute(request(identity)).collect { transition ->
                    transitions += transition
                    if (transition is ExecutionGraphTransition.StepPersisted) error("consumer failed")
                }
            }

            assertTrue(transitions.none { it is ExecutionGraphTransition.NodeFailed })
            assertEquals(1, ledger.get(identity.runId)?.stepCount)
        }

    private fun request(identity: AgentRunIdentity): ExecutionGraphRequest =
        ExecutionGraphRequest(
            identity = identity,
            sessionId = "graph-session",
            userInput = "hello",
            origin = AgentExecutionOrigin.A2A,
            toolCallOrigin = ToolCallOrigin.A2A,
        )

    private fun pendingRun(runId: String): AgentRunRecord =
        AgentRunRecord(
            runId = runId,
            sessionId = "graph-session",
            origin = AgentExecutionOrigin.A2A.name,
            status = AgentRunStatus.PENDING,
            createdAt = 100,
            updatedAt = 100,
        )
}
