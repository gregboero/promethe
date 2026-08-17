package dev.promethe.core.coding

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface TrustedLocalAgentProcess {
    suspend fun sendLine(line: String)

    suspend fun readLine(timeoutMillis: Long): String?

    suspend fun awaitExit(timeoutMillis: Long): Int?

    fun stderr(): String

    fun cancel()
}

internal fun interface TrustedLocalAgentProcessFactory {
    fun start(
        executable: TrustedExecutable,
        arguments: List<String>,
        workingDirectory: Path,
        extraEnvironment: Map<String, String>,
    ): TrustedLocalAgentProcess
}

internal object JvmTrustedLocalAgentProcessFactory : TrustedLocalAgentProcessFactory {
    override fun start(
        executable: TrustedExecutable,
        arguments: List<String>,
        workingDirectory: Path,
        extraEnvironment: Map<String, String>,
    ): TrustedLocalAgentProcess {
        verifyTrustedExecutable(executable)
        require(arguments.none { argument -> DANGEROUS_FLAGS.any { argument.equals(it, ignoreCase = true) } }) {
            "Unsafe local-agent flags are forbidden"
        }
        val builder = ProcessBuilder(listOf(executable.path.toString()) + arguments)
        builder.directory(workingDirectory.toFile())
        val inherited = System.getenv()
        builder.environment().clear()
        SAFE_ENVIRONMENT.forEach { name -> inherited[name]?.let { builder.environment()[name] = it } }
        extraEnvironment.forEach { (name, value) -> builder.environment()[name] = value }
        return JvmTrustedLocalAgentProcess(builder.start())
    }
}

internal fun trustedExecutable(path: Path): TrustedExecutable {
    val canonical = path.toRealPath()
    require(Files.isRegularFile(canonical)) { "Local-agent executable is not a regular file" }
    return TrustedExecutable(canonical, sha256(canonical))
}

private fun verifyTrustedExecutable(executable: TrustedExecutable) {
    val canonical = executable.path.toRealPath()
    require(canonical == executable.path) { "Local-agent executable path changed after detection" }
    val actual = sha256(canonical)
    require(MessageDigest.isEqual(actual.hexToByteArray(), executable.sha256.hexToByteArray())) {
        "Local-agent executable checksum changed after detection"
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private class JvmTrustedLocalAgentProcess(
    private val process: Process,
) : TrustedLocalAgentProcess {
    private val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
    private val errorBuffer = StringBuilder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (errorBuffer.length < MAX_STDERR) errorBuffer.appendLine(line.take(MAX_LINE))
                }
            }
        }
    }

    override suspend fun sendLine(line: String) =
        withContext(Dispatchers.IO) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }

    override suspend fun readLine(timeoutMillis: Long): String? =
        withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.IO) { reader.readLine() }
        }

    override suspend fun awaitExit(timeoutMillis: Long): Int? =
        withContext(Dispatchers.IO) {
            if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) process.exitValue() else null
        }

    override fun stderr(): String = errorBuffer.toString().take(MAX_STDERR)

    override fun cancel() {
        process.toHandle().descendants().forEach { child -> child.destroyForcibly() }
        process.destroyForcibly()
        scope.cancel()
    }
}

private val DANGEROUS_FLAGS =
    setOf(
        "--yolo",
        "--dangerously-skip-permissions",
        "--danger-full-access",
        "danger-full-access",
        "--full-auto",
    )

private val SAFE_ENVIRONMENT =
    setOf(
        "CODEX_HOME",
        "CLAUDE_CONFIG_DIR",
        "HOME",
        "LANG",
        "LC_ALL",
        "PATH",
        "SystemDrive",
        "SystemRoot",
        "TEMP",
        "TMP",
        "TMPDIR",
        "USERPROFILE",
        "WINDIR",
    )

private const val MAX_STDERR = 16_384
private const val MAX_LINE = 4_096
