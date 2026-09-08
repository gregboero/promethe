package dev.promethe.core

import dev.promethe.api.*
import dev.promethe.core.sandbox.SandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class KotlinHarnessMetrics(
    val stagingMillis: Long,
    val processMillis: Long,
    val compilationMillis: Long,
    val evaluationMillis: Long,
    val observations: Int,
    val cacheHit: Boolean = false,
    val artifactBytes: Int = 0,
    val workerProcesses: Int = 1,
)

@kotlinx.serialization.Serializable
data class KotlinWorkerTiming(
    val compilation: Boolean,
    val sandboxMillis: Long,
    val childMillis: Long,
    val workerUptimeMillis: Long?,
)

@kotlinx.serialization.Serializable
data class KotlinRunnerTiming(
    val statusMillis: Long,
    val stagingMillis: Long,
    val cleanupMillis: Long,
    val totalMillis: Long,
    val successful: Boolean,
    val workers: List<KotlinWorkerTiming>,
)

/** Compile and evaluate in separate disposable sandboxed JVMs; cache only opaque bytes in the host. */
class HarnessKotlinRunner(
    private val sandbox: SandboxManager,
    private val distribution: Path,
    private val javaRuntime: Path,
    private val scratchRoot: Path,
    private val workspaceRoot: Path = scratchRoot.toAbsolutePath().parent,
    private val metrics: (KotlinHarnessMetrics) -> Unit = {},
    private val cacheEnabled: Boolean = true,
    private val optimizeEvaluationStartup: Boolean = true,
    private val timing: (KotlinRunnerTiming) -> Unit = {},
    private val reusePreparedRuntime: Boolean = true,
) : HarnessRunner {
    override val language = HarnessLanguage.KOTLIN
    private val cache = KotlinCompilationCache()
    private val runtimePreparation = KotlinRuntimePreparation(distribution, javaRuntime, scratchRoot, reusePreparedRuntime)
    private val locks = Array(256) { Mutex() }

    private fun lock(session: String) = locks[session.hashCode() and 255]

    internal fun cachedEntries() = cache.size()

    internal fun preparedRuntimeEntries() = runtimePreparation.size()

    override suspend fun endSession(sessionId: String) =
        lock(sessionId).withLock {
            cache.clear(sessionId)
            runtimePreparation.clear(sessionId)
        }

    override suspend fun execute(
        source: String,
        observation: HarnessObservation,
        sessionId: String,
    ): String = executeBatch(source, listOf(observation), sessionId).single()

    override suspend fun executeBatch(
        source: String,
        observations: List<HarnessObservation>,
        sessionId: String,
    ): List<String> =
        lock(sessionId).withLock {
            val totalStart = System.nanoTime()
            require(sessionId.isNotBlank() && sessionId.length <= 256)
            require(source.encodeToByteArray().size <= 32 * 1024)
            require(observations.size in 1..16 && observations.sumOf { it.text.encodeToByteArray().size } <= 256 * 1024)
            require(distribution.isAbsolute && javaRuntime.isAbsolute)
            check(Files.isDirectory(distribution.resolve("lib"))) { "Build :harness-kotlin:installDist first" }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val javaRelative = if (windows) "bin/java.exe" else "bin/java"
            check(Files.isRegularFile(javaRuntime.resolve(javaRelative))) { "Prepare the trusted Java 21 runtime first" }
            val statusStart = System.nanoTime()
            val status = sandbox.status()
            val statusMillis = (System.nanoTime() - statusStart) / 1_000_000
            check(status.available && status.selfTestPassed) { "Native sandbox required for Kotlin scripts" }
            Files.createDirectories(scratchRoot)
            val stageStart = System.nanoTime()
            val prepared = runtimePreparation.acquire(sessionId)
            val directory = try {
                Files.createTempDirectory(prepared.directory, "call-")
            } catch (error: Throwable) {
                runtimePreparation.release(sessionId, prepared, false)
                throw error
            }
            var activeExecution: String? = null
            var key: String? = null
            var stageMillis = 0L
            var successful = false
            val workerTimings = mutableListOf<KotlinWorkerTiming>()
            try {
                val jars = Files.list(prepared.directory.resolve("lib")).use { files ->
                    files.filter { it.fileName.toString().endsWith(".jar") }.sorted().map { it.toString() }.toList().joinToString(java.io.File.pathSeparator)
                }
                val executable = if (windows) prepared.directory.resolve("harness-jvm.exe") else prepared.directory.resolve("jre").resolve(javaRelative)
                val arguments = if (windows) {
                    listOf(prepared.directory.resolve("jre").toString(), jars, directory.resolve("input.json").toString())
                } else {
                    listOf("-Xmx384m", "-XX:MaxMetaspaceSize=256m", "-XX:-UsePerfData", "-Djava.io.tmpdir=$directory", "-Duser.home=$directory", "-cp", jars, "dev.promethe.harness.kotlin.MainKt", directory.resolve("input.json").toString())
                }
                key = SessionHarness.sha256(source) + ":" + prepared.fingerprint
                currentCoroutineContext().ensureActive()
                val cached = if (cacheEnabled) cache.get(sessionId, key) else null
                stageMillis = (System.nanoTime() - stageStart) / 1_000_000
                val processStart = System.nanoTime()

                suspend fun invoke(
                    input: JsonObject,
                    compilation: Boolean,
                ): JsonObject {
                    val text = input.toString()
                    require(text.encodeToByteArray().size <= 768 * 1024)
                    Files.writeString(directory.resolve("input.json"), text)
                    val id = UUID.randomUUID().toString()
                    val invocationArguments = if (!compilation && optimizeEvaluationStartup) {
                        if (windows) arguments + "fast-evaluate" else listOf("-XX:TieredStopAtLevel=1") + arguments
                    } else {
                        arguments
                    }
                    activeExecution = id
                    val sandboxStart = System.nanoTime()
                    val result = sandbox.execute(
                        SandboxedExecutionRequest(
                            executionId = id,
                            sessionId = sessionId,
                            executable = executable.toString(),
                            arguments = invocationArguments,
                            workingDirectory = directory.toString(),
                            profile = SandboxPermissionProfile(
                                mode = SandboxMode.READ_ONLY,
                                approvalPolicy = SandboxApprovalPolicy.NEVER,
                                networkMode = SandboxNetworkMode.OFF,
                                readableRoots = listOf(if (windows) workspaceRoot.toString() else prepared.directory.toString()),
                                writableRoots = emptyList(),
                                limits = SandboxResourceLimits(timeoutMillis = if (compilation) 15_000 else 5_000, maxOutputBytesPerStream = if (compilation) 256 * 1024 else 64 * 1024, memoryBytes = 1024L * 1024 * 1024, processLimit = 1),
                            ),
                        ),
                    )
                    activeExecution = null
                    val sandboxMillis = (System.nanoTime() - sandboxStart) / 1_000_000
                    val bounded = result.stdout.encodeToByteArray().size <= if (compilation) 256 * 1024 else 64 * 1024
                    val parsed = if (bounded) runCatching { Json.parseToJsonElement(result.stdout).jsonObject }.getOrNull() else null
                    workerTimings.add(KotlinWorkerTiming(compilation, sandboxMillis, result.durationMillis, (parsed?.get("workerUptimeMillis") as? JsonPrimitive)?.longOrNull))
                    if (result.errorCode != null || result.exitCode != 0 || result.truncated || result.timedOut || result.cancelled) throw HarnessExecutionException(result.copy(errorMessage = "${if (compilation) "compile" else "evaluate"}: ${result.errorMessage.orEmpty()}"))
                    require(bounded)
                    return requireNotNull(parsed)
                }
                var compilationMillis = 0L
                val artifact = cached ?: invoke(
                    buildJsonObject {
                        put("operation", "compile")
                        put("source", source)
                    },
                    true,
                ).let {
                    compilationMillis = it.getValue("compilationMillis").jsonPrimitive.long
                    it.getValue("artifact").jsonObject.toString().also { bytes -> require(bytes.encodeToByteArray().size <= 256 * 1024) }
                }
                val body = invoke(
                    buildJsonObject {
                        put("operation", "evaluate")
                        put("artifact", Json.parseToJsonElement(artifact))
                        put("observations", Json.parseToJsonElement(Json.encodeToString(observations)))
                    },
                    false,
                )
                val values = body.getValue("results").jsonArray.map { it.jsonPrimitive.also { value -> require(value.isString) }.content }
                require(values.size == observations.size && values.sumOf { it.encodeToByteArray().size } <= 64 * 1024)
                currentCoroutineContext().ensureActive()
                if (cacheEnabled && cached == null) cache.put(sessionId, key, artifact)
                metrics(KotlinHarnessMetrics(stageMillis, (System.nanoTime() - processStart) / 1_000_000, compilationMillis, body.getValue("evaluationMillis").jsonPrimitive.long, observations.size, cached != null, artifact.encodeToByteArray().size, if (cached != null) 1 else 2))
                successful = true
                values
            } catch (cancelled: CancellationException) {
                cache.clear(sessionId)
                withContext(NonCancellable) { activeExecution?.let { sandbox.cancel(it) } }
                throw cancelled
            } catch (error: Exception) {
                key?.let { cache.remove(sessionId, it) }
                throw error
            } finally {
                val cleanupStart = System.nanoTime()
                var cleaned = false
                try {
                    try {
                        require(directory.toRealPath() == directory.toAbsolutePath().normalize() && directory.parent == prepared.directory) { "Invocation cleanup must stay in the prepared runtime" }
                        Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    } catch (error: Throwable) {
                        successful = false
                        throw error
                    } finally {
                        runtimePreparation.release(sessionId, prepared, successful)
                    }
                    cleaned = true
                } finally {
                    runCatching { timing(KotlinRunnerTiming(statusMillis, stageMillis, (System.nanoTime() - cleanupStart) / 1_000_000, (System.nanoTime() - totalStart) / 1_000_000, successful && cleaned, workerTimings.toList())) }
                }
            }
        }
}
