package dev.promethe.core.tools

/**
 * Annotation marker for auto-discoverable agent tools.
 *
 * Tools annotated with @AgentTool will be picked up by ToolScanner
 * via ServiceLoader (META-INF/services) or classpath scanning.
 *
 * @param name        Tool name as exposed to the LLM
 * @param description Human-readable description
 * @param category    Tool category for organization
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AgentTool(
    val name: String,
    val description: String = "",
    val category: ToolCategory = ToolCategory.OTHER,
)

/**
 * Tool categories for grouping and filtering.
 */
enum class ToolCategory {
    FILESYSTEM,
    CODE,
    GIT,
    WEB,
    COMMUNICATION,
    DATA,
    SYSTEM,
    AI,
    SECURITY,
    BROWSER,
    OTHER,
}
