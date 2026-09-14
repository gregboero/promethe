package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

class HarnessKoogProtocolLiveTest {
    @Test fun `compare formal Koog tools with JSON text on paired two page tasks`() =
        runBlocking {
            assumeTrue(System.getenv("PROMETHE_HARNESS_KOOG_PROTOCOL") == "true")
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            val budget = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_BUDGET_DB")))
            check(budget.isAbsolute && Files.isRegularFile(budget))
            val batch = "koog-protocol-${UUID.randomUUID()}"
            val directory = Files.createDirectories(output.resolve(batch))
            val tasks = listOf(listOf(3471, 8063), listOf(5192, 2847), listOf(6904, 1358))
            Files.writeString(
                directory.resolve("protocol.json"),
                buildJsonObject {
                    put("batch", batch)
                    put("model", "gpt-5.6-terra")
                    put("endpoint", "responses")
                    put("reasoningEffort", "none")
                    put("maxOutputTokens", 4096)
                    put("plannedRuns", 6)
                    put("maxPaidRequests", 36)
                    put("pagesPerTask", 2)
                    put("maxIterations", 6)
                    put("campaignCapMicroUsd", 5_000_000)
                    put("formalToolsOnlyDifference", true)
                    put("nativeHistory", "execution-local-chronological-v1")
                    put("fixedPromptDate", "2026-09-07 (fixed experiment date)")
                    put("nativeToolChoiceForced", false)
                    put("fallbackEnabled", false)
                    put("tasks", JsonArray(tasks.map { JsonArray(it.map(::JsonPrimitive)) }))
                    put("order", JsonArray(listOf("text", "structured", "structured", "text", "text", "structured").map(::JsonPrimitive)))
                    put("postLoopSkillSynthesisEligible", false)
                }.toString(),
            )
            val credentials = requireNotNull(CredentialsStore.load())
            check(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra")
            val results = mutableListOf<JsonObject>()
            CampaignKoogRelay(HarnessStore(budget), credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }, output).use { relay ->
                for ((index, answers) in tasks.withIndex()) {
                    for (structured in if (index % 2 == 0) listOf(false, true) else listOf(true, false)) {
                        val session = "$batch-${index + 1}-${if (structured) "structured" else "text"}"
                        val result = runKoogProtocolTask(directory, session, structured, answers, relay)
                        results.add(result)
                        Files.writeString(directory.resolve("results.json"), JsonArray(results).toString())
                        check(result["completed"]?.jsonPrimitive?.boolean == true) { "Protocol comparison stopped on incomplete run; preserve evidence" }
                    }
                }
                check(relay.blocked == 0)
            }
            check(results.size == 6)
        }
}
