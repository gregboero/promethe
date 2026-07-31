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
     * with YAML frontmatter (name + description).
     *
     * Returns the path of the written file, or null if it already exists (dedup).
     */
    suspend fun write(skill: SkillEntry): Path? =
        withContext(ioDispatcher) {
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

            // Build content with YAML frontmatter
            val fullContent = buildString {
                appendLine("---")
                appendLine("name: ${skill.name}")
                if (skill.description.isNotBlank()) {
                    appendLine("description: ${skill.description}")
                }
                appendLine("---")
                appendLine()
                append(skill.content)
            }

            val tempPath = skillDir / "SKILL.md.tmp"
            val sink = fs.sink(tempPath).buffer()
            try {
                sink.writeUtf8(fullContent)
                sink.close()
                fs.atomicMove(tempPath, targetPath)
                logger.info { "New skill written: $targetPath" }
                targetPath
            } catch (e: Exception) {
                try {
                    sink.close()
                } catch (closeEx: Exception) {
                    logger.warn(closeEx) { "Failed to close sink during skill write recovery" }
                }
                logger.error(e) { "Failed to write skill" }
                if (fs.exists(tempPath)) {
                    try {
                        fs.delete(tempPath)
                    } catch (deleteEx: Exception) {
                        logger.warn(deleteEx) { "Failed to delete temp skill file: $tempPath" }
                    }
                }
                null
            }
        }
}
