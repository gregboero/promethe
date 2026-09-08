package dev.promethe.core

import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Real Kotlin sandbox, deterministic task/decisions, no model or provider access. */
class HarnessAdaptationNativeTest {
    @Test fun `evaluated Kotlin adaptation survives restart and serves unseen task`() =
        runBlocking<Unit> {
            check(System.getenv("PROMETHE_HARNESS_ADAPTATION_NATIVE") == "true")

            fun env(name: String) = Path.of(requireNotNull(System.getenv(name)))
            val output = Files.createDirectories(env("PROMETHE_HARNESS_KOTLIN_REPORT"))
            check(!Files.exists(output.resolve("summary.json"))) { "Use a fresh evidence directory" }
            val source = Files.readString(env("PROMETHE_HARNESS_CANDIDATE"))
            assertEquals(HarnessKotlinAmortizationLiveTest.CANDIDATE_HASH, SessionHarness.sha256(source))
            val store = HarnessStore(output.resolve("adaptive.sqlite"))
            val library = HarnessAdaptationLibrary(store)
            val started = System.nanoTime()
            var rawBytes = 0L
            var presentedBytes = 0L
            var transformed = 0
            val decisions = mutableListOf<HarnessAdaptationDecision>()
            val metrics = mutableListOf<KotlinHarnessMetrics>()
            NativeSandboxManager(env("PROMETHE_HARNESS_HELPER"), JvmSandboxHelperProcessFactory).use { sandbox ->
                val status = sandbox.selfTest()
                Files.writeString(output.resolve("preflight.json"), Json.encodeToString(status))
                check(status.selfTestPassed) { status.message.orEmpty() }
                val runner = HarnessKotlinRunner(sandbox, env("PROMETHE_HARNESS_KOTLIN_DIST"), env("PROMETHE_HARNESS_JAVA_RUNTIME"), env("PROMETHE_HARNESS_SCRATCH"), metrics = { metrics += it })
                val compatibility = runner.compatibilityKey()
                var previousRevision: String? = null
                repeat(2) { task ->
                    // Recreate the controller and the database facade to exercise durable import.
                    val reopened = HarnessStore(output.resolve("adaptive.sqlite"))
                    val harness = AdaptiveSessionHarness(SessionHarness(reopened, runner, FileArtifactStore(output.resolve("artifacts"))), reopened, runner, compatibility)
                    val session = "adaptive-native-${UUID.randomUUID()}"
                    val request = ToolExecutionRequest("json_query", buildJsonObject {}, session, runId = session)
                    val inputs = (0..7).map { "{\"answer\":${901 + task * 100 + it},\"noise\":\"${"irrelevant ".repeat(1000)}\"}" }
                    try {
                        harness.beginStep(session, session, "observe")
                        for (raw in inputs.take(2)) assertEquals(ProcessedObservation(raw), harness.process(request, raw))
                        val decision = Json.decodeFromString<HarnessAdaptationDecision>(harness.adapt(request, HarnessAdaptationArguments(contractId = HarnessAdaptationEvaluator.CONTRACT, toolName = "json_query", remainingObservations = 6, maxAddedLatencyMillis = 300_000)))
                        decisions += decision
                        assertEquals(if (task == 0) "CREATE" else "REUSE", decision.choice)
                        if (task == 0) {
                            val revision = Json.decodeFromString<HarnessRevision>(harness.command(request, "propose", HarnessArguments(source = source, toolName = "json_query")))
                            assertTrue(harness.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
                            assertTrue(harness.command(request, "activate", HarnessArguments(revision = revision.id)).startsWith("Pending"))
                        }
                        harness.beginStep(session, session, "active")
                        val currentRevision = harness.revisionKey(session)
                        assertTrue(currentRevision != "original" && currentRevision != previousRevision)
                        previousRevision = currentRevision
                        for ((index, raw) in inputs.drop(2).withIndex()) {
                            harness.beginStep(session, session, "page-$index")
                            val result = harness.process(request, raw)
                            assertEquals(currentRevision, result.revision)
                            assertEquals((903 + task * 100 + index).toString(), result.text.substringBeforeLast("\n[harness revision="))
                            val hash = SessionHarness.sha256(raw)
                            assertTrue(result.text.contains("raw=artifact://$hash"))
                            assertEquals(raw, FileArtifactStore(output.resolve("artifacts")).read(hash)!!.decodeToString())
                            rawBytes += raw.encodeToByteArray().size
                            presentedBytes += result.text.encodeToByteArray().size
                            transformed++
                        }
                        if (task == 1) {
                            val entry = library.entries(SessionHarness.sha256("")).single()
                            harness.adapt(request, HarnessAdaptationArguments(operation = "invalidate", entryId = entry.id))
                            val original = harness.process(request, inputs.first())
                            assertEquals(ProcessedObservation(inputs.first()), original)
                        }
                    } finally {
                        harness.endSession(session)
                        assertEquals("original", harness.revisionKey(session))
                        assertEquals(0, runner.preparedRuntimeEntries())
                    }
                }
                Files.writeString(output.resolve("compatibility.txt"), compatibility)
            }
            assertEquals(12, transformed)
            assertTrue(presentedBytes < rawBytes)
            Files.writeString(output.resolve("decisions.json"), Json.encodeToString(decisions))
            Files.writeString(output.resolve("library.json"), Json.encodeToString(library.entries(SessionHarness.sha256("")).map { it.copy(source = "") }))
            Files.writeString(
                output.resolve("summary.json"),
                buildJsonObject {
                    put("sourceHash", SessionHarness.sha256(source))
                    put("tasks", 2)
                    put("correctTransformedObservations", transformed)
                    put("baselineObservationBytes", rawBytes)
                    put("presentedObservationBytes", presentedBytes)
                    put("totalNativeMillis", (System.nanoTime() - started) / 1_000_000)
                    put("compilations", metrics.count { !it.cacheHit })
                    put("workerProcesses", metrics.sumOf { it.workerProcesses })
                    put("modelCalls", 0)
                    put("providerSavingsMeasured", false)
                    put("freshRevisionAcrossSessions", true)
                    put("runtimeCleaned", true)
                    put("rawHashesVerified", true)
                    put("invalidationPreservesOriginal", true)
                    put("sourceOrigin", "Pinned prior model-generated source; not generated during this test")
                }.toString(),
            )
        }
}
