package dev.promethe.harness.kotlin

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Small, trusted API exposed to scripts. It contains no application/service references. */
data class Observation(
    val toolName: String,
    val text: String,
)

/** This executable must be launched by HarnessKotlinRunner inside the native sandbox. */
fun main(args: Array<String>) =
    runBlocking {
        require(args.size == 1)
        val inputPath = Path.of(args[0])
        require(Files.size(inputPath) <= 768 * 1024)
        val input = Json.parseToJsonElement(Files.readString(inputPath)).jsonObject
        val approved = listOf(Observation::class.java, Unit::class.java, kotlin.script.templates.standard.ScriptTemplateWithArgs::class.java, Json::class.java, kotlinx.serialization.KSerializer::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct()
        val config = ScriptCompilationConfiguration {
            providedProperties("observation" to Observation::class)
            defaultImports("kotlinx.serialization.json.*")
            jvm { updateClasspath(approved) }
            compilerOptions("-jvm-target", "21")
        }
        val output = when (input.getValue("operation").jsonPrimitive.content) {
            "compile" -> {
                val source = input.getValue("source").jsonPrimitive.content
                require(source.encodeToByteArray().size <= 32 * 1024)
                val start = System.nanoTime()
                val compilation = BasicJvmScriptingHost().compiler(source.toScriptSource("processor.kts"), config)
                val compiled = compilation.valueOrNull() ?: error(compilation.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.joinToString("; ") { it.message }.take(2048))
                // No script evaluation in the process that exports the compilation artifact.
                buildJsonObject {
                    put("artifact", CompiledArtifact.encode(compiled))
                    put("compilationMillis", (System.nanoTime() - start) / 1_000_000)
                    put("workerUptimeMillis", java.lang.management.ManagementFactory.getRuntimeMXBean().uptime)
                }.toString().also { require(it.encodeToByteArray().size <= CompiledArtifact.MAX_BYTES) }
            }

            "evaluate" -> {
                val observations = input.getValue("observations").jsonArray.map { element ->
                    element.jsonObject.let { Observation(it.getValue("toolName").jsonPrimitive.content, it.getValue("text").jsonPrimitive.content) }
                }
                require(observations.size in 1..16 && observations.sumOf { it.text.encodeToByteArray().size } <= 256 * 1024)
                val compiled = CompiledArtifact.decode(input.getValue("artifact").jsonObject, config)
                // Evaluation workers never construct a compiler host.
                val evaluator = BasicJvmScriptEvaluator()
                val start = System.nanoTime()
                val results = observations.map { observation ->
                    val evaluation = evaluator(
                        compiled,
                        ScriptEvaluationConfiguration {
                            providedProperties(mapOf("observation" to observation))
                            jvm {
                                baseClassLoader(Observation::class.java.classLoader)
                                loadDependencies(false)
                            }
                        },
                    )
                    val outcome = evaluation.valueOrNull()?.returnValue
                    val value = (outcome as? ResultValue.Value)?.value as? String ?: error("Script must evaluate successfully to a String")
                    require(value.encodeToByteArray().size <= 64 * 1024)
                    JsonPrimitive(value)
                }
                buildJsonObject {
                    put("results", JsonArray(results))
                    put("evaluationMillis", (System.nanoTime() - start) / 1_000_000)
                    put("workerUptimeMillis", java.lang.management.ManagementFactory.getRuntimeMXBean().uptime)
                }.toString().also { require(it.encodeToByteArray().size <= 64 * 1024) }
            }

            else -> {
                error("Unknown worker operation")
            }
        }
        System.out.write(output.encodeToByteArray())
        System.out.flush()
    }
