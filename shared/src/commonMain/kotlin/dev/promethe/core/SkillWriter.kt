package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer

class SkillWriter(
    private val fs: FileSystem,
    private val skillsDirectory: Path,
) {
    private val logger = Log.create("SkillWriter")

    /**
     * Writes a skill in standard SKILL.md format: skills/<name>/SKILL.md
     * with a versioned lifecycle contract and content hash in frontmatter.
     *
     * Returns the path of the written file, or null if it already exists (dedup).
     */
    suspend fun write(skill: SkillEntry): Path? =
        withContext(ioDispatcher) {
            if (!isValidSkillSlug(skill.name)) {
                logger.warn { "Rejected invalid skill name '${skill.name}'" }
                return@withContext null
            }
            if (!fs.exists(skillsDirectory)) {
                fs.createDirectories(skillsDirectory)
            }

            val skillDir = skillsDirectory / skill.name
            val targetPath = skillDir / "SKILL.md"

            // Also check legacy flat file for deduplication
            val legacyPath = skillsDirectory / "${skill.name}.md"

            if (fs.exists(targetPath) || fs.exists(legacyPath)) {
                logger.info { "Skill '${skill.name}' already exists, skipping" }
                return@withContext null
            }

            // Create skill directory
            if (!fs.exists(skillDir)) {
                fs.createDirectories(skillDir)
            }

            if (writeAtomically(targetPath, renderSkill(skill))) {
                logger.info { "New skill written: $targetPath" }
                targetPath
            } else {
                null
            }
        }

    /** Atomically replaces an existing standard or legacy skill file. */
    suspend fun update(skill: SkillEntry): Path? =
        withContext(ioDispatcher) {
            val slug = skill.slug.ifBlank { skill.name }
            if (!isValidSkillSlug(slug)) {
                logger.warn { "Rejected invalid skill slug '$slug'" }
                return@withContext null
            }
            val directoryPath = skillsDirectory / slug / "SKILL.md"
            val legacyPath = skillsDirectory / "$slug.md"
            val targetPath =
                when {
                    fs.exists(directoryPath) -> directoryPath
                    fs.exists(legacyPath) -> legacyPath
                    else -> return@withContext null
                }
            if (writeAtomically(targetPath, renderSkill(skill))) {
                logger.info { "Skill updated: $targetPath" }
                targetPath
            } else {
                null
            }
        }

    private fun renderSkill(skill: SkillEntry): String {
        val content = normalizeSkillBody(skill.content)
        val contract = skill.contract
        return buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            if (skill.description.isNotBlank()) appendLine("description: ${frontmatterScalar(skill.description)}")
            appendLine("source: ${skill.source.name.lowercase()}")
            appendLine("lifecycle: ${contract.lifecycle.name}")
            appendLine("version: ${frontmatterScalar(contract.version)}")
            appendLine("content_hash: ${skillContentDigest(content)}")
            contract.owner?.let { appendLine("owner: ${frontmatterScalar(it)}") }
            contract.provenance?.let { appendLine("provenance: ${frontmatterScalar(it)}") }
            appendList("triggers", contract.triggers)
            appendList("anti_triggers", contract.antiTriggers)
            appendList("required_tools", contract.requiredTools)
            appendList("required_skills", contract.requiredSkills)
            appendList("eval_suite", contract.evalSuite)
            appendList("requires_cli", skill.requirements.requiresCli)
            skill.requirements.requiresOAuth?.let { appendLine("requires_oauth: ${frontmatterScalar(it)}") }
            appendList("platforms", skill.requirements.platforms)
            appendLine("---")
            appendLine()
            append(content)
        }
    }

    private fun StringBuilder.appendList(
        key: String,
        values: List<String>,
    ) {
        if (values.isNotEmpty()) appendLine("$key: ${values.joinToString(", ") { value -> frontmatterScalar(value) }}")
    }

    private fun frontmatterScalar(value: String): String = value.replace(Regex("[\r\n]+"), " ").trim()

    private fun writeAtomically(
        targetPath: Path,
        content: String,
    ): Boolean {
        val parent = targetPath.parent ?: return false
        val tempPath = parent / "${targetPath.name}.tmp"
        return try {
            fs.sink(tempPath).buffer().use { sink -> sink.writeUtf8(content) }
            fs.atomicMove(tempPath, targetPath)
            true
        } catch (error: Exception) {
            logger.error(error) { "Failed to write skill: $targetPath" }
            if (fs.exists(tempPath)) {
                try {
                    fs.delete(tempPath)
                } catch (deleteError: Exception) {
                    logger.warn(deleteError) { "Failed to delete temp skill file: $tempPath" }
                }
            }
            false
        }
    }
}
