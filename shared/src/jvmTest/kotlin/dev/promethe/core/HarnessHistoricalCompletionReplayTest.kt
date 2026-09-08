package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class HarnessHistoricalCompletionReplayTest {
    @Test fun `reclassify archived final responses without changing evidence or invoking a model`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val source = root.resolve("docs/reports/harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0")
        val results = Json.parseToJsonElement(Files.readString(source.resolve("results.json"))).jsonArray
        val replay = results.map { element ->
            val row = element.jsonObject
            val session = row.getValue("session").jsonPrimitive.content
            val transcript = Json.parseToJsonElement(Files.readString(source.resolve("$session-transcript.json"))).jsonArray.map { it.jsonObject }
            val pages = transcript.zipWithNext().mapNotNull { (message, next) ->
                if (message["role"]?.jsonPrimitive?.content != "assistant" || next["role"]?.jsonPrimitive?.content != "system") return@mapNotNull null
                val observation = next.getValue("content").jsonPrimitive.content
                if (!observation.startsWith("Observation: ") || observation.contains("[ERROR]") || observation.contains("[IDEMPOTENT]")) return@mapNotNull null
                val action = AgentActionJson.extract(message.getValue("content").jsonPrimitive.content)?.let { Json.parseToJsonElement(it).jsonObject["action"] as? JsonObject } ?: return@mapNotNull null
                if (action["tool_name"]?.jsonPrimitive?.content != "json_query") return@mapNotNull null
                action.getValue("args").jsonObject.getValue("page").jsonPrimitive.int
            }
            assertEquals(row.getValue("pagesRead").jsonPrimitive.int, pages.size)
            assertEquals(row.getValue("distinctPagesRead").jsonPrimitive.int, pages.toSet().size)
            val completion = HarnessTaskCompletion.assess(row.getValue("final").jsonPrimitive.content, pages, 8)
            buildJsonObject {
                put("session", session)
                put("historicalCompleted", row.getValue("completed"))
                put("historicalCorrect", row.getValue("correct"))
                put("readPages", JsonArray(pages.map(::JsonPrimitive)))
                put("completion", Json.encodeToJsonElement(completion))
                put("finishReason", JsonNull)
                put("finishReasonAvailability", "not-recorded-in-historical-receipts")
            }
        }
        val reasons = replay.groupingBy { it.getValue("completion").jsonObject.getValue("reason").jsonPrimitive.content }.eachCount()
        assertEquals(mapOf("complete" to 14, "empty_final_response" to 3, "missing_page_reads" to 1), reasons)
        val output = root.resolve("build/reports/harness-response-diagnostics")
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("historical-replay.json"),
            buildJsonObject {
                put("source", root.relativize(source).toString().replace('\\', '/'))
                put("runs", JsonArray(replay))
                put("counts", buildJsonObject { reasons.forEach { (reason, count) -> put(reason, count) } })
                put("modelCalls", 0)
            }.toString(),
        )
    }
}
