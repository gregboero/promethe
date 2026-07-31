package dev.promethe.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Non-regression guard against duplicate tool registrations.
 *
 * ToolRegistry.register() silently overwrites by tool name, so a duplicate
 * registration never fails at runtime — it just wastes a construction and
 * misleads readers about where a tool comes from (this happened with
 * VideoGenerateTool/VideoAnalyzeTool, registered both in
 * registerIntegrationTools and registerExtendedTools).
 *
 * This test scans the two bootstrap sources and asserts every tool class is
 * constructed inside ToolRegistry.register(...) at most once.
 */
class ToolRegistrationDuplicateTest {
    private val registrationSources =
        listOf(
            "src/jvmMain/kotlin/dev/promethe/core/AgentBootstrap.kt",
            "src/jvmMain/kotlin/dev/promethe/core/IntegrationRegistrar.kt",
        )

    // Matches `ToolRegistry.register(` followed (possibly across lines) by a
    // constructor call, capturing the qualified class name. Non-constructor
    // registrations like `ToolRegistry.register(tool)` don't match — fine,
    // those are loops over factories (BrowserTools), not literal duplicates.
    private val registerConstructorRegex =
        Regex("""ToolRegistry\.register\(\s*([A-Za-z][\w.]*)\(""")

    private fun resolveSource(relative: String): File {
        val candidates =
            listOf(
                File(relative),
                File("shared", relative),
                File("../shared", relative),
            )
        return candidates.firstOrNull { it.isFile }
            ?: fail("Cannot locate $relative from ${File(".").absolutePath} — adjust ToolRegistrationDuplicateTest paths")
    }

    @Test
    fun testNoToolClassIsRegisteredTwiceAcrossBootstrapSources() {
        val registrations = mutableListOf<String>()
        for (relative in registrationSources) {
            val text = resolveSource(relative).readText()
            registerConstructorRegex.findAll(text).forEach { match ->
                registrations += match.groupValues[1].substringAfterLast('.')
            }
        }

        assertTrue(registrations.isNotEmpty(), "Expected to find tool registrations in bootstrap sources")

        val duplicates =
            registrations
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }

        assertTrue(
            duplicates.isEmpty(),
            "Tool classes registered more than once across AgentBootstrap/IntegrationRegistrar: $duplicates",
        )
    }
}
