package dev.promethe.core

import dev.promethe.api.PolicyDataTrust
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

/** Deterministic orchestration tests. The runner is injected; no model decision or native timing claim. */
class AdaptiveSessionHarnessTest {
    @Test fun `activation failure invalidates published version before any transformed observation`() =
        runTest {
            Lab().use { lab ->
                val id = lab.create()
                assertTrue(lab.harness.command(lab.request(), "evaluate", HarnessArguments(revision = id)).contains("passed=true"))
                assertTrue(lab.harness.command(lab.request(), "activate", HarnessArguments(revision = id)).startsWith("Pending"))
                lab.fail = true
                lab.harness.beginStep("one", "one", "activate")
                assertEquals("original", lab.harness.revisionKey("one"))
                assertFalse(lab.library.entries(SessionHarness.sha256("")).single().active)
                assertEquals(ProcessedObservation(lab.raw(7777)), lab.harness.process(lab.request(), lab.raw(7777)))
            }
        }

    @Test fun `cancelled revalidation cannot retain previous activation permission`() =
        runTest {
            Lab().use { lab ->
                val id = lab.publish()
                lab.cancelled = true
                try {
                    lab.harness.command(lab.request(), "evaluate", HarnessArguments(revision = id))
                } catch (_: CancellationException) {
                    // propagated
                }
                assertFalse(lab.store.get("one", id).validated)
                assertTrue(lab.harness.command(lab.request(), "activate", HarnessArguments(revision = id)).startsWith("[ERROR]"))
                assertEquals(ProcessedObservation(lab.raw(7)), lab.harness.process(lab.request(), lab.raw(7)))
                assertEquals(1, lab.library.entries(SessionHarness.sha256("")).size)
            }
        }

    private class Lab : AutoCloseable {
        val directory = Files.createTempDirectory("adaptive-harness")
        val store = HarnessStore(directory.resolve("lab.sqlite"))
        val library = HarnessAdaptationLibrary(store)
        var fail = false
        var wrong = false
        var cancelled = false
        var calls = 0
        val closed = mutableListOf<String>()
        val runner = object : HarnessRunner {
            override val language = HarnessLanguage.KOTLIN

            override suspend fun execute(
                source: String,
                observation: HarnessObservation,
                sessionId: String,
            ): String {
                calls++
                if (cancelled) throw CancellationException("test cancellation")
                check(!fail)
                if (source == "memorized" && observation.text.contains("-731")) return "42"
                if (wrong && observation.text.contains("7777")) return "wrong"
                return AdaptiveSessionHarness.expected(observation.text)
            }

            override suspend fun endSession(sessionId: String) {
                closed += sessionId
            }
        }

        fun controller(compatibility: String = "test-runtime") =
            AdaptiveSessionHarness(
                SessionHarness(store, runner, FileArtifactStore(directory.resolve("artifacts"))),
                store,
                runner,
                compatibility,
            )

        var harness = controller()

        fun request(
            session: String = "one",
            scope: String? = null,
        ) = ToolExecutionRequest("json_query", buildJsonObject {}, session, runId = session, workspaceRelativePath = scope)

        val args = HarnessAdaptationArguments(contractId = HarnessAdaptationEvaluator.CONTRACT, toolName = "json_query", remainingObservations = 8, maxAddedLatencyMillis = 300_000)

        fun raw(answer: Int) = "{\"answer\":$answer,\"noise\":\"${"x".repeat(10_000)}\"}"

        suspend fun observe(request: ToolExecutionRequest = request()) {
            harness.beginStep(request.sessionId, request.runId, "first")
            for (answer in 1..2) assertEquals(ProcessedObservation(raw(answer)), harness.process(request, raw(answer)))
        }

        suspend fun create(source: String = "correct"): String {
            observe()
            assertEquals("CREATE", Json.decodeFromString<HarnessAdaptationDecision>(harness.adapt(request(), args)).choice)
            val revision = Json.decodeFromString<HarnessRevision>(harness.command(request(), "propose", HarnessArguments(source = source, toolName = "json_query")))
            return revision.id
        }

        suspend fun publish(): String {
            val id = create()
            assertTrue(harness.command(request(), "evaluate", HarnessArguments(revision = id)).contains("passed=true"))
            assertTrue(harness.command(request(), "activate", HarnessArguments(revision = id)).startsWith("Pending"))
            harness.beginStep("one", "one", "second")
            return id
        }

        override fun close() {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `normal path needs distinct large observations contract work and allowance`() =
        runTest {
            Lab().use { lab ->
                val request = lab.request()
                lab.harness.beginStep("one", "one", "first")
                repeat(3) { lab.harness.process(request, lab.raw(1)) }
                assertEquals("ORIGINAL", Json.decodeFromString<HarnessAdaptationDecision>(lab.harness.adapt(request, lab.args)).choice)
                lab.harness.process(request, lab.raw(2))
                for (args in listOf(lab.args.copy(contractId = "summarize-everything"), lab.args.copy(maxAddedLatencyMillis = 0), lab.args.copy(remainingObservations = 2), lab.args.copy(maxAddedLatencyMillis = 1))) {
                    assertEquals("ORIGINAL", Json.decodeFromString<HarnessAdaptationDecision>(lab.harness.adapt(request, args)).choice)
                }
                assertEquals(0, lab.calls)
            }
        }

    @Test fun `heldout regression rejects memorized fixtures and blocks activation`() =
        runTest {
            Lab().use { lab ->
                val id = lab.create("memorized")
                assertTrue(lab.harness.command(lab.request(), "evaluate", HarnessArguments(revision = id)).contains("passed=false"))
                assertFalse(lab.store.get("one", id).validated)
                assertTrue(lab.harness.command(lab.request(), "activate", HarnessArguments(revision = id)).startsWith("[ERROR]"))
                assertTrue(lab.library.entries(SessionHarness.sha256("")).isEmpty())
                assertTrue(lab.closed.any { it.startsWith("eval-") })
            }
        }

    @Test fun `new session reuses durable source after reopening and revalidates before activation`() =
        runTest {
            Lab().use { lab ->
                val first = lab.publish()
                val transformed = lab.harness.process(lab.request(), lab.raw(7777))
                assertEquals(first, transformed.revision)
                assertTrue(transformed.text.startsWith("7777\n[harness revision="))
                lab.harness.endSession("one")
                assertEquals("original", lab.harness.revisionKey("one"))
                lab.harness = lab.controller()
                val request = lab.request("two")
                lab.observe(request)
                val calls = lab.calls
                val decision = Json.decodeFromString<HarnessAdaptationDecision>(lab.harness.adapt(request, lab.args))
                assertEquals("REUSE", decision.choice)
                assertTrue(lab.calls > calls)
                assertEquals("original", lab.harness.revisionKey("two"))
                lab.harness.beginStep("two", "two", "second")
                val reused = lab.harness.process(request, lab.raw(8888))
                assertTrue(reused.text.startsWith("8888\n[harness revision="))
                assertTrue(reused.revision != null && reused.revision != first)
                assertEquals(lab.store.get("one", first).hash, lab.store.get("two", reused.revision!!).hash)
                lab.harness.endSession("two")
                assertTrue(lab.closed.containsAll(listOf("one", "two")))
            }
        }

    @Test fun `unknown format stays unchanged and runtime semantic regression invalidates catalog entry`() =
        runTest {
            Lab().use { lab ->
                lab.publish()
                val unknown = "plain text " + "x".repeat(400)
                assertEquals(ProcessedObservation(unknown), lab.harness.process(lab.request(), unknown))
                lab.wrong = true
                assertEquals(ProcessedObservation(lab.raw(7777)), lab.harness.process(lab.request(), lab.raw(7777)))
                val entry = lab.library.entries(SessionHarness.sha256("")).single()
                assertFalse(entry.active)
                assertEquals("runtime_contract_failure", entry.reason)
                lab.harness.beginStep("one", "one", "third")
                assertEquals("original", lab.harness.revisionKey("one"))
            }
        }

    @Test fun `timeout invalidates reusable version and original survives`() =
        runTest {
            Lab().use { lab ->
                lab.publish()
                lab.fail = true
                assertEquals(ProcessedObservation(lab.raw(44)), lab.harness.process(lab.request(), lab.raw(44)))
                assertFalse(lab.library.entries(SessionHarness.sha256("")).single().active)
            }
        }

    @Test fun `invalidation is visible across active sessions and restoration creates new evidence`() =
        runTest {
            Lab().use { lab ->
                lab.publish()
                val entry = lab.library.entries(SessionHarness.sha256("")).single()
                val admin = lab.request("two")
                lab.harness.beginStep("two", "two", "first")
                lab.harness.adapt(admin, HarnessAdaptationArguments(operation = "invalidate", entryId = entry.id))
                assertEquals(ProcessedObservation(lab.raw(4)), lab.harness.process(lab.request(), lab.raw(4)))
                val restored = Json.decodeFromString<HarnessAdaptationDecision>(lab.harness.adapt(admin, HarnessAdaptationArguments(operation = "restore", entryId = entry.id)))
                assertEquals("ORIGINAL", restored.choice)
                assertTrue(restored.entryId != entry.id)
                assertEquals(2, lab.library.entries(entry.scope).size)
                assertFalse(lab.library.entries(entry.scope).first { it.id == entry.id }.active)
                assertEquals("original", lab.harness.revisionKey("two"))
            }
        }

    @Test fun `project runtime and trust boundaries prevent reuse`() =
        runTest {
            Lab().use { lab ->
                lab.publish()
                val other = lab.request("other", "different-project")
                lab.observe(other)
                assertEquals("CREATE", Json.decodeFromString<HarnessAdaptationDecision>(lab.harness.adapt(other, lab.args)).choice)
                lab.harness.endSession("one")
                lab.harness = lab.controller("different-runtime")
                val newRequest = lab.request("new")
                lab.observe(newRequest)
                assertEquals("CREATE", Json.decodeFromString<HarnessAdaptationDecision>(lab.harness.adapt(newRequest, lab.args)).choice)
                for (request in listOf(newRequest.copy(origin = ToolCallOrigin.A2A), newRequest.copy(dataTrust = PolicyDataTrust.UNTRUSTED))) {
                    assertTrue(lab.harness.adapt(request, lab.args).startsWith("[ERROR]"))
                    assertEquals(ProcessedObservation(lab.raw(3)), lab.harness.process(request, lab.raw(3)))
                }
            }
        }

    @Test fun `process restart does not restore active task contract`() =
        runTest {
            Lab().use { lab ->
                lab.publish()
                lab.harness = lab.controller()
                lab.harness.beginStep("one", "one", "resumed")
                assertEquals("original", lab.harness.revisionKey("one"))
                assertEquals(ProcessedObservation(lab.raw(77)), lab.harness.process(lab.request(), lab.raw(77)))
                assertEquals(1, lab.library.entries(SessionHarness.sha256("")).size)
            }
        }

    @Test fun `remaining observation allowance stops applying adaptation`() =
        runTest {
            Lab().use { lab ->
                lab.publish()
                repeat(8) { assertTrue(lab.harness.process(lab.request(), lab.raw(100 + it)).revision != null) }
                assertEquals(ProcessedObservation(lab.raw(500)), lab.harness.process(lab.request(), lab.raw(500)))
                assertTrue(lab.harness.command(lab.request(), "inspect", HarnessArguments()).contains("allowance exhausted"))
            }
        }

    @Test fun `cancelled evaluation closes isolated runner session and never publishes`() =
        runTest {
            Lab().use { lab ->
                val id = lab.create()
                lab.cancelled = true
                var propagated = false
                try {
                    lab.harness.command(lab.request(), "evaluate", HarnessArguments(revision = id))
                } catch (_: CancellationException) {
                    propagated = true
                }
                assertTrue(propagated)
                assertTrue(lab.closed.any { it.startsWith("eval-") })
                assertTrue(lab.library.entries(SessionHarness.sha256("")).isEmpty())
            }
        }
}
