package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class KoogMutationLoopTest {
    @Test fun `baseline native Koog reads preserve the raw ledger`() = exercise(false)

    @Test fun `native Koog proposes validates activates and applies Kotlin processor`() = exercise(true)

    @Test fun `directed control requires a real activation and transformed pages`() = exercise(true, directed = true, pageCount = 2)

    @Test fun `directed control rejects correct answers without mutation`() = exercise(true, directed = true, skipMutation = true, pageCount = 2)

    @Test fun `eight page baseline retains complete native history`() = exercise(false, pageCount = 8)

    @Test fun `explicit reused processor transforms all eight pages without exposing mutation tools`() = exercise(false, pageCount = 8, installed = true)

    @Test fun `conditional native Koog exposes after two large reads and applies later pages`() = exercise(true, pageCount = 8, conditional = true, noiseBytes = 8000)

    @Test fun `conditional native Koog keeps unprofitable tools out of schema and prompt`() = exercise(true, pageCount = 8, conditional = true)

    private fun exercise(
        free: Boolean,
        directed: Boolean = false,
        skipMutation: Boolean = false,
        pageCount: Int = 4,
        conditional: Boolean = false,
        noiseBytes: Int = 1200,
        installed: Boolean = false,
    ) = runBlocking<Unit> {
        val directory = Files.createTempDirectory("koog-mutation")
        val store = HarnessStore(directory.resolve("budget.sqlite"))
        val requests = mutableListOf<JsonObject>()
        val runner = object : HarnessRunner {
            override val language = HarnessLanguage.KOTLIN

            override suspend fun execute(
                source: String,
                observation: HarnessObservation,
                sessionId: String,
            ): String {
                assertEquals(HarnessDecisionLiveTest.KOTLIN_FIXED_SOURCE, source)
                val text = observation.text
                return when {
                    text.startsWith("{") -> Json.parseToJsonElement(text).jsonObject["answer"]?.jsonPrimitive?.content ?: text
                    text.startsWith("id,answer\n") -> text.substringAfterLast(',')
                    text.startsWith("DATA answer=") -> text.substringAfter('=')
                    else -> text
                }
            }
        }
        var call = 0
        val mutate = free && !skipMutation && (!conditional || noiseBytes >= 8000)
        val mutationOffset = if (conditional) 2 else 0
        val prefix = if (mutate) 4 else 0
        val answers = List(pageCount) { (it + 1) * 11 }
        val pages = answers.map { """{"answer":$it,"noise":"${"x".repeat(noiseBytes)}"}""" }
        try {
            CampaignKoogRelay(store, "fake-key", directory) { payload ->
                val request = Json.parseToJsonElement(payload).jsonObject
                requests.add(request)
                val index = call++
                if (index <= prefix + pageCount) {
                    val exposed = free && (!conditional || (mutate && index >= 2))
                    assertEquals(if (exposed) 7 else 1, request.getValue("tools").jsonArray.size)
                    assertEquals(exposed, request.getValue("input").jsonArray.first().toString().contains("harness_inspect"))
                    val history = request.getValue("input").jsonArray.map { it.jsonObject }
                    assertEquals(index, history.count { it["type"]?.jsonPrimitive?.content == "function_call_output" })
                }

                fun revision() =
                    store.transaction { db ->
                        db.createStatement().use { s ->
                            s.executeQuery("SELECT id FROM harness_revisions").use { rows ->
                                check(rows.next())
                                rows.getString(1)
                            }
                        }
                    }
                val tool = when {
                    mutate && index == mutationOffset -> "harness_inspect" to "{}"

                    mutate && index == mutationOffset + 1 -> "harness_propose" to buildJsonObject {
                        put("source", HarnessDecisionLiveTest.KOTLIN_FIXED_SOURCE)
                        put("toolName", "json_query")
                    }.toString()

                    mutate && index in (mutationOffset + 2)..(mutationOffset + 3) -> (if (index == mutationOffset + 2) "harness_evaluate" else "harness_activate") to buildJsonObject { put("revision", revision()) }.toString()

                    index < prefix + pageCount -> "json_query" to """{"page":${if (index < mutationOffset) index else index - prefix}}"""

                    else -> null
                }
                val output = if (tool != null) {
                    """{"type":"function_call","id":"fc-$index","call_id":"call-$index","name":"${tool.first}","arguments":${JsonPrimitive(tool.second)},"status":"completed"}"""
                } else {
                    val text = if (index == prefix + pageCount) JsonArray(answers.map(::JsonPrimitive)).toString() else "No reusable skill."
                    """{"type":"message","id":"msg-$index","role":"assistant","status":"completed","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}"""
                }
                CampaignHttpResponse(200, """{"id":"resp-$index","object":"response","created_at":1,"status":"completed","model":"gpt-5.6-terra","parallel_tool_calls":false,"text":{"format":{"type":"text"}},"output":[$output],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}""")
            }.use { relay ->
                val result = runKoogMutationTask(
                    directory,
                    "koog-fixture",
                    free,
                    pages,
                    answers,
                    relay,
                    store,
                    runner,
                    emptyList(),
                    taskText = if (directed) {
                        KOOG_DIRECTED_TASK
                    } else if (pageCount == 8) {
                        KOOG_LONG_MUTATION_TASK
                    } else {
                        KOOG_MUTATION_TASK
                    },
                    requireMutation = directed,
                    conditionalExposure = conditional,
                    installedSource = HarnessDecisionLiveTest.KOTLIN_FIXED_SOURCE.takeIf { installed },
                ) {
                    if (!skipMutation) throw AssertionError("Offline mutation fixture failed", it)
                }
                assertEquals(!skipMutation, result.getValue("completed").jsonPrimitive.boolean, result.toString())
                if (skipMutation) assertEquals("task_mutation_missing", result.getValue("failure").jsonPrimitive.content)
                assertTrue(result.getValue("correct").jsonPrimitive.boolean)
                assertTrue(result.getValue("rawLedgerPreserved").jsonPrimitive.boolean)
                assertTrue(result.getValue("sessionCleaned").jsonPrimitive.boolean)
                assertTrue(result.getValue("registryCleaned").jsonPrimitive.boolean)
                assertEquals(if (mutate || installed) 1 else 0, result.getValue("activations").jsonPrimitive.int)
                assertEquals(
                    if (installed) {
                        pageCount
                    } else if (mutate) {
                        pageCount - mutationOffset
                    } else {
                        0
                    },
                    result.getValue("processed").jsonPrimitive.int,
                )
                if (conditional) assertEquals(if (mutate) JsonPrimitive(2) else JsonNull, result["exposedAfterReads"])
                assertEquals(prefix + pageCount + (if (skipMutation) 1 else 2), call)
                assertEquals(0, relay.blocked)
                if (mutate) assertTrue(requests.last().toString().contains("harness revision="))
                System.getenv("PROMETHE_KOOG_MUTATION_PREVIEW_DIR")?.let { destination ->
                    val preview = Files.createDirectories(Path.of(destination))
                    val label = if (installed) {
                        "reused-$pageCount"
                    } else if (conditional) {
                        "conditional-$noiseBytes"
                    } else if (directed) {
                        "directed-${if (skipMutation) "missing" else "complete"}"
                    } else {
                        "${if (free) "free" else "baseline"}-$pageCount"
                    }
                    Files.writeString(preview.resolve("$label-requests.json"), JsonArray(requests).toString())
                    Files.writeString(preview.resolve("$label-result.json"), result.toString())
                }
            }
        } finally {
            ToolRegistry.clear()
            check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            directory.toFile().deleteRecursively()
        }
    }
}
