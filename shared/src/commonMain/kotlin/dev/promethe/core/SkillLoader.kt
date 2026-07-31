package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * SkillLoader — loads SKILL.md files from disk with in-memory caching
 * and a keyword index for O(1) relevant skill lookup.
 *
 * Supports the standard SKILL.md format:
 * - Directory-based: skills/<name>/SKILL.md (standard, preferred)
 * - Flat file: skills/<name>.md (legacy, backward compat)
 *
 * SKILL.md files use YAML frontmatter:
 * ```
 * ---
 * name: skill-name
 * description: What this skill does and when to use it.
 * ---
 * ## Instructions
 * ...body...
 * ```
 *
 * Progressive disclosure:
 * - L1: name + description (for matching/discovery)
 * - L2: full body (loaded when skill is activated)
 *
 * Cache is invalidated when the skills directory content changes
 * (file count or any file modification time differs from last load).
 */
class SkillLoader(
    private val fs: FileSystem,
    private val skillsDirectory: Path,
) {
    private val logger = Log.create("SkillLoader")

    // ── Cache ──
    private val mutex = Mutex()
    private var cachedSkills: List<SkillEntry> = emptyList()
    private var cacheFingerprint: String = ""

    // ── Keyword Index (inverted) ──
    // keyword → set of skill names that contain it
    private var keywordIndex: Map<String, Set<String>> = emptyMap()

    /**
     * Lists all skills. Uses cache if directory hasn't changed.
     * Supports both directory-based (standard) and flat-file (legacy) formats.
     */
    suspend fun listSkills(): List<SkillEntry> =
        mutex.withLock {
            val fingerprint = computeFingerprint()
            if (fingerprint == cacheFingerprint && cachedSkills.isNotEmpty()) {
                return@withLock cachedSkills
            }
            // Reload
            val skills = loadFromDisk()
            cachedSkills = skills
            cacheFingerprint = fingerprint
            keywordIndex = buildKeywordIndex(skills)
            skills
        }

    /**
     * Loads skills by exact name. Used to load profile-assigned skills.
     * Returns found skills in the order requested; skips missing ones.
     */
    suspend fun loadByNames(names: List<String>): List<SkillEntry> {
        if (names.isEmpty()) return emptyList()
        val allSkills = listSkills()
        val byName = allSkills.associateBy { it.name }
        return names.mapNotNull { name ->
            byName[name].also { if (it == null) logger.debug { "Skill '$name' not found on disk" } }
        }
    }

    // ── Lazy-Loading API ──

    /**
     * Returns lightweight summaries (name + description + source + requirements)
     * for injection into the system prompt. Does NOT include skill body content.
     * ~10 tokens per skill vs ~500+ for full content.
     */
    suspend fun listSummaries(): List<SkillSummaryDto> =
        listSkills().map { entry ->
            SkillSummaryDto(
                name = entry.name,
                description = entry.description,
                source = entry.source,
                requirements = entry.requirements,
            )
        }

    /**
     * Loads the full content of a single skill by name.
     * Used when the LLM activates a skill via the load_skill tool.
     */
    suspend fun loadFull(name: String): SkillEntry? = loadByNames(listOf(name)).firstOrNull()

    /**
     * Returns skills matching query keywords, using progressive disclosure:
     * First matches on description (L1), then falls back to body content (L2).
     */
    suspend fun findRelevantSkills(
        query: String,
        maxResults: Int = 3,
    ): List<SkillEntry> {
        val keywords =
            query
                .lowercase()
                .split(Regex("\\s+"))
                .map { it.filter { char -> char.isLetterOrDigit() } }
                .filter { it.length > 3 }

        if (keywords.isEmpty()) return emptyList()

        val allSkills = listSkills()
        if (allSkills.isEmpty()) return emptyList()

        // Use inverted index for fast scoring
        return if (keywordIndex.isNotEmpty()) {
            findRelevantViaIndex(keywords, allSkills, maxResults)
        } else {
            findRelevantViaScan(keywords, allSkills, maxResults)
        }
    }

    /**
     * Deletes a skill from disk by name.
     * Supports both directory-based and flat-file formats.
     * Returns true if the skill existed and was deleted.
     */
    suspend fun deleteSkill(name: String): Boolean =
        mutex.withLock {
            return@withLock try {
                val dirPath = skillsDirectory / name
                val flatPath = skillsDirectory / "$name.md"

                val deleted = when {
                    // Directory-based (standard)
                    fs.exists(dirPath / "SKILL.md") -> {
                        withContext(ioDispatcher) {
                            // Delete all files in skill directory
                            fs.list(dirPath).forEach { fs.delete(it) }
                            fs.delete(dirPath)
                        }
                        true
                    }

                    // Flat file (legacy)
                    fs.exists(flatPath) -> {
                        withContext(ioDispatcher) { fs.delete(flatPath) }
                        true
                    }

                    else -> {
                        false
                    }
                }

                if (deleted) {
                    cachedSkills = cachedSkills.filter { it.name != name }
                    cacheFingerprint = "" // Force re-index on next access
                    logger.info { "Deleted skill: $name" }
                }
                deleted
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete skill '$name'" }
                false
            }
        }

    /**
     * Force cache invalidation (e.g., after writing a new skill).
     */
    suspend fun invalidateCache() =
        mutex.withLock {
            cachedSkills = emptyList()
            cacheFingerprint = ""
            keywordIndex = emptyMap()
        }

    // ── Internal ──

    private fun findRelevantViaIndex(
        keywords: List<String>,
        allSkills: List<SkillEntry>,
        maxResults: Int,
    ): List<SkillEntry> {
        // Score each skill by how many keywords match via index
        val scores = mutableMapOf<String, Int>()
        for (kw in keywords) {
            val matchingSkills = keywordIndex[kw] ?: continue
            for (name in matchingSkills) {
                scores[name] = (scores[name] ?: 0) + 1
            }
        }

        val skillMap = allSkills.associateBy { it.name }
        return scores.entries
            .sortedByDescending { it.value }
            .take(maxResults)
            .mapNotNull { (name, _) -> skillMap[name] }
    }

    private fun findRelevantViaScan(
        keywords: List<String>,
        allSkills: List<SkillEntry>,
        maxResults: Int,
    ): List<SkillEntry> =
        allSkills
            .map { skill ->
                // Score on description first (L1), then body (L2) with lower weight
                val descScore = keywords.count { kw -> skill.description.lowercase().contains(kw) } * 3
                val bodyScore = keywords.count { kw -> skill.content.lowercase().contains(kw) }
                skill to (descScore + bodyScore)
            }.filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(maxResults)
            .map { (skill, _) -> skill }

    private suspend fun loadFromDisk(): List<SkillEntry> =
        withContext(ioDispatcher) {
            if (!fs.exists(skillsDirectory)) return@withContext emptyList()
            try {
                val entries = mutableListOf<SkillEntry>()

                for (item in fs.list(skillsDirectory)) {
                    val metadata = fs.metadataOrNull(item) ?: continue

                    if (metadata.isDirectory) {
                        // ── Standard format: skill-name/SKILL.md ──
                        val skillFile = item / "SKILL.md"
                        if (fs.exists(skillFile)) {
                            val raw = fs.source(skillFile).buffer().use { it.readUtf8() }
                            entries.add(parseSkillMd(item.name, raw).copy(slug = item.name))
                        }
                    } else if (item.name.endsWith(".md")) {
                        // ── Legacy format: skill-name.md ──
                        val raw = fs.source(item).buffer().use { it.readUtf8() }
                        entries.add(parseSkillMd(item.name.removeSuffix(".md"), raw).copy(slug = item.name.removeSuffix(".md")))
                    }
                }

                entries
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load skills from disk: $skillsDirectory" }
                emptyList()
            }
        }

    /**
     * Parses a SKILL.md file, extracting YAML frontmatter (name, description)
     * and the markdown body.
     */
    private fun parseSkillMd(
        fallbackName: String,
        raw: String,
    ): SkillEntry {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("---")) {
            // No frontmatter — treat entire content as body (legacy)
            return SkillEntry(name = fallbackName, content = trimmed)
        }

        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) {
            return SkillEntry(name = fallbackName, content = trimmed)
        }

        val frontmatter = trimmed.substring(3, endIndex).trim()
        val body = trimmed.substring(endIndex + 3).trim()

        var name = fallbackName
        var description = ""
        var source = SkillSource.CUSTOM

        var requiresOAuth: String? = null
        val requiresCli = mutableListOf<String>()
        val platforms = mutableListOf<String>()

        for (line in frontmatter.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx == -1) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim()
            when (key) {
                "name" -> name = value

                "description" -> description = value

                "source" -> source = try {
                    SkillSource.valueOf(value.uppercase())
                } catch (_: Exception) {
                    SkillSource.CUSTOM
                }

                "requires_python" -> if (value.lowercase() == "true") requiresCli.addAll(listOf("python", "uv"))

                "requires_oauth" -> requiresOAuth = value.ifBlank { null }

                "requires_cli" -> requiresCli.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })

                "platforms" -> platforms.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }

        val requirements = SkillRequirements(
            platforms = platforms,
            requiresCli = requiresCli,
            requiresOAuth = requiresOAuth,
        )

        return SkillEntry(name = name, description = description, content = body, source = source, requirements = requirements)
    }

    /**
     * Build inverted keyword index from both description and content.
     * Description keywords get higher priority via the scoring functions.
     */
    private fun buildKeywordIndex(skills: List<SkillEntry>): Map<String, Set<String>> {
        val index = mutableMapOf<String, MutableSet<String>>()
        for (skill in skills) {
            val text = "${skill.description} ${skill.content}"
            val words =
                text
                    .lowercase()
                    .split(Regex("[\\s\\p{Punct}]+"))
                    .filter { it.length > 3 }
                    .toSet()
            for (word in words) {
                index.getOrPut(word) { mutableSetOf() }.add(skill.name)
            }
        }
        return index
    }

    /**
     * Compute a lightweight fingerprint based on file count + names.
     * Cheap to compute, good enough for invalidation.
     */
    private fun computeFingerprint(): String {
        if (!fs.exists(skillsDirectory)) return "empty"
        return try {
            val items = fs.list(skillsDirectory)
            val parts = mutableListOf<String>()
            for (item in items) {
                val meta = fs.metadataOrNull(item) ?: continue
                if (meta.isDirectory) {
                    if (fs.exists(item / "SKILL.md")) parts.add(item.name)
                } else if (item.name.endsWith(".md")) {
                    parts.add(item.name)
                }
            }
            "${parts.size}:${parts.sorted().joinToString(",")}"
        } catch (e: Exception) {
            logger.debug(e) { "Failed to compute skills directory fingerprint" }
            "error"
        }
    }
}

data class SkillEntry(
    val name: String,
    val description: String = "",
    val content: String,
    val source: SkillSource = SkillSource.CUSTOM,
    val requirements: SkillRequirements = SkillRequirements(),
    /** Directory name on disk (kebab-case), used for system skill matching. */
    val slug: String = "",
)

/**
 * Lightweight skill summary for system prompt injection.
 * Contains only name + description (~10 tokens per skill).
 */
data class SkillSummaryDto(
    val name: String,
    val description: String,
    val source: SkillSource = SkillSource.CUSTOM,
    val requirements: SkillRequirements = SkillRequirements(),
)

/**
 * Declares what a skill needs to run.
 */
data class SkillRequirements(
    val platforms: List<String> = emptyList(),
    val requiresCli: List<String> = emptyList(),
    val requiresOAuth: String? = null,
)

enum class SkillSource {
    BUNDLED,
    OPTIONAL,
    CUSTOM,
}
