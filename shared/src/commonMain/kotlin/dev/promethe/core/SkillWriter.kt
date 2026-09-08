package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.buffer
import dev.promethe.api.*

internal object SkillMutationLock {
    val mutex = Mutex()
}

class SkillWriter(
    private val fs: FileSystem,
    private val skillsDirectory: Path,
) {
    private val logger = Log.create("SkillWriter")
    val governance = SkillGovernanceStore(fs, skillsDirectory)

    /**
     * Writes a skill in standard SKILL.md format: skills/<name>/SKILL.md
     * with a versioned lifecycle contract and content hash in frontmatter.
     *
     * Returns the path of the written file, or null if it already exists (dedup).
     */
    suspend fun write(skill: SkillEntry): Path? =
        withContext(ioDispatcher) {
            SkillMutationLock.mutex.withLock {
                if (!isValidSkillSlug(skill.name)) {
                    logger.warn { "Rejected invalid skill name '${skill.name}'" }
                    return@withLock null
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
                    return@withLock null
                }

                // Create skill directory
                if (!fs.exists(skillDir)) {
                    fs.createDirectories(skillDir)
                }

                val managed = skill.copy(contract = skill.contract.copy(lifecycle = SkillLifecycle.DRAFT, validationRequired = true, revisionId = newSkillRevisionId(), evaluatedRunId = null, reviewedRevisionHash = null))
                val document = renderSkill(managed)
                governance.archive(managed, document)
                if (writeAtomically(targetPath, document)) {
                    logger.info { "New skill written: $targetPath" }
                    targetPath
                } else {
                    null
                }
            }
        }

    /** Atomically replaces an existing standard or legacy skill file. */
    suspend fun update(
        skill: SkillEntry,
        expectedContentHash: String? = null,
        expectedRevisionHash: String? = null,
    ): Path? =
        withContext(ioDispatcher) {
            SkillMutationLock.mutex.withLock {
                val slug = skill.slug.ifBlank { skill.name }
                if (!isValidSkillSlug(slug)) {
                    logger.warn { "Rejected invalid skill slug '$slug'" }
                    return@withLock null
                }
                val directoryPath = skillsDirectory / slug / "SKILL.md"
                val legacyPath = skillsDirectory / "$slug.md"
                val targetPath =
                    when {
                        fs.exists(directoryPath) -> directoryPath
                        fs.exists(legacyPath) -> legacyPath
                        else -> return@withLock null
                    }
                val raw = fs.source(targetPath).buffer().use { it.readUtf8() }
                val parsed = SkillLoader(fs, skillsDirectory).parseSkillMd(slug, raw).copy(slug = slug)
                val current = if (parsed.contract.lifecycle == SkillLifecycle.ACTIVE && !governance.isExecutable(parsed)) parsed.copy(contract = parsed.contract.copy(lifecycle = SkillLifecycle.QUARANTINED)) else parsed
                if (expectedContentHash != null && skillContentDigest(current.content) != expectedContentHash) return@withLock null
                if (expectedRevisionHash != null && governance.revisionHash(current) != expectedRevisionHash) return@withLock null
                val materialChanged = governance.revisionHash(current) != governance.revisionHash(skill.copy(contract = skill.contract.copy(revisionId = current.contract.revisionId)))
                val managed = if (materialChanged || !governance.isManaged(current)) {
                    skill.copy(
                        contract = skill.contract.copy(
                            lifecycle = SkillLifecycle.QUARANTINED,
                            validationRequired = true,
                            revisionId = newSkillRevisionId(),
                            evaluatedRunId = null,
                            reviewedRevisionHash = null,
                            reviewedContentHash = null,
                            reviewNote = null,
                            reviewedAt = null,
                        ),
                    )
                } else {
                    skill.copy(contract = skill.contract.copy(validationRequired = true, revisionId = current.contract.revisionId))
                }
                if (managed.contract.lifecycle in setOf(SkillLifecycle.CANDIDATE, SkillLifecycle.ACTIVE)) {
                    if (!current.contract.lifecycle.canTransitionTo(managed.contract.lifecycle)) return@withLock null
                    val run = runCatching { governance.validRun(managed) }.getOrNull() ?: return@withLock null
                    if (managed.contract.evaluatedRunId != run.id || managed.contract.reviewedRevisionHash != governance.revisionHash(managed) || managed.contract.reviewNote.isNullOrBlank()) return@withLock null
                }
                governance.archive(current, raw)
                val rendered = renderSkill(managed)
                governance.archive(managed, rendered)
                if (writeAtomically(targetPath, rendered)) {
                    logger.info { "Skill updated: $targetPath" }
                    targetPath
                } else {
                    null
                }
            }
        }

    suspend fun configureEvaluations(
        slug: String,
        expectedRevisionHash: String,
        suites: List<SkillEvaluationSuite>,
    ): SkillEntry =
        withContext(ioDispatcher) {
            SkillMutationLock.mutex.withLock {
                validateSuites(suites)
                val (path, current, raw) = readCurrent(slug)
                check(governance.revisionHash(current) == expectedRevisionHash) { "Skill changed; reload before configuring evaluations" }
                governance.archive(current, raw)
                governance.saveSuites(slug, suites)
                val updated = current.copy(
                    contract = current.contract.copy(
                        lifecycle = SkillLifecycle.QUARANTINED,
                        evalSuite = suites.map { it.id },
                        validationRequired = true,
                        revisionId = newSkillRevisionId(),
                        evaluatedRunId = null,
                        reviewedRevisionHash = null,
                        reviewedContentHash = null,
                        reviewNote = null,
                        reviewedAt = null,
                    ),
                )
                val rendered = renderSkill(updated)
                governance.archive(updated, rendered)
                check(writeAtomically(path, rendered)) { "Could not save evaluation configuration" }
                updated.copy(contract = updated.contract.copy(revisionHash = governance.revisionHash(updated)))
            }
        }

    suspend fun restore(
        slug: String,
        expectedRevisionHash: String,
        versionId: String,
    ): SkillEntry =
        withContext(ioDispatcher) {
            SkillMutationLock.mutex.withLock {
                val (path, current, raw) = readCurrent(slug)
                check(governance.revisionHash(current) == expectedRevisionHash) { "Skill changed; reload before restoring" }
                val version = governance.version(slug, versionId)
                val previous = SkillLoader(fs, skillsDirectory).parseSkillMd(slug, version.document).copy(slug = slug)
                governance.archive(current, raw)
                if (version.suites.isNotEmpty()) governance.saveSuites(slug, version.suites)
                val restored = previous.copy(
                    contract = previous.contract.copy(
                        lifecycle = SkillLifecycle.QUARANTINED,
                        validationRequired = true,
                        revisionId = newSkillRevisionId(),
                        evaluatedRunId = null,
                        reviewedRevisionHash = null,
                        reviewedContentHash = null,
                        reviewNote = null,
                        reviewedAt = null,
                    ),
                )
                val rendered = renderSkill(restored)
                governance.archive(restored, rendered)
                check(writeAtomically(path, rendered)) { "Could not restore skill" }
                restored.copy(contract = restored.contract.copy(revisionHash = governance.revisionHash(restored)))
            }
        }

    private fun readCurrent(slug: String): Triple<Path, SkillEntry, String> {
        require(isValidSkillSlug(slug))
        val path = listOf(skillsDirectory / slug / "SKILL.md", skillsDirectory / "$slug.md").firstOrNull { fs.exists(it) } ?: error("Skill not found")
        val raw = fs.source(path).buffer().use { it.readUtf8() }
        return Triple(path, SkillLoader(fs, skillsDirectory).parseSkillMd(slug, raw).copy(slug = slug), raw)
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
            appendLine("validation_required: ${contract.validationRequired}")
            contract.revisionId?.let { appendLine("revision_id: ${frontmatterScalar(it)}") }
            contract.evaluatedRunId?.let { appendLine("evaluated_run_id: ${frontmatterScalar(it)}") }
            contract.reviewedRevisionHash?.let { appendLine("reviewed_revision_hash: ${frontmatterScalar(it)}") }
            if (contract.reviewedContentHash == skillContentDigest(content)) {
                appendLine("reviewed_content_hash: ${contract.reviewedContentHash}")
                contract.reviewNote?.let { appendLine("review_note: ${frontmatterScalar(it)}") }
                contract.reviewedAt?.let { appendLine("reviewed_at: ${frontmatterScalar(it)}") }
            }
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
