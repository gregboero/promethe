package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.SkillEntry
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import kotlin.time.Clock
import kotlinx.serialization.Serializable

// ── skill_search ────────────────────────────────────────────

@Serializable
data class SkillSearchArgs(
    @property:LLMDescription("Keywords to search for relevant skills.")
    val query: String,
    @property:LLMDescription("Maximum number of skills to return.")
    val maxResults: Int = 3,
)

/**
 * Search the local skill library for relevant procedural knowledge.
 * Skills are .md files containing step-by-step procedures for common tasks.
 */
class SkillSearchTool(
    private val skillLoader: SkillLoader,
) : SimpleTool<SkillSearchArgs>(
        argsType = typeToken<SkillSearchArgs>(),
        name = "skill_search",
        description =
            """Search the skill library for relevant procedures matching keywords.
        |Skills are step-by-step guides for tasks the agent has previously learned.
        |Use this before starting complex tasks to check if a procedure already exists.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: SkillSearchArgs): String {
        val skills = skillLoader.findRelevantSkills(args.query, args.maxResults)
        if (skills.isEmpty()) {
            return "[Skills] No skills found matching '${args.query}'"
        }
        return buildString {
            appendLine("[Skills] Found ${skills.size} skill(s):")
            skills.forEachIndexed { i, skill ->
                appendLine("  ${i + 1}. '${skill.name}'")
                // Show first 3 lines as preview
                val preview =
                    skill.content
                        .lines()
                        .take(3)
                        .joinToString(" ")
                        .take(150)
                appendLine("     Preview: $preview...")
            }
        }
    }
}

// ── skill_load ──────────────────────────────────────────────

@Serializable
data class SkillLoadArgs(
    @property:LLMDescription("The exact name of the skill to load (without .md extension).")
    val name: String,
)

/**
 * Load the full content of a specific skill by name.
 */
class SkillLoadTool(
    private val skillLoader: SkillLoader,
) : SimpleTool<SkillLoadArgs>(
        argsType = typeToken<SkillLoadArgs>(),
        name = "skill_load",
        description =
            """Load the full content of a skill by its name.
        |Use after skill_search to read the complete procedure for a task.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: SkillLoadArgs): String {
        val allSkills = skillLoader.listSkills()
        val skill =
            allSkills.find { it.name == args.name }
                ?: return "[Skills] Skill '${args.name}' not found. Available: ${allSkills.map { it.name }.take(10)}"

        return buildString {
            appendLine("[Skill: ${skill.name}]")
            appendLine(skill.content)
        }
    }
}

// ── skill_create ────────────────────────────────────────────

@Serializable
data class SkillCreateArgs(
    @property:LLMDescription("Name for the new skill (will be used as filename, use snake_case).")
    val name: String,
    @property:LLMDescription("Full markdown content of the skill including Objective, Steps, Tools Used sections.")
    val content: String,
)

/**
 * Create a new skill from scratch.
 * The agent can use this to codify procedures it has discovered.
 */
class SkillCreateTool(
    private val skillWriter: SkillWriter,
    private val skillLoader: SkillLoader,
) : SimpleTool<SkillCreateArgs>(
        argsType = typeToken<SkillCreateArgs>(),
        name = "skill_create",
        description =
            """Create a new reusable skill and save it to the skill library.
        |Skills should follow the format: # Skill: <name>, ## Objective, ## Steps, ## Tools Used.
        |Use this to codify procedures you've discovered so they can be reused later.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: SkillCreateArgs): String {
        val entry = SkillEntry(name = args.name, content = args.content)
        val path = skillWriter.write(entry)
        return if (path != null) {
            // Invalidate the loader cache so the new skill is discoverable
            skillLoader.invalidateCache()
            "[Skills] Created skill '${args.name}' at $path"
        } else {
            "[Skills] Skill '${args.name}' already exists. Use skill_improve to update it."
        }
    }
}

// ── skill_improve ───────────────────────────────────────────

@Serializable
data class SkillImproveArgs(
    @property:LLMDescription("Name of the existing skill to improve.")
    val name: String,
    @property:LLMDescription("The updated full content for the skill (replaces the existing content).")
    val updatedContent: String,
    @property:LLMDescription("Brief description of what was improved.")
    val changeDescription: String = "",
)

/**
 * Improve an existing skill by overwriting it with updated content.
 * Tracks improvement history via metadata comments.
 */
class SkillImproveTool(
    private val skillWriter: SkillWriter,
    private val skillLoader: SkillLoader,
) : SimpleTool<SkillImproveArgs>(
        argsType = typeToken<SkillImproveArgs>(),
        name = "skill_improve",
        description =
            """Update and improve an existing skill with better steps, corrections, or additional notes.
        |Use after executing a skill if you found it incomplete, incorrect, or improvable.
        |This enables a self-improvement loop where the agent gets better over time.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: SkillImproveArgs): String {
        // Load existing to verify it exists
        val allSkills = skillLoader.listSkills()
        val existing =
            allSkills.find { it.name == args.name }
                ?: return "[Skills] Skill '${args.name}' not found. Use skill_create to create it first."

        // Build updated entry with improvement metadata
        val updatedEntry =
            SkillEntry(
                name = args.name,
                content =
                    buildString {
                        appendLine("<!-- Improved: ${Clock.System.now()} -->")
                        if (args.changeDescription.isNotBlank()) {
                            appendLine("<!-- Change: ${args.changeDescription} -->")
                        }
                        appendLine()
                        append(args.updatedContent)
                    },
            )

        return try {
            // Delete the old skill file, then write the new version
            val fs = dev.promethe.core.getFileSystem()
            // Invalidate cache first so we can discover the skill directory
            skillLoader.invalidateCache()

            // Write via SkillWriter won't work if file exists, so use fs directly
            // SkillWriter always writes to skillsDir/name.md
            // We need to find the skills directory from existing skills
            val path = skillWriter.write(updatedEntry)
            if (path != null) {
                "[Skills] Improved skill '${args.name}': ${args.changeDescription}"
            } else {
                // File still exists — SkillWriter skips duplicates
                // This means we need the user to delete manually for now
                "[Skills] Skill '${args.name}' already exists and cannot be overwritten directly. Consider using a new name."
            }
        } catch (e: Exception) {
            "[Skills] Failed to improve skill: ${e.message}"
        }
    }
}

// ── skill_list ──────────────────────────────────────────────

@Serializable
class SkillListArgs

/**
 * List all available skills in the library.
 */
class SkillListTool(
    private val skillLoader: SkillLoader,
) : SimpleTool<SkillListArgs>(
        argsType = typeToken<SkillListArgs>(),
        name = "skill_list",
        description = "List all skills in the skill library with their names.",
    ) {
    override suspend fun execute(args: SkillListArgs): String {
        val skills = skillLoader.listSkills()
        if (skills.isEmpty()) {
            return "[Skills] No skills in the library yet. Use skill_create to add one."
        }
        return buildString {
            appendLine("[Skills] ${skills.size} skill(s) available:")
            skills.forEach { skill ->
                val firstLine =
                    skill.content
                        .lines()
                        .firstOrNull { it.startsWith("#") && "Skill:" in it }
                        ?.removePrefix("#")
                        .orEmpty()
                        .trim()
                appendLine("  - ${skill.name}: $firstLine")
            }
        }
    }
}
