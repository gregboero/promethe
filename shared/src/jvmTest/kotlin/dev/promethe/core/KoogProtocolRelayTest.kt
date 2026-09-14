package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class KoogProtocolRelayTest {
    @Test fun `real Koog serializes none bounded Responses and preserves native tool results`() = exercise(true)

    @Test fun `same adapter without formal tools uses JSON text`() = exercise(false)

    @Test fun `repeated native page reads fail completion even with correct final values`() = exercise(true, duplicateReads = true)

    private fun exercise(
        structured: Boolean,
        duplicateReads: Boolean = false,
    ) = runBlocking {
        val directory = Files.createTempDirectory("koog-relay")
        var received = 0
        val inputs = mutableListOf<JsonObject>()
        val toolSteps = if (duplicateReads) 4 else 2
        try {
            CampaignKoogRelay(HarnessStore(directory.resolve("budget.sqlite")), "private-key", directory) { payload ->
                val request = Json.parseToJsonElement(payload).jsonObject
                inputs.add(request)
                assertEquals(if (structured) 1 else 0, (request["tools"] as? JsonArray)?.size ?: 0)
                if (structured && received > 0) assertTrue(request.getValue("input").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
                val index = received++
                if (structured && index > 0) {
                    val history = request.getValue("input").jsonArray.map { it.jsonObject }
                    val calls = history.filter { it["type"]?.jsonPrimitive?.content == "function_call" }
                    val results = history.filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                    assertEquals((0 until index).map { "call-$it" }, calls.map { it.getValue("call_id").jsonPrimitive.content })
                    assertEquals(calls.map { it["call_id"] }, results.map { it["call_id"] })
                    assertEquals((0 until index).map { it % 2 }, calls.map { Json.parseToJsonElement(it.getValue("arguments").jsonPrimitive.content).jsonObject.getValue("page").jsonPrimitive.int })
                    assertFalse(history.any { it.toString().contains("Observation:") })
                    assertEquals(listOf("message", "message") + List(index) { listOf("function_call", "function_call_output") }.flatten(), history.map { it.getValue("type").jsonPrimitive.content })
                }
                if (index == 0) {
                    System.getenv("PROMETHE_KOOG_OFFLINE_PREVIEW_DIR")?.let { destination ->
                        val preview = Files.createDirectories(Path.of(destination))
                        Files.writeString(preview.resolve("first-request-${if (structured) "structured" else "text"}.json"), payload)
                    }
                }
                val output = if (index < toolSteps && structured) {
                    """{"type":"function_call","id":"fc-$index","call_id":"call-$index","name":"json_query","arguments":"{\"page\":${index % 2}}","status":"completed"}"""
                } else {
                    val text = if (index < 2) """{"action":{"tool_name":"json_query","args":{"page":$index}}}""" else "[11,22]"
                    """{"type":"message","id":"msg-$index","role":"assistant","status":"completed","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}"""
                }
                CampaignHttpResponse(200, """{"id":"resp-$index","object":"response","created_at":1,"status":"completed","model":"gpt-5.6-terra","parallel_tool_calls":false,"text":{"format":{"type":"text"}},"output":[$output],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}""")
            }.use { relay ->
                val result = runKoogProtocolTask(directory, "fixture", structured, listOf(11, 22), relay) {
                    if (!duplicateReads) throw AssertionError("Offline Koog fixture failed", it)
                }
                assertTrue(result.getValue("correct").jsonPrimitive.boolean, result.toString())
                assertEquals(!duplicateReads, result.getValue("completed").jsonPrimitive.boolean)
                assertEquals(toolSteps + 1, received)
                assertEquals(if (structured) toolSteps else 0, result.getValue("nativeCalls").jsonPrimitive.int)
                assertEquals(0, relay.blocked)
                if (duplicateReads) {
                    assertEquals("task_duplicate_page_read", result.getValue("failure").jsonPrimitive.content)
                    assertEquals(listOf(0, 1, 0, 1), result.getValue("readPages").jsonArray.map { it.jsonPrimitive.int })
                    System.getenv("PROMETHE_KOOG_OFFLINE_PREVIEW_DIR")?.let { destination ->
                        Files.writeString(Path.of(destination).resolve("duplicate-replay-inputs.json"), JsonArray(inputs).toString())
                        Files.writeString(Path.of(destination).resolve("duplicate-replay-result.json"), result.toString())
                    }
                }
            }
        } finally {
            check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            directory.toFile().deleteRecursively()
        }
    }
}
