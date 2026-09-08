package dev.promethe.core

import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

/** No LLM calls. Every .kts evaluation, including diagnostics, uses the native sandbox. */
class HarnessKotlinNativeTest {
    @Test fun `compile and evaluate Kotlin scripts in the native sandbox`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PROMETHE_HARNESS_KOTLIN_NATIVE") == "true")
            val output = Files.createDirectories(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_REPORT"))))
            val metrics = mutableListOf<KotlinHarnessMetrics>()
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                val status = sandbox.selfTest()
                check(status.available && status.selfTestPassed)
                val runner = HarnessKotlinRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_DIST"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_JAVA_RUNTIME"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH"))), metrics = { metrics.add(it) })
                try {
                    assertEquals("native Kotlin", runner.execute("observation.text", HarnessObservation("json_query", "native Kotlin"), "kotlin-native"))
                    val observations = listOf("{\"answer\":42}", "{\"answer\":\"été\"}", "id,answer\n1,17", "DATA answer=29", "unstructured text")
                    val results = runner.executeBatch(SOURCE, observations.map { HarnessObservation("json_query", it) }, "kotlin-batch")
                    assertEquals(listOf("42", "été", "17", "29", "unstructured text"), results)
                    val failureResults = mutableMapOf<String, Boolean>()
                    for ((name, source) in mapOf("syntaxRejected" to "val = broken", "nonStringRejected" to "123", "timeoutEnforced" to "while (true) {}\n\"never\"")) {
                        val failure = runCatching { runner.execute(source, HarnessObservation("json_query", ""), "kotlin-$name") }.exceptionOrNull()
                        failureResults[name] = if (name == "timeoutEnforced") (failure as? HarnessExecutionException)?.timedOut == true else failure is HarnessExecutionException
                        check(failureResults.getValue(name)) { "Failed control: $name" }
                    }
                    val canary = Files.writeString(output.resolve("outside-workspace-canary.txt"), "synthetic private value")
                    try {
                        val path = Json.encodeToString(canary.toAbsolutePath().toString())
                        val denied = runner.execute("try { java.nio.file.Files.readString(java.nio.file.Path.of($path)); \"LEAK\" } catch (e: java.nio.file.AccessDeniedException) { \"DENIED\" }", HarnessObservation("json_query", ""), "kotlin-read-denial")
                        check(denied == "DENIED")
                        failureResults["outsideWorkspaceReadDenied"] = true
                    } finally {
                        Files.deleteIfExists(canary)
                    }
                    java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
                        val blocked = runner.execute("try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(\"127.0.0.1\", ${listener.localPort}), 500); \"CONNECTED\" } } catch (e: java.io.IOException) { \"DENIED\" }", HarnessObservation("json_query", ""), "kotlin-network")
                        check(blocked == "DENIED")
                        failureResults["kotlinTcpDenied"] = true
                    }
                    val writeDenied = runner.execute("try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"attempt.txt\"), \"test\"); \"WRITTEN\" } catch (e: java.nio.file.AccessDeniedException) { \"DENIED\" }", HarnessObservation("json_query", ""), "kotlin-write")
                    check(writeDenied == "DENIED")
                    failureResults["workspaceWriteDenied"] = true
                    Files.writeString(
                        output.resolve("native-results.json"),
                        buildJsonObject {
                            put("language", "KOTLIN")
                            put("compiler", "2.4.10")
                            put("identityPassed", true)
                            put("batchPassed", true)
                            put("nativeNetworkSelfTest", status.selfTestPassed)
                            failureResults.forEach { (name, passed) -> put(name, passed) }
                            put(
                                "metrics",
                                JsonArray(
                                    metrics.map {
                                        buildJsonObject {
                                            put("stagingMillis", it.stagingMillis)
                                            put("processMillis", it.processMillis)
                                            put("compilationMillis", it.compilationMillis)
                                            put("evaluationMillis", it.evaluationMillis)
                                            put("observations", it.observations)
                                        }
                                    },
                                ),
                            )
                        }.toString(),
                    )
                } catch (error: HarnessExecutionException) {
                    Files.writeString(
                        output.resolve("launch-error.json"),
                        buildJsonObject {
                            put("message", error.message)
                            put("stderr", error.stderrPreview)
                            put("stdout", error.stdoutPreview)
                        }.toString(),
                    )
                    throw error
                }
            }
        }

    @Test fun `compare batched Kotlin and JavaScript with identical observations`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PROMETHE_HARNESS_KOTLIN_NATIVE") == "true")
            val output = Files.createDirectories(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_REPORT"))))
            val rows = mutableListOf<JsonObject>()
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                check(sandbox.selfTest().selfTestPassed)
                val scratch = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH")))
                var metric: KotlinHarnessMetrics? = null
                val kotlin = HarnessKotlinRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_DIST"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_JAVA_RUNTIME"))), scratch, metrics = { metric = it })
                val node = HarnessNodeRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_NODE"))), scratch)
                val observations = listOf("{\"answer\":42,\"noise\":\"irrelevant\"}", "{\"noise\":\"answer=wrong\",\"answer\":\"été\"}", "id,answer\n1,17", "DATA answer=29", "unstructured text").map { HarnessObservation("json_query", it) }
                val js = "const t=observation.text; let v; try {v=JSON.parse(t);} catch {} if(v && Object.hasOwn(v,'answer')) return String(v.answer); if(t.startsWith('id,answer\\n')) return t.split('\\n')[1].split(',')[1]; if(t.startsWith('DATA answer=')) return t.slice(12); return t;"
                repeat(3) { repetition ->
                    val order = if (repetition % 2 == 0) listOf(node to js, kotlin to SOURCE) else listOf(kotlin to SOURCE, node to js)
                    for ((runner, source) in order) {
                        val start = System.nanoTime()
                        val values = runner.executeBatch(source, observations, "language-benchmark-$repetition")
                        val wall = (System.nanoTime() - start) / 1_000_000
                        assertEquals(listOf("42", "été", "17", "29", "unstructured text"), values)
                        rows.add(
                            buildJsonObject {
                                put("language", runner.language.name)
                                put("repetition", repetition)
                                put("wallMillis", wall)
                                put("correct", true)
                                put("observations", values.size)
                                if (runner.language == HarnessLanguage.KOTLIN) {
                                    requireNotNull(metric).let {
                                        put("stagingMillis", it.stagingMillis)
                                        put("processMillis", it.processMillis)
                                        put("compilationMillis", it.compilationMillis)
                                        put("evaluationMillis", it.evaluationMillis)
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Files.writeString(output.resolve("language-benchmark.json"), JsonArray(rows).toString())
        }

    companion object {
        const val SOURCE = """val text = observation.text
val value = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
when {
    value?.containsKey("answer") == true -> value.getValue("answer").jsonPrimitive.content
    text.startsWith("id,answer\n") -> text.lineSequence().drop(1).first().substringAfter(',')
    text.startsWith("DATA answer=") -> text.substringAfter("DATA answer=")
    else -> text
}"""
    }
}
