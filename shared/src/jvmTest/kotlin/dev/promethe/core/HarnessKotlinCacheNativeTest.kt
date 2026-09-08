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

/** Explicit native experiment. No model calls or bytecode loaded in the test JVM. */
class HarnessKotlinCacheNativeTest {
    private fun env(name: String) = Path.of(requireNotNull(System.getenv(name)))

    private fun runner(
        sandbox: NativeSandboxManager,
        cache: Boolean = true,
        metrics: (KotlinHarnessMetrics) -> Unit = {},
    ) = HarnessKotlinRunner(sandbox, env("PROMETHE_HARNESS_KOTLIN_DIST"), env("PROMETHE_HARNESS_JAVA_RUNTIME"), env("PROMETHE_HARNESS_SCRATCH"), metrics = metrics, cacheEnabled = cache)

    @Test fun `compare three modes including cold compilation and repeated evaluations`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PROMETHE_HARNESS_KOTLIN_NATIVE") == "true")
            val output = Files.createDirectories(env("PROMETHE_HARNESS_KOTLIN_REPORT"))
            val rows = mutableListOf<JsonObject>()
            NativeSandboxManager(env("PROMETHE_HARNESS_HELPER"), JvmSandboxHelperProcessFactory).use { sandbox ->
                check(sandbox.selfTest().selfTestPassed)
                var metric: KotlinHarnessMetrics? = null
                val cached = runner(sandbox, metrics = { metric = it })
                val uncached = runner(sandbox, false, metrics = { metric = it })
                val node = HarnessNodeRunner(sandbox, env("PROMETHE_HARNESS_NODE"), env("PROMETHE_HARNESS_SCRATCH"))
                val js = "const t=observation.text; let v; try {v=JSON.parse(t);} catch {} if(v && Object.hasOwn(v,'answer')) return String(v.answer); if(t.startsWith('id,answer\\n')) return t.split('\\n')[1].split(',')[1]; if(t.startsWith('DATA answer=')) return t.slice(12); return t;"
                val modes = listOf(Triple("javascript", node, js), Triple("kotlin_uncached", uncached, HarnessKotlinNativeTest.SOURCE), Triple("kotlin_cached", cached, HarnessKotlinNativeTest.SOURCE))
                repeat(3) { repetition ->
                    val order = modes.drop(repetition) + modes.take(repetition)
                    for ((mode, runner, source) in order) {
                        val session = "$mode-$repetition-${UUID.randomUUID()}"
                        repeat(3) { step ->
                            val observations = listOf("{\"answer\":${42 + step},\"noise\":\"irrelevant\"}", "{\"noise\":\"answer=wrong\",\"answer\":\"été\"}", "id,answer\n1,17", "DATA answer=29", "unstructured text").map { HarnessObservation("json_query", it) }
                            val start = System.nanoTime()
                            val values = try {
                                runner.executeBatch(source, observations, session)
                            } catch (error: HarnessExecutionException) {
                                Files.writeString(
                                    output.resolve("cache-benchmark-failure.json"),
                                    buildJsonObject {
                                        put("mode", mode)
                                        put("repetition", repetition)
                                        put("step", step)
                                        put("message", error.message)
                                        put("stdout", error.stdoutPreview)
                                        put("stderr", error.stderrPreview)
                                    }.toString(),
                                )
                                throw error
                            }
                            val wall = (System.nanoTime() - start) / 1_000_000
                            assertEquals(listOf("${42 + step}", "été", "17", "29", "unstructured text"), values)
                            rows.add(
                                buildJsonObject {
                                    put("mode", mode)
                                    put("repetition", repetition)
                                    put("step", step)
                                    put("wallMillis", wall)
                                    put("correct", true)
                                    put("observations", 5)
                                    if (mode != "javascript") {
                                        requireNotNull(metric).let {
                                            assertEquals(mode == "kotlin_cached" && step > 0, it.cacheHit)
                                            put("cacheHit", it.cacheHit)
                                            put("compilationMillis", it.compilationMillis)
                                            put("evaluationMillis", it.evaluationMillis)
                                            put("stagingMillis", it.stagingMillis)
                                            put("workerProcesses", it.workerProcesses)
                                            put("artifactBytes", it.artifactBytes)
                                        }
                                    }
                                },
                            )
                            Files.writeString(output.resolve("cache-benchmark.json"), JsonArray(rows).toString())
                        }
                        runner.endSession(session)
                    }
                }
                assertEquals(0, cached.cachedEntries())
            }
            Files.writeString(output.resolve("cache-benchmark.json"), JsonArray(rows).toString())
        }

    @Test fun `activation rollback disable timeout and session cleanup preserve cache boundaries`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PROMETHE_HARNESS_KOTLIN_NATIVE") == "true")
            val output = Files.createDirectories(env("PROMETHE_HARNESS_KOTLIN_REPORT"))
            NativeSandboxManager(env("PROMETHE_HARNESS_HELPER"), JvmSandboxHelperProcessFactory).use { sandbox ->
                check(sandbox.selfTest().selfTestPassed)
                val observations = mutableListOf<KotlinHarnessMetrics>()
                val runner = runner(sandbox, metrics = { observations.add(it) })
                val harness = SessionHarness(HarnessStore(output.resolve("cache-lifecycle.sqlite")), runner, FileArtifactStore(output.resolve("artifacts")))
                val session = "cache-lifecycle-${UUID.randomUUID()}"
                val request = ToolExecutionRequest("json_query", buildJsonObject {}, session, runId = "run", stepId = "first")

                suspend fun propose(
                    source: String,
                    base: String? = null,
                ): String {
                    val revision = Json.decodeFromString<HarnessRevision>(harness.command(request, "propose", HarnessArguments(source = source, baseRevision = base, toolName = "json_query")))
                    assertTrue(harness.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
                    assertTrue(harness.command(request, "activate", HarnessArguments(revision = revision.id)).startsWith("Pending"))
                    return revision.id
                }
                try {
                    harness.beginStep(session, "run", "first")
                    val first = propose(HarnessKotlinNativeTest.SOURCE)
                    assertFalse(observations.last().cacheHit)
                    harness.beginStep(session, "run", "second")
                    assertTrue(observations.last().cacheHit)
                    val raw = "{\"answer\":73,\"noise\":\"${"x".repeat(500)}\"}"
                    assertTrue(harness.process(request, raw).text.startsWith("73\n"))
                    assertTrue(observations.last().cacheHit)
                    val second = propose(HarnessKotlinNativeTest.SOURCE + "\n", first)
                    assertFalse(observations.last().cacheHit)
                    harness.beginStep(session, "run", "third")
                    assertEquals(second, harness.revisionKey(session))
                    harness.command(request, "rollback", HarnessArguments())
                    harness.beginStep(session, "run", "fourth")
                    assertEquals(first, harness.revisionKey(session))
                    assertTrue(observations.last().cacheHit)
                    harness.command(request, "disable", HarnessArguments())
                    harness.beginStep(session, "run", "fifth")
                    assertEquals(0, runner.cachedEntries())
                    val conditional = "if (observation.text == \"hang\") { while (true) {} }; observation.text"
                    assertEquals("ok", runner.execute(conditional, HarnessObservation("json_query", "ok"), session))
                    assertEquals(1, runner.cachedEntries())
                    val failure = runCatching { runner.execute(conditional, HarnessObservation("json_query", "hang"), session) }.exceptionOrNull()
                    assertTrue((failure as? HarnessExecutionException)?.timedOut == true)
                    assertEquals(0, runner.cachedEntries())
                    runner.execute("observation.text", HarnessObservation("json_query", "fresh"), session)
                    harness.endSession(session)
                    assertEquals(0, runner.cachedEntries())
                    Files.writeString(
                        output.resolve("cache-lifecycle.json"),
                        buildJsonObject {
                            put("activationHit", true)
                            put("sourceChangeMiss", true)
                            put("rollbackHit", true)
                            put("disablePurged", true)
                            put("cachedTimeoutEvicted", true)
                            put("sessionCleaned", true)
                        }.toString(),
                    )
                } finally {
                    harness.endSession(session)
                }
            }
        }
}
