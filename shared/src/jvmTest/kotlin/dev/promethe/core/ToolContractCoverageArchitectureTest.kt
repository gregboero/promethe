package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolContractCoverageArchitectureTest {
    @Test
    fun `every literal production SimpleTool has an explicit contract`() {
        val root = projectRoot()
        val toolNames =
            productionKotlinFiles(root)
                .flatMap { path -> literalSimpleToolNames(path.readText()) }
                .filterNot { name -> '$' in name }
                .distinct()
                .sorted()

        assertTrue(toolNames.size >= 100, "Tool inventory source scan unexpectedly found only ${toolNames.size} tools")
        val report = ToolContractRegistry.audit(toolNames)
        assertTrue(
            report.valid,
            "Tool contract coverage failed for ${report.toolCount} source tools: ${report.issues.joinToString()}",
        )
    }

    @Test
    fun `every effectful static contract has a mandatory negative path`() {
        val report = ToolContractRegistry.audit(ToolContractRegistry.staticContractNames())

        assertTrue(report.valid, "Static tool contract security coverage failed: ${report.issues.joinToString()}")
        assertTrue(report.effectfulToolCount > 0, "Expected effectful contracts in the production catalog")
    }

    @Test
    fun `dynamic MCP and ACP families are explicit and fail closed`() {
        val report = ToolContractRegistry.audit(listOf("mcp_example_mutate", "acp_example_execute"))

        assertTrue(report.valid, report.issues.joinToString())
        listOf("mcp_example_mutate", "acp_example_execute").forEach { toolName ->
            val contract = ToolContractRegistry.contractFor(toolName)
            assertTrue(contract.explicit, toolName)
            assertTrue(contract.evaluate(kotlinx.serialization.json.buildJsonObject {}).mandatoryApproval, toolName)
        }
    }

    private fun literalSimpleToolNames(source: String): List<String> = SIMPLE_TOOL_NAME.findAll(source).map { match -> match.groupValues[1] }.toList()

    private fun productionKotlinFiles(root: Path): List<Path> =
        listOf(
            root.resolve("shared/src/commonMain"),
            root.resolve("shared/src/jvmMain"),
            root.resolve("gateway/src/jvmMain"),
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

    private companion object {
        val SIMPLE_TOOL_NAME =
            Regex(
                pattern = """SimpleTool<[^>]+>\([\s\S]*?\bname\s*=\s*\"([^\"]+)\"""",
            )
    }
}
