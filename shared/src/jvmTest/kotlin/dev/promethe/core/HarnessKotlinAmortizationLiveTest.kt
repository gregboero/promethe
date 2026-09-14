package dev.promethe.core

import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

/** One preregistered CI-report comparison; the existing durable campaign ceiling still applies. */
class HarnessKotlinAmortizationLiveTest {
    @Test fun `compare CI diagnostic pages with baseline reused Kotlin and conditional mutation`() =
        runBlocking<Unit> {
            val mode = System.getenv("PROMETHE_HARNESS_KOTLIN_AMORTIZATION")
            assumeTrue(mode in setOf("preflight", "live"))
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            val budget = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_BUDGET_DB")))
            check(budget.isAbsolute && Files.isRegularFile(budget))
            val source = Files.readString(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_CANDIDATE"))))
            check(SessionHarness.sha256(source) == CANDIDATE_HASH)
            val batch = "kotlin-amortization-${UUID.randomUUID()}"
            val directory = Files.createDirectories(output.resolve(batch))
            val store = HarnessStore(budget)
            val answers = listOf(3, 0, 7, 1, 0, 4, 2, 0)
            val modules = listOf("api", "shared", "gateway", "evals", "desktop", "sandbox", "harness", "integration")
            val pages = answers.mapIndexed { index, answer ->
                buildJsonObject {
                    put("module", modules[index])
                    put("metric", "failedTests")
                    put("answer", answer)
                    put("diagnostics", List(70) { line -> "TRACE case_${index}_$line fixture loaded; dependency cache hit; assertion details omitted." }.joinToString("\n"))
                }.toString()
            }
            val task = "Summarize this synthetic CI run. Read json_query pages 0 through 7 exactly once each, in order: ${modules.joinToString()}. Each JSON report stores the failedTests count in its answer field. Ignore verbose diagnostic traces. Return only the JSON array of eight failed-test counts in module order. Accuracy and reading every report are required. Avoid unnecessary cost and delay. Optional harness tools, if available, may be used or ignored; mutation costs additional model calls, compilation and validation."
            Files.writeString(directory.resolve("corpus.json"), JsonArray(pages.map(::JsonPrimitive)).toString())
            Files.writeString(
                directory.resolve("protocol.json"),
                buildJsonObject {
                    put("batch", batch)
                    put("mode", mode)
                    put("model", "gpt-5.6-terra")
                    put("endpoint", "responses")
                    put("reasoningEffort", "none")
                    put("maxOutputTokens", 4096)
                    put("campaignCapMicroUsd", 5_000_000)
                    put("plannedRuns", 3)
                    put("maxPaidRequests", 36)
                    put("maxCallsPerRunIncludingReview", 18)
                    put("maxIterations", 16)
                    put("pagesPerTask", 8)
                    put("task", task)
                    put("order", JsonArray(listOf("baseline", "reused", "conditional").map(::JsonPrimitive)))
                    put("candidateHash", CANDIDATE_HASH)
                    put("candidateOrigin", "previous directed model-generated script; explicit host reuse, no autonomous promotion")
                    put("reusedSetupIncluded", true)
                    put("historicalSourceGenerationIncluded", false)
                    put("conditionalExposure", "two distinct >=1024-byte observations, >=4 remaining, optimistic removable context exceeds 7000*(remaining+4+1) bytes; sticky")
                    put("postLoopSynthesisIncluded", true)
                    put("corpus", "synthetic CI diagnostic reports, no user data")
                    put("rawPageBytes", JsonArray(pages.map { JsonPrimitive(it.encodeToByteArray().size) }))
                    put("expected", JsonArray(answers.map(::JsonPrimitive)))
                }.toString(),
            )
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                check(sandbox.selfTest().selfTestPassed)
                val metrics = mutableListOf<KotlinHarnessMetrics>()
                val runner = HarnessKotlinRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_DIST"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_JAVA_RUNTIME"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH"))), metrics = { metrics.add(it) })
                val start = System.nanoTime()
                val replay = try {
                    runner.executeBatch(source, pages.map { HarnessObservation("json_query", it) }, batch)
                } finally {
                    runner.endSession(batch)
                }
                check(replay == answers.map { it.toString() })
                check(runner.cachedEntries() == 0)
                Files.writeString(
                    directory.resolve("preflight.json"),
                    buildJsonObject {
                        put("passed", true)
                        put("modelCalls", 0)
                        put("sourceHash", CANDIDATE_HASH)
                        put("correctPages", replay.size)
                        put("rawBytes", pages.sumOf { it.encodeToByteArray().size })
                        put("transformedBytesWithoutProvenance", replay.sumOf { it.encodeToByteArray().size })
                        put("compilations", metrics.count { !it.cacheHit })
                        put("workerProcesses", metrics.sumOf { it.workerProcesses })
                        put("compilationMillis", metrics.sumOf { it.compilationMillis })
                        put("evaluationMillis", metrics.sumOf { it.evaluationMillis })
                        put("totalMillis", (System.nanoTime() - start) / 1_000_000)
                        put("cacheCleaned", true)
                    }.toString(),
                )
                if (mode == "preflight") return@use
                val credentials = requireNotNull(CredentialsStore.load())
                check(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra")
                val results = mutableListOf<JsonObject>()
                CampaignKoogRelay(store, credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }, output).use { relay ->
                    for (arm in listOf("baseline", "reused", "conditional")) {
                        metrics.clear()
                        val result = runKoogMutationTask(directory, "$batch-$arm", arm == "conditional", pages, answers, relay, store, runner, metrics, taskText = task, conditionalExposure = arm == "conditional", installedSource = source.takeIf { arm == "reused" })
                        results.add(result)
                        Files.writeString(directory.resolve("results.json"), JsonArray(results).toString())
                        check(result.getValue("completed").jsonPrimitive.boolean && result.getValue("correct").jsonPrimitive.boolean) { "Stop on first incomplete or incorrect run; retain evidence" }
                        if (arm == "reused") check(result.getValue("processed").jsonPrimitive.int == 8 && result.getValue("activations").jsonPrimitive.int == 1)
                    }
                    check(relay.blocked == 0)
                }
            }
        }

    companion object {
        const val CANDIDATE_HASH = "09211ce22a956ae92b8bdca61617999ee4ad13674144cf899a584717e7806e8e"
    }
}
