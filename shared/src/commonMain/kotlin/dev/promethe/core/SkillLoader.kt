package dev.promethe.core

import dev.promethe.api.SkillContract
import dev.promethe.api.SkillLifecycle
import dev.promethe.core.Log

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * SkillLoader — loads SKILL.md files and builds a keyword index for relevant skill lookup.
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
 * Bodies and governance evidence are reloaded before use so out-of-band edits
 * cannot retain a previously executable cached revision.
 */
class SkillLoader(
    private val fs: FileSystem,
    private val skillsDirectory: Path,
) {
    private val logger = Log.create("SkillLoader")

    private val mutex = Mutex()

    // ── Keyword Index (inverted) ──
    // keyword → set of skill names that contain it
    private var keywordIndex: Map<String, Set<String>> = emptyMap()

    /**
     * Lists skills using their current content and governance state.
     * Supports both directory-based (standard) and flat-file (legacy) formats.
     */
    suspend fun listSkills(): List<SkillEntry> =
        mutex.withLock {
            // Reload bodies and governance state: same-size edits and external suite changes
            // must never retain an executable cached revision.
            val skills = loadFromDisk()
            keywordIndex = buildKeywordIndex(skills)
            skills
        }

    /** Skills eligible for prompt injection and agent-side loading. */
    suspend fun listExecutableSkills(): List<SkillEntry> =
        listSkills().filter { skill ->
            skill.contract.lifecycle == SkillLifecycle.ACTIVE && SkillGovernanceStore(fs, skillsDirectory).isExecutable(skill)
        }

    /**
     * Loads skills by exact name. Used to load profile-assigned skills.
     * Returns found skills in the order requested; skips missing ones.
     */
    suspend fun loadByNames(names: List<String>): List<SkillEntry> {
        if (names.isEmpty()) return emptyList()
        val allSkills = listExecutableSkills()
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
        listExecutableSkills().map { entry ->
            SkillSummaryDto(
                name = entry.name,
                description = entry.description,
                source = entry.source,
                requirements = entry.requirements,
                contract = entry.contract,
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

        val allSkills = listExecutableSkills()
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
            if (!isValidSkillSlug(name)) {
                logger.warn { "Rejected invalid skill name '$name'" }
                return@withLock false
            }
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
                    keywordIndex = emptyMap()
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
                            entries.add(withRevision(parseSkillMd(item.name, raw).copy(slug = item.name)))
                        }
                    } else if (item.name.endsWith(".md")) {
                        // ── Legacy format: skill-name.md ──
                        val raw = fs.source(item).buffer().use { it.readUtf8() }
                        entries.add(withRevision(parseSkillMd(item.name.removeSuffix(".md"), raw).copy(slug = item.name.removeSuffix(".md"))))
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
    internal fun parseSkillMd(
        fallbackName: String,
        raw: String,
    ): SkillEntry {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("---")) {
            // No frontmatter — treat entire content as body (legacy)
            return SkillEntry(
                name = fallbackName,
                content = trimmed,
                contract = SkillContract(contentHash = skillContentDigest(trimmed)),
            )
        }

        val endIndex = frontmatterEndIndex(trimmed)
        if (endIndex == -1) {
            return SkillEntry(
                name = fallbackName,
                content = trimmed,
                contract = SkillContract(contentHash = skillContentDigest(trimmed)),
            )
        }

        val frontmatter = trimmed.substring(3, endIndex).trim()
        val body = trimmed.substring(endIndex + 3).trim()

        var name = fallbackName
        var description = ""
        var source = SkillSource.CUSTOM
        var lifecycle = SkillLifecycle.ACTIVE
        var provenance: String? = null
        var version = "1"
        var owner: String? = null
        var declaredContentHash: String? = null
        var reviewedContentHash: String? = null
        var reviewNote: String? = null
        var reviewedAt: String? = null
        var validationRequired = false
        var revisionId: String? = null
        var evaluatedRunId: String? = null
        var reviewedRevisionHash: String? = null

        var requiresOAuth: String? = null
        val requiresCli = mutableListOf<String>()
        val platforms = mutableListOf<String>()
        val triggers = mutableListOf<String>()
        val antiTriggers = mutableListOf<String>()
        val requiredTools = mutableListOf<String>()
        val requiredSkills = mutableListOf<String>()
        val evalSuite = mutableListOf<String>()

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

                "lifecycle" -> lifecycle = try {
                    SkillLifecycle.valueOf(value.uppercase())
                } catch (_: Exception) {
                    SkillLifecycle.QUARANTINED
                }

                "triggers" -> triggers.addAll(parseFrontmatterList(value))

                "anti_triggers" -> antiTriggers.addAll(parseFrontmatterList(value))

                "required_tools" -> requiredTools.addAll(parseFrontmatterList(value))

                "required_skills" -> requiredSkills.addAll(parseFrontmatterList(value))

                "eval_suite" -> evalSuite.addAll(parseFrontmatterList(value))

                "provenance" -> provenance = value.ifBlank { null }

                "version" -> version = value.ifBlank { "1" }

                "owner" -> owner = value.ifBlank { null }

                "content_hash" -> declaredContentHash = value.ifBlank { null }

                "reviewed_content_hash" -> reviewedContentHash = value.ifBlank { null }

                "review_note" -> reviewNote = value.ifBlank { null }

                "reviewed_at" -> reviewedAt = value.ifBlank { null }

                "validation_required" -> validationRequired = value != "false"

                "revision_id" -> revisionId = value.ifBlank { null }

                "evaluated_run_id" -> evaluatedRunId = value.ifBlank { null }

                "reviewed_revision_hash" -> reviewedRevisionHash = value.ifBlank { null }

                "requires_python" -> if (value.lowercase() == "true") requiresCli.addAll(listOf("python", "uv"))

                "requires_oauth" -> requiresOAuth = value.ifBlank { null }

                "requires_cli" -> requiresCli.addAll(parseFrontmatterList(value))

                "platforms" -> platforms.addAll(parseFrontmatterList(value))
            }
        }

        val requirements = SkillRequirements(
            platforms = platforms,
            requiresCli = requiresCli,
            requiresOAuth = requiresOAuth,
        )

        val contentHash = skillContentDigest(body)
        if (declaredContentHash != null && declaredContentHash != contentHash) {
            logger.warn { "Skill '$name' content hash mismatch; quarantining it" }
            lifecycle = SkillLifecycle.QUARANTINED
        }
        val contract =
            SkillContract(
                lifecycle = lifecycle,
                triggers = triggers,
                antiTriggers = antiTriggers,
                requiredTools = requiredTools,
                requiredSkills = requiredSkills,
                evalSuite = evalSuite,
                provenance = provenance,
                version = version,
                owner = owner,
                contentHash = contentHash,
                reviewedContentHash = reviewedContentHash,
                reviewNote = reviewNote,
                reviewedAt = reviewedAt,
                validationRequired = validationRequired,
                revisionId = revisionId,
                evaluatedRunId = evaluatedRunId,
                reviewedRevisionHash = reviewedRevisionHash,
            )

        return SkillEntry(
            name = name,
            description = description,
            content = body,
            source = source,
            requirements = requirements,
            contract = contract,
        )
    }

    private fun withRevision(skill: SkillEntry): SkillEntry {
        val store = SkillGovernanceStore(fs, skillsDirectory)
        val lifecycle = if (skill.contract.lifecycle == SkillLifecycle.ACTIVE && !store.isExecutable(skill)) SkillLifecycle.QUARANTINED else skill.contract.lifecycle
        return skill.copy(contract = skill.contract.copy(lifecycle = lifecycle, revisionHash = store.revisionHash(skill)))
    }

    private fun parseFrontmatterList(value: String): List<String> =
        value
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { item -> item.trim().trim('"', '\'') }
            .filter { item -> item.isNotEmpty() }

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
}

@kotlinx.serialization.Serializable
data class SkillEntry(
    val name: String,
    val description: String = "",
    val content: String,
    val source: SkillSource = SkillSource.CUSTOM,
    val requirements: SkillRequirements = SkillRequirements(),
    val contract: SkillContract = SkillContract(),
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
    val contract: SkillContract = SkillContract(),
)

/**
 * Declares what a skill needs to run.
 */
@kotlinx.serialization.Serializable
data class SkillRequirements(
    val platforms: List<String> = emptyList(),
    val requiresCli: List<String> = emptyList(),
    val requiresOAuth: String? = null,
)

@kotlinx.serialization.Serializable
enum class SkillSource {
    BUNDLED,
    OPTIONAL,
    CUSTOM,
}

internal fun skillContentDigest(content: String): String = content.trim().encodeUtf8().sha256().hex()

internal fun isValidSkillSlug(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))

internal fun normalizeSkillBody(content: String): String {
    val trimmed = content.trim()
    if (!trimmed.startsWith("---")) return trimmed
    val frontmatterEnd = frontmatterEndIndex(trimmed)
    return if (frontmatterEnd < 0) trimmed else trimmed.substring(frontmatterEnd + 3).trim()
}

private fun frontmatterEndIndex(content: String): Int {
    var lineStart = content.indexOf('\n').let { index -> if (index < 0) return -1 else index + 1 }
    while (lineStart < content.length) {
        val nextLine = content.indexOf('\n', startIndex = lineStart)
        val lineEnd = if (nextLine < 0) content.length else nextLine
        if (content.substring(lineStart, lineEnd).trim() == "---") return lineStart
        lineStart = lineEnd + 1
    }
    return -1
}
