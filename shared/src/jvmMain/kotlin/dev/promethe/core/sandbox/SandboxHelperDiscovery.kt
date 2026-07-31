package dev.promethe.core.sandbox

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.absolutePathString

internal const val SANDBOX_HELPER_ENV = "PROMETHE_SANDBOX_HELPER"
internal const val SANDBOX_HELPER_SHA256_ENV = "PROMETHE_SANDBOX_HELPER_SHA256"

internal data class DiscoveredSandboxHelper(
    val path: Path,
    val temporary: Boolean,
)

internal class SandboxHelperDiscovery(
    private val environment: Map<String, String> = System.getenv(),
    private val classLoader: ClassLoader = SandboxHelperDiscovery::class.java.classLoader,
    private val osName: String = System.getProperty("os.name"),
    private val architecture: String = System.getProperty("os.arch"),
) {
    fun discover(): Result<DiscoveredSandboxHelper> =
        runCatching {
            val expectedHash = parseSandboxHelperHash(environment[SANDBOX_HELPER_SHA256_ENV])
            val configuredPath = environment[SANDBOX_HELPER_ENV]?.trim().orEmpty()
            val helper =
                if (configuredPath.isNotEmpty()) {
                    require(expectedHash != null) {
                        "$SANDBOX_HELPER_SHA256_ENV is required when $SANDBOX_HELPER_ENV is configured"
                    }
                    DiscoveredSandboxHelper(validateConfiguredPath(configuredPath), temporary = false)
                } else {
                    extractPackagedHelper()
                }

            if (expectedHash != null) {
                val actualHash = sandboxHelperSha256(helper.path)
                require(MessageDigest.isEqual(actualHash, expectedHash)) {
                    "sandbox helper checksum mismatch"
                }
            }
            helper
        }

    private fun validateConfiguredPath(configuredPath: String): Path {
        val path = Path.of(configuredPath).toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "configured sandbox helper is not a regular file" }
        require(isWindows() || Files.isExecutable(path)) { "configured sandbox helper is not executable" }
        return path
    }

    private fun extractPackagedHelper(): DiscoveredSandboxHelper {
        val platform = normalizedPlatform()
        val arch = normalizedArchitecture()
        val binaryName = if (platform == "windows") "promethe-sandbox.exe" else "promethe-sandbox"
        val candidates =
            listOf(
                "sandbox/$platform-$arch/$binaryName",
                "sandbox/$platform/$arch/$binaryName",
                "native/$platform-$arch/$binaryName",
            )
        val resource =
            candidates.firstNotNullOfOrNull { candidate ->
                classLoader.getResourceAsStream(candidate)?.let { candidate to it }
            } ?: error("packaged sandbox helper is unavailable")

        val suffix = if (platform == "windows") ".exe" else ""
        val extracted = Files.createTempFile("promethe-sandbox-", suffix)
        resource.second.use { input ->
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING)
        }
        if (!isWindows() && !extracted.toFile().setExecutable(true, true)) {
            Files.deleteIfExists(extracted)
            error("packaged sandbox helper could not be made executable")
        }
        extracted.toFile().deleteOnExit()
        return DiscoveredSandboxHelper(extracted.toAbsolutePath().normalize(), temporary = true)
    }

    private fun normalizedPlatform(): String =
        when {
            osName.contains("win", ignoreCase = true) -> "windows"
            osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> "macos"
            osName.contains("linux", ignoreCase = true) -> "linux"
            else -> error("unsupported sandbox platform")
        }

    private fun normalizedArchitecture(): String =
        when (architecture.lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("unsupported sandbox architecture")
        }

    private fun isWindows(): Boolean = osName.contains("win", ignoreCase = true)

    fun describeFailure(): String = "Sandbox helper is unavailable. Configure $SANDBOX_HELPER_ENV or install the packaged helper."

    fun describePath(helper: DiscoveredSandboxHelper): String = if (helper.temporary) "packaged helper" else helper.path.fileName?.toString() ?: helper.path.absolutePathString()
}

internal fun parseSandboxHelperHash(value: String?): ByteArray? {
    val normalized = value?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) {
        return null
    }
    require(normalized.length == SHA256_HEX_LENGTH && normalized.all { it in HEX_DIGITS }) {
        "$SANDBOX_HELPER_SHA256_ENV must contain a 64-character hexadecimal SHA-256"
    }
    return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal fun sandboxHelperSha256(path: Path): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        updateSandboxHelperDigest(digest, input)
    }
    return digest.digest()
}

private fun updateSandboxHelperDigest(
    digest: MessageDigest,
    input: InputStream,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) {
            return
        }
        digest.update(buffer, 0, count)
    }
}

private const val SHA256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"
