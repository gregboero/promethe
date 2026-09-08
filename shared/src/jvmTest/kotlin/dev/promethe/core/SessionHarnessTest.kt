package dev.promethe.core

import dev.promethe.api.PolicyDataTrust
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** State-machine tests use an injected runner, not a claim of native isolation. */
class SessionHarnessTest {
    @Test fun `ending session clears runner compilation state`() =
        runTest {
            val lab = Lab()
            try {
                val cleared = mutableListOf<String>()
                val runner = object : HarnessRunner {
                    override suspend fun execute(
                        source: String,
                        observation: HarnessObservation,
                        sessionId: String,
                    ) = observation.text

                    override suspend fun endSession(sessionId: String) {
                        cleared.add(sessionId)
                    }
                }
                val harness = SessionHarness(lab.store, runner, lab.artifacts)
                harness.endSession("one")
                assertEquals(listOf("one"), cleared)
            } finally {
                lab.close()
            }
        }

    @Test fun `Kotlin revision language survives storage and cannot run in JavaScript`() =
        runTest {
            val lab = Lab()
            try {
                val kotlin = SessionHarness(
                    lab.store,
                    object : HarnessRunner {
                        override val language = HarnessLanguage.KOTLIN

                        override suspend fun execute(
                            source: String,
                            observation: HarnessObservation,
                            sessionId: String,
                        ) = lab.runner.execute(source, observation, sessionId)
                    },
                    lab.artifacts,
                )
                val json = kotlin.command(lab.request(), "propose", HarnessArguments(source = "observation.text"))
                val revision = Json.decodeFromString<HarnessRevision>(json)
                assertEquals(HarnessLanguage.KOTLIN, revision.language)
                assertTrue(kotlin.command(lab.request(), "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
                assertTrue(lab.harness.command(lab.request(), "activate", HarnessArguments(revision = revision.id)).startsWith("[ERROR]"))
                assertTrue(kotlin.command(lab.request(), "inspect", HarnessArguments()).contains(".kts"))
                val legacy = json.replace(",\"language\":\"KOTLIN\"", "")
                assertEquals(HarnessLanguage.JAVASCRIPT, Json.decodeFromString<HarnessRevision>(legacy).language)
            } finally {
                lab.close()
            }
        }

    @Test fun `default guard skips tiny inputs and never expands observations`() =
        runTest {
            val lab = Lab()
            try {
                lab.harness.beginStep("one", "run", "first")
                val id = lab.revision()
                lab.activate(id, "second")
                var calls = 0
                val guarded = SessionHarness(
                    lab.store,
                    HarnessRunner { source, observation, session ->
                        calls++
                        lab.runner.execute(source, observation, session)
                    },
                    lab.artifacts,
                )
                val tiny = "{\"answer\":7}"
                assertEquals(ProcessedObservation(tiny), guarded.process(lab.request(), tiny))
                assertEquals(0, calls)
                assertTrue(lab.raw.isEmpty())
                val large = "{\"answer\":7,\"noise\":\"${"x".repeat(500)}\"}"
                val result = guarded.process(lab.request(), large)
                assertEquals(id, result.revision)
                assertTrue(result.text.encodeToByteArray().size < large.encodeToByteArray().size)
                val unknown = "unknown " + "x".repeat(500)
                assertEquals(ProcessedObservation(unknown), guarded.process(lab.request(), unknown))
                assertEquals(2, calls)
                assertEquals(id, guarded.revisionKey("one")) // Skipping a presentation does not disable a useful revision.
            } finally {
                lab.close()
            }
        }

    @Test fun `guarded processor failure preserves observation and disables broken revision`() =
        runTest {
            val lab = Lab()
            try {
                lab.harness.beginStep("one", "run", "first")
                lab.activate(lab.revision(), "second")
                val guarded = SessionHarness(lab.store, HarnessRunner { _, _, _ -> error("processor failure") }, lab.artifacts)
                val raw = "x".repeat(500)
                assertEquals(ProcessedObservation(raw), guarded.process(lab.request(), raw))
                assertEquals("original", guarded.revisionKey("one"))
            } finally {
                lab.close()
            }
        }

    private class Lab {
        val directory = Files.createTempDirectory("harness-test")
        val path = directory.resolve("lab.sqlite")
        val store = HarnessStore(path)
        val raw = mutableListOf<String>()
        var failing: String? = null
        val runner = HarnessRunner { source, observation, _ ->
            check(source != failing && source != "bad")
            when {
                observation.text.startsWith("{") -> Json.parseToJsonElement(observation.text).let { (it as kotlinx.serialization.json.JsonObject)["answer"]!!.jsonPrimitive.content }
                observation.text.startsWith("id,answer") -> observation.text.substringAfterLast(',')
                observation.text.startsWith("DATA answer=") -> observation.text.substringAfter('=')
                else -> observation.text
            }
        }
        val artifacts = object : ArtifactStore {
            override suspend fun put(request: ArtifactWriteRequest): ArtifactReference {
                raw.add(request.content.decodeToString())
                val hash = SessionHarness.sha256(raw.last())
                return ArtifactReference(hash, "artifact://$hash", request.content.size.toLong(), request.mediaType)
            }

            override suspend fun read(hash: String): ByteArray? = null
        }
        val harness = SessionHarness(store, runner, artifacts, preferSmallerObservations = false)

        fun request(session: String = "one") = ToolExecutionRequest("read_file", buildJsonObject {}, session, runId = "run", stepId = "step")

        suspend fun revision(
            source: String = "good",
            base: String? = null,
        ): String {
            val result = harness.command(request(), "propose", HarnessArguments(source = source, baseRevision = base))
            return Json.decodeFromString<HarnessRevision>(result).id
        }

        suspend fun activate(
            id: String,
            step: String,
        ) {
            assertTrue(harness.command(request(), "evaluate", HarnessArguments(revision = id)).contains("passed=true"))
            assertTrue(harness.command(request(), "activate", HarnessArguments(revision = id)).startsWith("Pending"))
            harness.beginStep("one", "run", step)
        }

        fun close() {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `activation waits for next step and preserves raw artifact`() =
        runTest {
            val lab = Lab()
            try {
                lab.harness.beginStep("one", "run", "first")
                val id = lab.revision()
                lab.activate(id, "first")
                assertEquals("original", lab.harness.revisionKey("one"))
                lab.harness.beginStep("one", "run", "second")
                val result = lab.harness.process(lab.request(), "{\"answer\":7}")
                assertEquals(id, result.revision)
                assertTrue(result.text.startsWith("7\n"))
                assertEquals(listOf("{\"answer\":7}"), lab.raw)
                assertEquals("original", lab.harness.revisionKey("two"))
                assertTrue(lab.harness.command(lab.request("two"), "activate", HarnessArguments(revision = id)).startsWith("[ERROR]"))
                lab.harness.endSession("one")
                assertEquals("original", lab.harness.revisionKey("one"))
            } finally {
                lab.close()
            }
        }

    @Test fun `invalid untrusted and stale proposals cannot activate`() =
        runTest {
            val lab = Lab()
            try {
                val bad = lab.revision("bad")
                assertTrue(lab.harness.command(lab.request(), "evaluate", HarnessArguments(revision = bad)).contains("passed=false"))
                assertTrue(lab.harness.command(lab.request(), "activate", HarnessArguments(revision = bad)).startsWith("[ERROR]"))
                assertTrue(lab.harness.command(lab.request().copy(dataTrust = PolicyDataTrust.UNTRUSTED), "propose", HarnessArguments(source = "good")).startsWith("[ERROR]"))
                val good = lab.revision()
                lab.activate(good, "first")
                assertTrue(lab.harness.command(lab.request(), "propose", HarnessArguments(source = "good")).startsWith("[ERROR]"))
            } finally {
                lab.close()
            }
        }

    @Test fun `execution failure restores prior revision and raw observation`() =
        runTest {
            val lab = Lab()
            try {
                val first = lab.revision()
                lab.activate(first, "first")
                val second = lab.revision("second", first)
                lab.activate(second, "second")
                lab.failing = "second"
                assertEquals(ProcessedObservation("raw"), lab.harness.process(lab.request(), "raw"))
                assertEquals(first, lab.harness.revisionKey("one"))
                lab.harness.command(lab.request(), "disable", HarnessArguments())
                lab.harness.beginStep("one", "run", "third")
                assertEquals("original", lab.harness.revisionKey("one"))
            } finally {
                lab.close()
            }
        }

    @Test fun `activation revalidates and retains old revision on rejection`() =
        runTest {
            val lab = Lab()
            try {
                val first = lab.revision()
                lab.activate(first, "first")
                val next = lab.revision("second", first)
                lab.harness.command(lab.request(), "evaluate", HarnessArguments(revision = next))
                lab.harness.command(lab.request(), "activate", HarnessArguments(revision = next))
                lab.failing = "second"
                lab.harness.beginStep("one", "run", "second")
                assertEquals(first, lab.harness.revisionKey("one"))
                assertNull(lab.store.state("one").pending)
            } finally {
                lab.close()
            }
        }

    @Test fun `restart revalidates same run and new run resets`() =
        runTest {
            val lab = Lab()
            try {
                val first = lab.revision()
                lab.activate(first, "first")
                val reopened = SessionHarness(HarnessStore(lab.path), lab.runner, lab.artifacts, preferSmallerObservations = false)
                reopened.beginStep("one", "run", "resumed")
                assertEquals(first, reopened.revisionKey("one"))
                reopened.beginStep("one", "different-run", "new")
                assertEquals("original", reopened.revisionKey("one"))
            } finally {
                lab.close()
            }
        }

    @Test fun `tampered source never executes on restart`() =
        runTest {
            val lab = Lab()
            try {
                val id = lab.revision()
                lab.activate(id, "first")
                lab.store.transaction { db -> db.createStatement().use { it.executeUpdate("UPDATE harness_revisions SET body=replace(body,'good','evil')") } }
                val reopened = SessionHarness(HarnessStore(lab.path), lab.runner, lab.artifacts, preferSmallerObservations = false)
                reopened.beginStep("one", "run", "resume")
                assertEquals("original", reopened.revisionKey("one"))
            } finally {
                lab.close()
            }
        }

    @Test fun `explicit rollback is deferred and restores previous revision`() =
        runTest {
            val lab = Lab()
            try {
                val first = lab.revision()
                lab.activate(first, "first")
                val second = lab.revision("second", first)
                lab.activate(second, "second")
                assertTrue(lab.harness.command(lab.request(), "rollback", HarnessArguments()).startsWith("Rollback"))
                assertEquals(second, lab.harness.revisionKey("one"))
                lab.harness.beginStep("one", "run", "third")
                assertEquals(first, lab.harness.revisionKey("one"))
            } finally {
                lab.close()
            }
        }

    @Test fun `concurrent sessions activate only their own revisions`() =
        runTest {
            val lab = Lab()
            try {
                val ids = listOf("one", "two").map { session ->
                    async(Dispatchers.IO) {
                        val request = lab.request(session)
                        lab.harness.beginStep(session, "run", "first")
                        val proposed = lab.harness.command(request, "propose", HarnessArguments(source = "good"))
                        val id = Json.decodeFromString<HarnessRevision>(proposed).id
                        lab.harness.command(request, "evaluate", HarnessArguments(revision = id))
                        lab.harness.command(request, "activate", HarnessArguments(revision = id))
                        lab.harness.beginStep(session, "run", "second")
                        assertEquals(id, lab.harness.revisionKey(session))
                        id
                    }
                }.awaitAll()
                assertEquals(2, ids.toSet().size)
                lab.harness.endSession("one")
                assertEquals(ids[1], lab.harness.revisionKey("two"))
            } finally {
                lab.close()
            }
        }

    @Test fun `budget serializes concurrent reservations and retains uncertainty after restart`() =
        runTest {
            val lab = Lab()
            try {
                val stores = List(12) { HarnessStore(lab.path) }
                val admitted = stores.mapIndexed { i, store -> async(Dispatchers.IO) { store.reserve("request-$i", 1_000_000) } }.awaitAll()
                assertEquals(5, admitted.count { it })
                assertFalse(HarnessStore(lab.path).reserve("after-crash", 1))
                val id = "request-${admitted.indexOf(true)}"
                lab.store.settle(id, 100)
                lab.store.settle(id, 100)
                assertFailsWith<IllegalArgumentException> { lab.store.settle(id, 101) }
                assertTrue(lab.store.reserve("remaining", 999_900))
                assertFalse(lab.store.reserve("over-cap", 1))
            } finally {
                lab.close()
            }
        }
}
