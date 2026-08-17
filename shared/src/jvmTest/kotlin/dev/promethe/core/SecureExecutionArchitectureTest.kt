package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class SecureExecutionArchitectureTest {
    @Test
    fun `only ActionExecutor may call executeUnsafe`() {
        val root = projectRoot()
        val violations =
            listOf(root.resolve("shared/src"), root.resolve("gateway/src"))
                .flatMap { sourceRoot ->
                    if (!sourceRoot.isDirectory()) {
                        emptyList()
                    } else {
                        Files.walk(sourceRoot).use { paths ->
                            paths
                                .filter { path ->
                                    path.extension == "kt" &&
                                        !path.toString().contains("Test") &&
                                        path.name != "ActionExecutor.kt"
                                }.filter { path -> path.readText().contains(".executeUnsafe(") }
                                .map { path -> root.relativize(path).toString() }
                                .toList()
                        }
                    }
                }

        assertEquals(emptyList(), violations, "Tool execution bypasses SecureToolExecutor: $violations")
    }

    @Test
    fun `only the trusted sandbox helper launcher may create a process`() {
        val root = projectRoot()
        val allowed =
            setOf(
                "shared/src/jvmMain/kotlin/dev/promethe/core/sandbox/SandboxHelperProcess.kt",
                "shared/src/jvmMain/kotlin/dev/promethe/core/sandbox/WindowsSandboxSetupLauncher.kt",
                "shared/src/jvmMain/kotlin/dev/promethe/core/coding/TrustedLocalAgentProcess.kt",
            )
        val forbidden =
            listOf(
                "ProcessBuilder(",
                "Runtime.getRuntime().exec(",
                "\"sh\", \"-c\"",
                "\"cmd\", \"/c\"",
                "post(\"/execute\")",
            )
        val violations =
            productionKotlinFiles(root)
                .filter { path -> root.relativize(path).toString().replace('\\', '/') !in allowed }
                .flatMap { path ->
                    val source = path.readText()
                    forbidden
                        .filter(source::contains)
                        .map { token -> "${root.relativize(path)} contains $token" }
                }

        assertEquals(emptyList(), violations, "Direct process execution bypasses SandboxProcessLauncher: $violations")
    }

    @Test
    fun `production credential mutations use atomic update`() {
        val root = projectRoot()
        val violations =
            productionKotlinFiles(root)
                .filter { path -> path.readText().contains("CredentialsStore.save(") }
                .map { path -> root.relativize(path).toString() }

        assertEquals(emptyList(), violations, "Credential mutation can overwrite a concurrent update: $violations")
    }

    @Test
    fun `local coding assistants remain tools and are never A2A peers`() {
        val root = projectRoot()
        val codingRoot = root.resolve("shared/src/jvmMain/kotlin/dev/promethe/core/coding")
        val violations =
            Files.walk(codingRoot).use { paths ->
                paths
                    .filter { path -> path.extension == "kt" && path.readText().contains("AgentA2ARegistry") }
                    .map { path -> root.relativize(path).toString() }
                    .toList()
            }
        assertEquals(emptyList(), violations, "Local coding assistants were registered as A2A peers: $violations")
    }

    @Test
    fun `gateway exposes no external agent lifecycle routes`() {
        val root = projectRoot()
        val gatewayRoot = root.resolve("gateway/src/jvmMain")
        val violations =
            Files.walk(gatewayRoot).use { paths ->
                paths
                    .filter { path -> path.extension == "kt" && path.readText().contains("/external-agents") }
                    .map { path -> root.relativize(path).toString() }
                    .toList()
            }
        assertEquals(emptyList(), violations, "Redundant external-agent REST routes were added: $violations")
    }

    private fun productionKotlinFiles(root: Path): List<Path> =
        listOf(
            root.resolve("shared/src/commonMain"),
            root.resolve("shared/src/jvmMain"),
            root.resolve("gateway/src/jvmMain"),
            root.resolve("composeApp/src/desktopMain"),
        ).flatMap { sourceRoot ->
            if (!sourceRoot.isDirectory()) {
                emptyList()
            } else {
                Files.walk(sourceRoot).use { paths ->
                    paths.filter { path -> path.extension == "kt" }.toList()
                }
            }
        }

    private fun projectRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current.parent != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
        }
        return current
    }
}
