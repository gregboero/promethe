package dev.promethe.core

import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

class HarnessKoogMutationLiveTest {
    @Test fun `compare optional Kotlin mutation with native Koog baseline`() =
        runBlocking<Unit> {
            val mode = System.getenv("PROMETHE_HARNESS_KOOG_KOTLIN")
            assumeTrue(mode in setOf("preflight", "live"))
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            val budget = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_BUDGET_DB")))
            check(budget.isAbsolute && Files.isRegularFile(budget))
            val batch = "koog-kotlin-${UUID.randomUUID()}"
            val directory = Files.createDirectories(output.resolve(batch))
            val store = HarnessStore(budget)
            val answers = listOf(6284, 1739, 8452, 3906)
            val random = Random(307199)
            val pages = answers.map { answer ->
                val noise = List(1200) { ('a'.code + random.nextInt(26)).toChar() }.joinToString("")
                """{"noise":"$noise","answer":$answer}"""
            }
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
                    put("plannedRuns", 2)
                    put("maxPaidRequests", 36)
                    put("pagesPerTask", 4)
                    put("maxIterations", 16)
                    put("maxCallsPerRunIncludingReview", 18)
                    put("task", KOOG_MUTATION_TASK)
                    put("order", JsonArray(listOf("baseline", "free").map(::JsonPrimitive)))
                    put("mutationForced", false)
                    put("modelGivenCandidateSource", false)
                    put("postLoopSynthesisIncluded", true)
                    put("language", "KOTLIN")
                    put("nativeHistory", "execution-local-chronological-v1")
                    put("expected", JsonArray(answers.map(::JsonPrimitive)))
                }.toString(),
            )
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                val status = sandbox.selfTest()
                check(status.available && status.selfTestPassed)
                val metrics = mutableListOf<KotlinHarnessMetrics>()
                val runner = HarnessKotlinRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_DIST"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_JAVA_RUNTIME"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH"))), metrics = { metrics.add(it) })
                val preflightStarted = System.nanoTime()
                val preflight = SessionHarness(store, runner, FileArtifactStore(directory.resolve("preflight-artifacts")))
                try {
                    check(runner.execute("observation.text", HarnessObservation("json_query", "preflight"), batch) == "preflight")
                    val request = ToolExecutionRequest("json_query", buildJsonObject {}, batch)
                    val revision = Json.decodeFromString<HarnessRevision>(preflight.command(request, "propose", HarnessArguments(source = HarnessDecisionLiveTest.KOTLIN_FIXED_SOURCE, toolName = "json_query")))
                    check(preflight.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
                } finally {
                    preflight.endSession(batch)
                }
                Files.writeString(
                    directory.resolve("preflight.json"),
                    buildJsonObject {
                        put("passed", true)
                        put("modelCalls", 0)
                        put("compilations", metrics.count { !it.cacheHit })
                        put("compilationMillis", metrics.sumOf { it.compilationMillis })
                        put("evaluationMillis", metrics.sumOf { it.evaluationMillis })
                        put("workerProcesses", metrics.sumOf { it.workerProcesses })
                        put("totalMillis", (System.nanoTime() - preflightStarted) / 1_000_000)
                        put("cacheCleaned", runner.cachedEntries() == 0)
                    }.toString(),
                )
                if (mode == "preflight") return@use
                val credentials = requireNotNull(CredentialsStore.load())
                check(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra")
                val results = mutableListOf<JsonObject>()
                CampaignKoogRelay(store, credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }, output).use { relay ->
                    for (free in listOf(false, true)) {
                        metrics.clear()
                        val session = "$batch-${if (free) "free" else "baseline"}"
                        val result = runKoogMutationTask(directory, session, free, pages, answers, relay, store, runner, metrics)
                        results.add(result)
                        Files.writeString(directory.resolve("results.json"), JsonArray(results).toString())
                        check(result.getValue("completed").jsonPrimitive.boolean) { "Stop on first incomplete run; retain evidence" }
                    }
                    check(relay.blocked == 0)
                }
            }
        }
}
