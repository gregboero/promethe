package dev.promethe.core.sandbox

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal interface SandboxHelperProcess {
    val input: OutputStream
    val output: InputStream
    val error: InputStream
    val isAlive: Boolean

    fun destroy()

    fun destroyForcibly()
}

internal fun interface SandboxHelperProcessFactory {
    fun start(helper: Path): SandboxHelperProcess
}

internal object JvmSandboxHelperProcessFactory : SandboxHelperProcessFactory {
    override fun start(helper: Path): SandboxHelperProcess {
        val launchHelper = verifiedSandboxHelperLaunchCopy(helper)
        val builder = ProcessBuilder(launchHelper.toAbsolutePath().normalize().toString())
        val inheritedEnvironment = System.getenv()
        builder.environment().clear()
        SAFE_HELPER_ENVIRONMENT.forEach { name ->
            inheritedEnvironment[name]?.let { value -> builder.environment()[name] = value }
        }
        helper.parent?.toFile()?.let(builder::directory)
        return try {
            JvmSandboxHelperProcess(builder.start(), launchHelper.takeIf { it != helper })
        } catch (error: Exception) {
            if (launchHelper != helper) {
                Files.deleteIfExists(launchHelper)
            }
            throw error
        }
    }

    private val SAFE_HELPER_ENVIRONMENT =
        setOf(
            "HOME",
            "LANG",
            "LC_ALL",
            "PATH",
            "SystemRoot",
            "TEMP",
            "TMP",
            "TMPDIR",
            "USERPROFILE",
            "WINDIR",
        )
}

internal fun verifiedSandboxHelperLaunchCopy(
    helper: Path,
    environment: Map<String, String> = System.getenv(),
): Path {
    val configuredPath = environment[SANDBOX_HELPER_ENV]?.trim().orEmpty()
    if (configuredPath.isEmpty()) {
        return helper
    }

    val configured = Path.of(configuredPath).toAbsolutePath().normalize()
    require(Files.isSameFile(helper, configured)) {
        "sandbox helper path changed after discovery"
    }
    val expectedHash =
        requireNotNull(parseSandboxHelperHash(environment[SANDBOX_HELPER_SHA256_ENV])) {
            "$SANDBOX_HELPER_SHA256_ENV is required when $SANDBOX_HELPER_ENV is configured"
        }
    val suffix = if (helper.fileName.toString().endsWith(".exe", ignoreCase = true)) ".exe" else ""
    val launchCopy = Files.createTempFile("promethe-sandbox-launch-", suffix)
    try {
        Files.copy(helper, launchCopy, StandardCopyOption.REPLACE_EXISTING)
        if (suffix.isEmpty() && !launchCopy.toFile().setExecutable(true, true)) {
            error("verified sandbox helper copy could not be made executable")
        }
        require(MessageDigest.isEqual(sandboxHelperSha256(launchCopy), expectedHash)) {
            "sandbox helper checksum mismatch before launch"
        }
        launchCopy.toFile().deleteOnExit()
        return launchCopy
    } catch (error: Exception) {
        Files.deleteIfExists(launchCopy)
        throw error
    }
}

private class JvmSandboxHelperProcess(
    private val delegate: Process,
    private val cleanupPath: Path? = null,
) : SandboxHelperProcess {
    init {
        cleanupPath?.let { path ->
            delegate.onExit().thenRun {
                Files.deleteIfExists(path)
            }
        }
    }

    override val input: OutputStream
        get() = delegate.outputStream
    override val output: InputStream
        get() = delegate.inputStream
    override val error: InputStream
        get() = delegate.errorStream
    override val isAlive: Boolean
        get() = delegate.isAlive

    override fun destroy() {
        delegate.destroy()
    }

    override fun destroyForcibly() {
        delegate.destroyForcibly()
    }
}
