package dev.promethe.core.sandbox

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object WindowsSandboxSetupLauncher {
    suspend fun run(
        helper: Path,
        workspaceRoot: String,
        classLoader: ClassLoader = WindowsSandboxSetupLauncher::class.java.classLoader,
    ) = withContext(Dispatchers.IO) {
        check(System.getProperty("os.name").contains("win", ignoreCase = true)) {
            "Elevated sandbox setup is available only on Windows."
        }
        val workspace = Path.of(workspaceRoot).toRealPath()
        val script = Files.createTempFile("promethe-sandbox-setup-", ".ps1")
        val log = Files.createTempFile("promethe-sandbox-setup-", ".log")
        val result = Files.createTempFile("promethe-sandbox-setup-", ".result")
        try {
            val resource =
                requireNotNull(classLoader.getResourceAsStream(SETUP_SCRIPT_RESOURCE)) {
                    "Packaged Windows sandbox setup script is unavailable."
                }
            resource.use { input ->
                Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING)
            }
            val systemRoot = requireNotNull(System.getenv("SystemRoot")) { "SystemRoot is unavailable." }
            val powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
            check(Files.isRegularFile(powershell)) { "Windows PowerShell is unavailable." }
            val builder =
                ProcessBuilder(
                    powershell.toString(),
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    script.toString(),
                    "-WorkspaceRoot",
                    workspace.toString(),
                    "-HelperPath",
                    helper.toAbsolutePath().normalize().toString(),
                    "-ResultPath",
                    result.toString(),
                ).redirectErrorStream(true).redirectOutput(log.toFile())
            val inherited = System.getenv()
            builder.environment().clear()
            SAFE_SETUP_ENVIRONMENT.forEach { name ->
                inherited[name]?.let { value -> builder.environment()[name] = value }
            }
            val process = builder.start()
            if (!process.waitFor(SETUP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("Windows sandbox setup timed out.")
            }
            val setupResult = Files.readString(result).trim().take(MAX_ERROR_LENGTH)
            val output = setupResult.ifBlank { Files.readString(log).trim().take(MAX_ERROR_LENGTH) }
            check(process.exitValue() == 0) {
                output.ifBlank { "Windows sandbox setup failed or UAC was cancelled." }
            }
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(log)
            Files.deleteIfExists(result)
        }
    }

    private const val SETUP_SCRIPT_RESOURCE = "sandbox/windows/setup.ps1"
    private const val SETUP_TIMEOUT_MINUTES = 5L
    private const val MAX_ERROR_LENGTH = 2_000
    private val SAFE_SETUP_ENVIRONMENT =
        setOf(
            "PATH",
            "ProgramData",
            "SystemDrive",
            "SystemRoot",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "WINDIR",
        )
}
