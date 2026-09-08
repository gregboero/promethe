package dev.promethe.core

import dev.promethe.api.*
import dev.promethe.core.sandbox.SandboxManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

fun interface HarnessRunner {
    val language: HarnessLanguage get() = HarnessLanguage.JAVASCRIPT

    suspend fun endSession(sessionId: String) {}

    suspend fun executeBatch(
        source: String,
        observations: List<HarnessObservation>,
        sessionId: String,
    ): List<String> = observations.map { execute(source, it, sessionId) }

    suspend fun execute(
        source: String,
        observation: HarnessObservation,
        sessionId: String,
    ): String
}

class HarnessExecutionException(
    result: SandboxedExecutionResult,
) : IllegalStateException(
        "Extension execution failed: ${result.errorCode ?: result.exitCode}; ${result.errorMessage.orEmpty().take(512)}; timedOut=${result.timedOut}; truncated=${result.truncated}",
    ) {
    val timedOut = result.timedOut
    val stderrPreview = result.stderr.take(2048)
    val stdoutPreview = result.stdout.take(2048)
}

class HarnessNodeRunner(
    private val sandbox: SandboxManager,
    private val node: Path,
    private val scratchRoot: Path,
    private val workspaceRoot: Path = scratchRoot.toAbsolutePath().parent,
) : HarnessRunner {
    override suspend fun execute(
        source: String,
        observation: HarnessObservation,
        sessionId: String,
    ): String = run(source, listOf(observation), sessionId, false).single()

    override suspend fun executeBatch(
        source: String,
        observations: List<HarnessObservation>,
        sessionId: String,
    ): List<String> = run(source, observations, sessionId, true)

    private suspend fun run(
        source: String,
        observations: List<HarnessObservation>,
        sessionId: String,
        batch: Boolean,
    ): List<String> {
        require(observations.size in 1..16)
        require(source.encodeToByteArray().size <= 32 * 1024) { "Source exceeds 32 KiB" }
        val input = if (batch) Json.encodeToString(observations) else Json.encodeToString(observations.single())
        require(input.encodeToByteArray().size <= 256 * 1024) { "Observation exceeds 256 KiB" }
        check(node.isAbsolute && Files.isRegularFile(node)) { "Set PROMETHE_HARNESS_NODE to an absolute Node executable" }
        val status = sandbox.status()
        check(status.available && status.selfTestPassed) { "Native sandbox unavailable: ${status.message}" }
        Files.createDirectories(scratchRoot)
        val directory = Files.createTempDirectory(scratchRoot.toRealPath(), "invocation-")
        val id = UUID.randomUUID().toString()
        try {
            Files.writeString(directory.resolve("input.json"), input)
            Files.writeString(directory.resolve("source.json"), Json.encodeToString(source))
            Files.writeString(directory.resolve("runner.cjs"), BOOTSTRAP)
            // Windows validates roots against its already registered workspace and
            // cannot launch a binary in the owner's private Gradle directory.
            val executable = if (System.getProperty("os.name").startsWith("Windows")) {
                Files.copy(node, directory.resolve("node.exe"))
            } else {
                node
            }
            val result = sandbox.execute(
                SandboxedExecutionRequest(
                    executionId = id,
                    sessionId = sessionId,
                    executable = executable.toString(),
                    arguments = listOf("--permission", "--allow-fs-read=$directory", "--preserve-symlinks-main", "--max-old-space-size=128", directory.resolve("runner.cjs").toString()),
                    workingDirectory = directory.toString(),
                    profile = SandboxPermissionProfile(
                        mode = SandboxMode.READ_ONLY,
                        approvalPolicy = SandboxApprovalPolicy.NEVER,
                        networkMode = SandboxNetworkMode.OFF,
                        // Windows ACL certification applies to the registered root;
                        // Node permissions additionally narrow reads to this invocation.
                        readableRoots = listOf(if (System.getProperty("os.name").startsWith("Windows")) workspaceRoot.toString() else directory.toString()),
                        writableRoots = emptyList(),
                        limits = SandboxResourceLimits(timeoutMillis = 2_000, maxOutputBytesPerStream = 64 * 1024, memoryBytes = 256L * 1024 * 1024, processLimit = 1),
                    ),
                ),
            )
            if (result.errorCode != null || result.exitCode != 0 || result.truncated || result.timedOut || result.cancelled) {
                throw HarnessExecutionException(result)
            }
            val output = Json.parseToJsonElement(result.stdout)
            val values = if (batch) (output as kotlinx.serialization.json.JsonArray).toList() else listOf(output)
            require(values.size == observations.size)
            return values.map { element -> element.jsonPrimitive.also { require(it.isString) { "Processor must return a string" } }.content }
                .also { require(it.sumOf { text -> text.encodeToByteArray().size } <= 64 * 1024) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { sandbox.cancel(id) }
            throw cancelled
        } finally {
            // No follow-links: the native invocation has no writable roots.
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private companion object {
        val BOOTSTRAP =
            """
            'use strict';
            const fs = require('node:fs');
            const path = require('node:path');
            const input = JSON.parse(fs.readFileSync(path.join(__dirname, 'input.json'), 'utf8'));
            const source = JSON.parse(fs.readFileSync(path.join(__dirname, 'source.json'), 'utf8'));
            const transform = new Function('observation', '"use strict";\n' + source);
            const results = (Array.isArray(input) ? input : [input]).map(observation => {
                const result = transform(Object.freeze(observation));
                if (typeof result !== 'string') throw new Error('Expected synchronous string result');
                return result;
            });
            process.stdout.write(JSON.stringify(Array.isArray(input) ? results : results[0]));
            """.trimIndent()
    }
}
