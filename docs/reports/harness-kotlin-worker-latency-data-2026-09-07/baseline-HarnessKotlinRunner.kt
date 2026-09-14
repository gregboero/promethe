package dev.promethe.core

import dev.promethe.api.*
import dev.promethe.core.sandbox.SandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
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

/** Compile and evaluate in separate disposable sandboxed JVMs; cache only opaque bytes in the host. */
class HarnessKotlinRunner(
    private val sandbox: SandboxManager,
    private val distribution: Path,
    private val javaRuntime: Path,
    private val scratchRoot: Path,
    private val workspaceRoot: Path = scratchRoot.toAbsolutePath().parent,
    private val metrics: (KotlinHarnessMetrics) -> Unit = {},
    private val cacheEnabled: Boolean = true,
) : HarnessRunner {
    override val language = HarnessLanguage.KOTLIN
    private val cache = KotlinCompilationCache()
    private val locks = Array(256) { Mutex() }

    private fun lock(session: String) = locks[session.hashCode() and 255]

    internal fun cachedEntries() = cache.size()

    override suspend fun endSession(sessionId: String) = lock(sessionId).withLock { cache.clear(sessionId) }

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
            require(sessionId.isNotBlank() && sessionId.length <= 256)
            require(source.encodeToByteArray().size <= 32 * 1024)
            require(observations.size in 1..16 && observations.sumOf { it.text.encodeToByteArray().size } <= 256 * 1024)
            require(distribution.isAbsolute && javaRuntime.isAbsolute)
            check(Files.isDirectory(distribution.resolve("lib"))) { "Build :harness-kotlin:installDist first" }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val javaRelative = if (windows) "bin/java.exe" else "bin/java"
            check(Files.isRegularFile(javaRuntime.resolve(javaRelative))) { "Prepare the trusted Java 21 runtime first" }
            val status = sandbox.status()
            check(status.available && status.selfTestPassed) { "Native sandbox required for Kotlin scripts" }
            Files.createDirectories(scratchRoot)
            val directory = Files.createTempDirectory(scratchRoot.toRealPath(), "kotlin-")
            var activeExecution: String? = null
            var key: String? = null
            val stageStart = System.nanoTime()
            try {
                val fingerprint = MessageDigest.getInstance("SHA-256")
                fingerprint.update("promethe-kotlin-artifact-v1".encodeToByteArray())
                copyTree(distribution.resolve("lib"), directory.resolve("lib"), fingerprint, "lib")
                copyTree(javaRuntime, directory.resolve("jre"), fingerprint, "jre")
                val jars = Files.list(directory.resolve("lib")).use { files ->
                    files.filter { it.fileName.toString().endsWith(".jar") }.sorted().map { it.toString() }.toList().joinToString(java.io.File.pathSeparator)
                }
                val executable = if (windows) {
                    val launcher = distribution.resolve("bin/harness-jvm.exe")
                    require(!Files.isSymbolicLink(launcher))
                    fingerprint.update(Files.readAllBytes(launcher))
                    Files.copy(launcher, directory.resolve("harness-jvm.exe"))
                } else {
                    directory.resolve("jre").resolve(javaRelative)
                }
                val arguments = if (windows) {
                    listOf(directory.resolve("jre").toString(), jars, directory.resolve("input.json").toString())
                } else {
                    listOf("-Xmx384m", "-XX:MaxMetaspaceSize=256m", "-XX:-UsePerfData", "-Djava.io.tmpdir=$directory", "-Duser.home=$directory", "-cp", jars, "dev.promethe.harness.kotlin.MainKt", directory.resolve("input.json").toString())
                }
                key = SessionHarness.sha256(source) + ":" + fingerprint.digest().joinToString("") { "%02x".format(it) }
                currentCoroutineContext().ensureActive()
                val cached = if (cacheEnabled) cache.get(sessionId, key) else null
                val stageMillis = (System.nanoTime() - stageStart) / 1_000_000
                val processStart = System.nanoTime()

                suspend fun invoke(
                    input: JsonObject,
                    compilation: Boolean,
                ): JsonObject {
                    val text = input.toString()
                    require(text.encodeToByteArray().size <= 768 * 1024)
                    Files.writeString(directory.resolve("input.json"), text)
                    val id = UUID.randomUUID().toString()
                    activeExecution = id
                    val result = sandbox.execute(
                        SandboxedExecutionRequest(
                            executionId = id,
                            sessionId = sessionId,
                            executable = executable.toString(),
                            arguments = arguments,
                            workingDirectory = directory.toString(),
                            profile = SandboxPermissionProfile(
                                mode = SandboxMode.READ_ONLY,
                                approvalPolicy = SandboxApprovalPolicy.NEVER,
                                networkMode = SandboxNetworkMode.OFF,
                                readableRoots = listOf(if (windows) workspaceRoot.toString() else directory.toString()),
                                writableRoots = emptyList(),
                                limits = SandboxResourceLimits(timeoutMillis = if (compilation) 15_000 else 5_000, maxOutputBytesPerStream = if (compilation) 256 * 1024 else 64 * 1024, memoryBytes = 1024L * 1024 * 1024, processLimit = 1),
                            ),
                        ),
                    )
                    activeExecution = null
                    if (result.errorCode != null || result.exitCode != 0 || result.truncated || result.timedOut || result.cancelled) throw HarnessExecutionException(result.copy(errorMessage = "${if (compilation) "compile" else "evaluate"}: ${result.errorMessage.orEmpty()}"))
                    require(result.stdout.encodeToByteArray().size <= if (compilation) 256 * 1024 else 64 * 1024)
                    return Json.parseToJsonElement(result.stdout).jsonObject
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
                values
            } catch (cancelled: CancellationException) {
                cache.clear(sessionId)
                withContext(NonCancellable) { activeExecution?.let { sandbox.cancel(it) } }
                throw cancelled
            } catch (error: Exception) {
                key?.let { cache.remove(sessionId, it) }
                throw error
            } finally {
                Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    private fun copyTree(
        from: Path,
        to: Path,
        digest: MessageDigest,
        label: String,
    ) {
        Files.walk(from).use { paths ->
            paths.sorted().forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Runtime distribution must not contain symbolic links" }
                val relative = from.relativize(path)
                val target = to.resolve(relative)
                digest.update((label + "/" + relative.toString() + "\u0000").encodeToByteArray())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    digest.update((Files.size(path).toString() + "\u0000").encodeToByteArray())
                    DigestInputStream(Files.newInputStream(path), digest).use { Files.copy(it, target) }
                }
            }
        }
    }
}
