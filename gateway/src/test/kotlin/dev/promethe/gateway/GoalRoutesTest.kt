package dev.promethe.gateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Integration tests for GoalRoutes — the routes are actually mounted and
 * exercised over HTTP, with the agent stubbed through [AcpExecutor].
 *
 * The stub's first call is the decomposition; returning non-JSON makes
 * GoalDecomposer fall back to a deterministic single-task plan.
 */
class GoalRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stub agent: call #1 = decomposition (non-JSON → single-task plan),
     * calls #2+ = task execution, optionally blocked on [taskGate] so tests
     * can observe the RUNNING state deterministically.
     */
    private class StubAgent(
        gateOpen: Boolean = true,
    ) : AcpExecutor {
        val taskGate = CompletableDeferred<Unit>().apply { if (gateOpen) complete(Unit) }
        val calls = AtomicInteger(0)

        override suspend fun execute(
            sessionId: String,
            text: String,
            channelHint: String,
        ): String {
            val n = calls.incrementAndGet()
            if (n == 1) return "free-form plan, not JSON"
            taskGate.await()
            return "task completed by stub"
        }
    }

    private fun ApplicationTestBuilder.configureApp(agent: StubAgent): GoalState {
        val state = GoalState()
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
        }
        routing {
            route("/api/v1") { goalRoutes(agent, state) }
        }
        return state
    }

    private suspend fun ApplicationTestBuilder.status(): GoalStatusResponse = json.decodeFromString(client.get("/api/v1/goal/status").bodyAsText())

    private suspend fun ApplicationTestBuilder.awaitState(
        vararg expected: String,
        timeoutMs: Long = 5_000,
    ): GoalStatusResponse =
        withTimeout(timeoutMs) {
            var s = status()
            while (s.state !in expected) {
                delay(25)
                s = status()
            }
            s
        }

    // ── Validation ───────────────────────────────────────────────

    @Test
    fun `blank goal is rejected with 400`() =
        testApplication {
            configureApp(StubAgent())

            val response =
                client.post("/api/v1/goal") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"goal":"   "}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `initial status is IDLE`() =
        testApplication {
            configureApp(StubAgent())
            assertEquals("IDLE", status().state)
        }

    // ── Full happy path ──────────────────────────────────────────

    @Test
    fun `goal runs through decomposition and execution to completion`() =
        testApplication {
            val agent = StubAgent(gateOpen = true)
            configureApp(agent)

            val response =
                client.post("/api/v1/goal") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"goal":"write release notes","budgetPreset":"MINIMAL"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("started"))

            val final = awaitState("COMPLETED", "FAILED", "BUDGET_EXCEEDED")
            assertEquals("COMPLETED", final.state)
            assertEquals("write release notes", final.goal)
            assertEquals(1, final.tasksTotal, "Non-JSON decomposition must yield a single-task plan")
            assertEquals(1, final.tasksCompleted)
            assertEquals(1, final.results.size)
            assertTrue(final.results[0].response.contains("task completed by stub"))
            assertTrue(agent.calls.get() >= 2, "Decomposition + at least one task call expected")
        }

    // ── Conflict guard ───────────────────────────────────────────

    @Test
    fun `second goal while one is running is rejected with 409`() =
        testApplication {
            val agent = StubAgent(gateOpen = false)
            configureApp(agent)

            client.post("/api/v1/goal") {
                contentType(ContentType.Application.Json)
                setBody("""{"goal":"long running goal"}""")
            }
            awaitState("RUNNING")

            val second =
                client.post("/api/v1/goal") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"goal":"another goal"}""")
                }
            assertEquals(HttpStatusCode.Conflict, second.status)

            agent.taskGate.complete(Unit)
            awaitState("COMPLETED", "FAILED")
        }

    // ── Stop ─────────────────────────────────────────────────────

    @Test
    fun `stop cancels a running goal and reports STOPPED`() =
        testApplication {
            val agent = StubAgent(gateOpen = false)
            configureApp(agent)

            client.post("/api/v1/goal") {
                contentType(ContentType.Application.Json)
                setBody("""{"goal":"goal to stop"}""")
            }
            awaitState("RUNNING")

            val stop = client.post("/api/v1/goal/stop")
            assertEquals(HttpStatusCode.OK, stop.status)
            assertEquals("STOPPED", status().state)
        }

    @Test
    fun `late background updates cannot overwrite stopped state`() {
        val state = GoalState()
        state.status = GoalStatusResponse(state = "RUNNING", currentTask = "before")

        state.markStopped()
        state.updateWhileActive { current -> current.copy(state = "RUNNING", currentTask = "late") }

        assertEquals("STOPPED", state.status.state)
        assertEquals("before", state.status.currentTask)
    }

    // ── State isolation (the old file-level globals leaked) ─────

    @Test
    fun `each mount gets its own state`() =
        testApplication {
            val agent = StubAgent()
            val stateA = GoalState()
            val stateB = GoalState()
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                        },
                    )
                }
            }
            routing {
                route("/a") { goalRoutes(agent, stateA) }
                route("/b") { goalRoutes(agent, stateB) }
            }

            client.post("/a/goal") {
                contentType(ContentType.Application.Json)
                setBody("""{"goal":"only in A"}""")
            }

            val statusB = json.decodeFromString<GoalStatusResponse>(client.get("/b/goal/status").bodyAsText())
            assertEquals("IDLE", statusB.state, "Mount B must not see mount A's goal")
        }
}
