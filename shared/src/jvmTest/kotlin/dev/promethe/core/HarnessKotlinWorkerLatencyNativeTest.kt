package dev.promethe.core

import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

/** Local paired replay only: no credentials, provider transport or campaign budget writes. */
class HarnessKotlinWorkerLatencyNativeTest {
    private fun env(name: String) = Path.of(requireNotNull(System.getenv(name)))

    @Test fun `compare disposable workers on the same CI observations with alternating order`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PROMETHE_HARNESS_WORKER_LATENCY") == "true")
            val output = Files.createDirectories(env("PROMETHE_HARNESS_KOTLIN_REPORT"))
            check(!Files.exists(output.resolve("results.json"))) { "Choose a fresh output directory; never overwrite a measured cohort" }
            val corpus = Json.parseToJsonElement(Files.readString(env("PROMETHE_HARNESS_CORPUS"))).jsonArray.map { it.jsonPrimitive.content }
            val expected = listOf("3", "0", "7", "1", "0", "4", "2", "0")
            assertEquals(expected, corpus.map { Json.parseToJsonElement(it).jsonObject.getValue("answer").jsonPrimitive.content })
            val source = Files.readString(env("PROMETHE_HARNESS_CANDIDATE"))
            assertEquals(HarnessKotlinAmortizationLiveTest.CANDIDATE_HASH, SessionHarness.sha256(source))
            val rows = mutableListOf<JsonObject>()
            Files.writeString(
                output.resolve("protocol.json"),
                buildJsonObject {
                    put("modelCalls", 0)
                    put("repetitions", 3)
                    put("pagesPerRun", 8)
                    put("setupIncluded", true)
                    put("sourceHash", SessionHarness.sha256(source))
                    put("corpusHash", SessionHarness.sha256(Files.readString(env("PROMETHE_HARNESS_CORPUS"))))
                    put("order", "baseline/optimized, optimized/baseline, baseline/optimized")
                    put("baseline", "preserved worker distribution, compiler host during evaluation, default JVM tiering")
                    put("optimized", "direct evaluator, TieredStopAtLevel=1 on evaluation only")
                    put("isolation", "fresh process per invocation, read-only, network off, unchanged limits")
                }.toString(),
            )
            NativeSandboxManager(env("PROMETHE_HARNESS_HELPER"), JvmSandboxHelperProcessFactory).use { sandbox ->
                check(sandbox.selfTest().selfTestPassed)
                repeat(3) { repetition ->
                    for (optimized in if (repetition % 2 == 0) listOf(false, true) else listOf(true, false)) {
                        val metrics = mutableListOf<KotlinHarnessMetrics>()
                        val runner = HarnessKotlinRunner(sandbox, env(if (optimized) "PROMETHE_HARNESS_KOTLIN_DIST" else "PROMETHE_HARNESS_BASELINE_DIST"), env("PROMETHE_HARNESS_JAVA_RUNTIME"), env("PROMETHE_HARNESS_SCRATCH"), metrics = { metrics.add(it) }, optimizeEvaluationStartup = optimized, reusePreparedRuntime = false)
                        val mode = if (optimized) "optimized" else "baseline"
                        val session = "worker-$mode-$repetition-${UUID.randomUUID()}"
                        val store = HarnessStore(output.resolve("replay.sqlite"))
                        val harness = SessionHarness(store, runner, FileArtifactStore(output.resolve("artifacts")))
                        val request = ToolExecutionRequest("json_query", buildJsonObject {}, session, runId = session)
                        val started = System.nanoTime()
                        val pageMillis = mutableListOf<Long>()
                        var failure: String? = null
                        var setupMillis = 0L
                        try {
                            val revision = Json.decodeFromString<HarnessRevision>(harness.command(request, "propose", HarnessArguments(source = source, toolName = "json_query")))
                            check(harness.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
                            check(harness.command(request, "activate", HarnessArguments(revision = revision.id)).startsWith("Pending"))
                            harness.beginStep(session, session, "page-0")
                            setupMillis = (System.nanoTime() - started) / 1_000_000
                            for ((index, raw) in corpus.withIndex()) {
                                val start = System.nanoTime()
                                harness.beginStep(session, session, "page-$index")
                                val processed = harness.process(request, raw)
                                assertEquals(expected[index], processed.text.substringBefore('\n'))
                                assertEquals(revision.id, processed.revision)
                                pageMillis.add((System.nanoTime() - start) / 1_000_000)
                            }
                        } catch (error: Throwable) {
                            failure = error.javaClass.simpleName
                            throw error
                        } finally {
                            harness.endSession(session)
                            rows.add(
                                buildJsonObject {
                                    put("mode", mode)
                                    put("repetition", repetition)
                                    put("wallMillis", (System.nanoTime() - started) / 1_000_000)
                                    put("setupMillis", setupMillis)
                                    put("pageMillis", JsonArray(pageMillis.map(::JsonPrimitive)))
                                    put("correctPages", pageMillis.size)
                                    put("failure", failure)
                                    put("cacheCleaned", runner.cachedEntries() == 0)
                                    put("sessionCleaned", harness.revisionKey(session) == "original")
                                    put("compilations", metrics.count { !it.cacheHit })
                                    put("cacheHits", metrics.count { it.cacheHit })
                                    put("workerProcesses", metrics.sumOf { it.workerProcesses })
                                    put("compilationMillis", metrics.sumOf { it.compilationMillis })
                                    put("evaluationMillis", metrics.sumOf { it.evaluationMillis })
                                    put("stagingMillis", metrics.sumOf { it.stagingMillis })
                                    put("processMillis", metrics.sumOf { it.processMillis })
                                    put("rawArtifactsPreserved", koogMutationEvents(store, session).filter { it.startsWith("processed:") }.map { it.removePrefix("processed:") } == corpus.map(SessionHarness::sha256))
                                },
                            )
                            Files.writeString(output.resolve("results.json"), JsonArray(rows).toString())
                        }
                        assertEquals(1, metrics.count { !it.cacheHit })
                        assertEquals(9, metrics.count { it.cacheHit })
                        assertEquals(11, metrics.sumOf { it.workerProcesses })
                    }
                }
                val metrics = mutableListOf<KotlinHarnessMetrics>()
                val runner = HarnessKotlinRunner(sandbox, env("PROMETHE_HARNESS_KOTLIN_DIST"), env("PROMETHE_HARNESS_JAVA_RUNTIME"), env("PROMETHE_HARNESS_SCRATCH"), metrics = { metrics.add(it) }, reusePreparedRuntime = false)
                val session = "worker-isolation-${UUID.randomUUID()}"
                try {
                    val isolated = "val prior = System.getProperty(\"promethe.worker.probe\") ?: \"fresh\"; System.setProperty(\"promethe.worker.probe\", \"changed\"); prior"
                    repeat(2) { assertEquals("fresh", runner.execute(isolated, HarnessObservation("json_query", ""), session)) }
                    assertTrue(metrics.last().cacheHit)
                    val looping = "if (observation.text == \"hang\") { while (true) {} }; observation.text"
                    assertEquals("ok", runner.execute(looping, HarnessObservation("json_query", "ok"), session))
                    val failure = runCatching { runner.execute(looping, HarnessObservation("json_query", "hang"), session) }.exceptionOrNull()
                    assertTrue((failure as? HarnessExecutionException)?.timedOut == true)
                    val beforeRetry = metrics.size
                    assertEquals("ok", runner.execute(looping, HarnessObservation("json_query", "ok"), session))
                    assertEquals(beforeRetry + 1, metrics.size)
                    assertFalse(metrics.last().cacheHit)
                } finally {
                    runner.endSession(session)
                }
                assertEquals(0, runner.cachedEntries())
                Files.writeString(
                    output.resolve("isolation.json"),
                    buildJsonObject {
                        put("freshProcessState", true)
                        put("cachedTimeoutEnforced", true)
                        put("failedArtifactRecompiled", true)
                        put("sessionCacheCleaned", true)
                    }.toString(),
                )
            }
        }
}
