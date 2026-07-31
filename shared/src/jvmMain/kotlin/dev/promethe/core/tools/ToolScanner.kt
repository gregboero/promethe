package dev.promethe.core.tools

import ai.koog.agents.core.tools.ToolBase
import java.util.ServiceLoader

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ToolScanner — auto-discovers tools via Java ServiceLoader.
 *
 * Two discovery strategies:
 *   1. **ServiceLoader**: Tools listed in META-INF/services/ai.koog.agents.core.tools.ToolBase
 *   2. **Manual class list**: Explicit fallback for tools not in services file
 */
object ToolScanner {
    /**
     * Discover tools by scanning known tool class names.
     * This is a fallback for when ServiceLoader isn't configured.
     */
    fun discoverKnown(): List<ToolBase<*, *>> {
        val knownToolClasses = listOf(
            "dev.promethe.core.tools.fs.FileDeleteTool",
            "dev.promethe.core.tools.fs.FileMoveTool",
            "dev.promethe.core.tools.fs.DirectoryTreeTool",
            "dev.promethe.core.tools.fs.FileSearchTool",
            "dev.promethe.core.tools.fs.CodeGrepTool",
            "dev.promethe.core.tools.git.GitStatusTool",
            "dev.promethe.core.tools.git.GitDiffTool",
            "dev.promethe.core.tools.git.GitCommitTool",
            "dev.promethe.core.tools.git.GitLogTool",
            "dev.promethe.core.tools.git.GitBranchTool",
            "dev.promethe.core.tools.sys.ProcessManagerTool",
            "dev.promethe.core.tools.sys.SystemInfoTool",
            "dev.promethe.core.tools.sys.EnvironmentTool",
            "dev.promethe.core.tools.data.JsonQueryTool",
            "dev.promethe.core.tools.sec.HashTool",
            "dev.promethe.core.tools.sec.EncryptTool",
            "dev.promethe.core.tools.sec.CertCheckTool",
        )

        val tools = mutableListOf<ToolBase<*, *>>()
        for (className in knownToolClasses) {
            try {
                val clazz = Class.forName(className)
                val ctor = clazz.constructors.firstOrNull { it.parameterCount == 0 }
                if (ctor != null) {
                    val instance = ctor.newInstance()
                    if (instance is ToolBase<*, *>) {
                        tools.add(instance)
                    }
                }
            } catch (e: Exception) {
                logger.debug(e) { "Tool class not available: $className" }
            }
        }
        if (tools.isNotEmpty()) {
            logger.info { "Discovered ${tools.size} tools via known class list" }
        }
        return tools
    }

    /**
     * Get tool category breakdown for status reporting.
     */
    fun categorize(tools: List<ToolBase<*, *>>): Map<ToolCategory, List<ToolBase<*, *>>> =
        tools.groupBy { tool ->
            val annotation = (tool as Any).javaClass.getAnnotation(AgentTool::class.java)
            annotation?.category ?: ToolCategory.OTHER
        }
}
