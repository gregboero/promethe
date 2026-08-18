package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentExecutionArchitectureTest {
    @Test
    fun `only AgentExecutionService enters the agent loop`() {
        val root = projectRoot()
        val violations =
            listOf(root.resolve("shared/src"), root.resolve("gateway/src"))
                .flatMap { sourceRoot ->
                    if (!sourceRoot.isDirectory()) {
                        emptyList()
                    } else {
                        Files.walk(sourceRoot).use { paths ->
                            paths.filter { path ->
                                path.extension == "kt" &&
                                    !path.toString().contains("Test") &&
                                    path.name != "AgentExecutionService.kt"
                            }.filter { path ->
                                path.readLines().any { line ->
                                    val trimmed = line.trim()
                                    !trimmed.startsWith("//") &&
                                        !trimmed.startsWith("*") &&
                                        ".executeLoop(" in trimmed
                                }
                            }.map { path -> root.relativize(path).toString() }.toList()
                        }
                    }
                }

        assertEquals(emptyList(), violations, "Agent loop bypasses AgentExecutionService: $violations")
    }

    @Test
    fun `agent run events remain append only`() {
        val root = projectRoot()
        val forbiddenPatterns =
            listOf(
                "AgentRunEvents.update",
                "AgentRunEvents.deleteWhere",
                "AgentRunEvents.deleteAll",
                "UPDATE agent_run_events",
                "DELETE FROM agent_run_events",
            )
        val violations =
            listOf(root.resolve("shared/src/commonMain"), root.resolve("shared/src/jvmMain"))
                .flatMap { sourceRoot ->
                    if (!sourceRoot.isDirectory()) {
                        emptyList()
                    } else {
                        Files.walk(sourceRoot).use { paths ->
                            paths.filter { path -> path.extension == "kt" }
                                .filter { path ->
                                    path.readLines().any { line -> forbiddenPatterns.any { pattern -> pattern in line } }
                                }.map { path -> root.relativize(path).toString() }
                                .toList()
                        }
                    }
                }

        assertEquals(emptyList(), violations, "Agent run events must never be updated or deleted: $violations")
    }

    private fun projectRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current.parent != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
        }
        return current
    }
}
