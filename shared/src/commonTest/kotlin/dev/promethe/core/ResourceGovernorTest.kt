package dev.promethe.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ResourceGovernorTest {
    @Test
    fun `concurrent admissions cannot exceed the call limit`() =
        runTest {
            val governor =
                ResourceGovernor(
                    rootRunId = "run-concurrent",
                    budget = ResourceBudget(maxLlmCalls = 3),
                )

            val admissions =
                coroutineScope {
                    List(20) { async { governor.admit(GovernedResource.LLM_CALL) } }.awaitAll()
                }

            assertEquals(3, admissions.count { it is ResourceAdmission.Allowed })
            assertEquals(17, admissions.count { it is ResourceAdmission.Denied })
            assertEquals(3, governor.snapshot().llmCallsStarted)
        }

    @Test
    fun `recorded token and cost usage fail closed before the next start`() =
        runTest {
            val governor =
                ResourceGovernor(
                    rootRunId = "run-usage",
                    budget = ResourceBudget(maxTokens = 10, maxCostDollars = 0.5),
                )

            assertIs<ResourceAdmission.Allowed>(governor.admit(GovernedResource.LLM_CALL))
            governor.recordLlmUsage(tokens = 10, cost = 0.25)

            val denied = assertIs<ResourceAdmission.Denied>(governor.admit(GovernedResource.TOOL_START))
            assertEquals(ResourceLimit.TOKENS, denied.limit)
            assertEquals(0, denied.snapshot.toolsStarted)
        }

    @Test
    fun `duration exhaustion blocks every governed resource`() =
        runTest {
            var now = 100L
            val governor =
                ResourceGovernor(
                    rootRunId = "run-duration",
                    budget = ResourceBudget(maxDurationMs = 50),
                    now = { now },
                )
            now = 150L

            val denied = assertIs<ResourceAdmission.Denied>(governor.admit(GovernedResource.SUB_AGENT))
            assertEquals(ResourceLimit.DURATION, denied.limit)
        }

    @Test
    fun `child runs inherit the root governor and sub-agent budget`() =
        runTest {
            val registry = InMemoryResourceGovernorRegistry()
            val root =
                assertIs<ResourceGovernorAcquisition.Acquired>(
                    registry.acquire(
                        runId = "run-root",
                        sessionId = "session-root",
                        budget = ResourceBudget(maxSubAgents = 1),
                    ),
                ).governor

            assertIs<ChildResourceBinding.Bound>(registry.bindChild("session-root", "session-child"))
            val child =
                assertIs<ResourceGovernorAcquisition.Acquired>(
                    registry.acquire("run-child", "session-child", ResourceBudget.DEFAULT),
                ).governor

            assertSame(root, child)
            assertEquals("run-root", child.rootRunId)
            val denied = assertIs<ChildResourceBinding.Denied>(registry.bindChild("session-root", "session-other"))
            assertEquals(ResourceLimit.SUB_AGENTS, denied.admission.limit)
        }

    @Test
    fun `a session cannot be claimed by concurrent runs`() =
        runTest {
            val registry = InMemoryResourceGovernorRegistry()
            assertIs<ResourceGovernorAcquisition.Acquired>(
                registry.acquire("run-one", "session-shared", ResourceBudget.DEFAULT),
            )

            val conflict =
                assertIs<ResourceGovernorAcquisition.Conflict>(
                    registry.acquire("run-two", "session-shared", ResourceBudget.DEFAULT),
                )

            assertEquals("run-one", conflict.activeRunId)
        }
}
