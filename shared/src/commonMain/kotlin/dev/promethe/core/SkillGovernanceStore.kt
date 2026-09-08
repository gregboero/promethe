package dev.promethe.core

import dev.promethe.api.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer

@Serializable
internal data class SkillVersionRecord(
    val id: String,
    val revisionHash: String,
    val capturedAt: Long,
    val document: String,
    val suites: List<SkillEvaluationSuite>,
)

/** Local owner storage. Immutable snapshots/results are separate from agent-written SKILL.md. */
class SkillGovernanceStore(
    private val fs: FileSystem,
    private val root: Path,
) {
    private val json = Json { encodeDefaults = true }

    private fun directory(slug: String): Path {
        require(isValidSkillSlug(slug)) { "Invalid skill slug" }
        return root / ".skillops" / slug
    }

    internal fun slug(skill: SkillEntry) = skill.slug.ifBlank { skill.name }

    fun suites(slug: String): List<SkillEvaluationSuite> = readOrNull<List<SkillEvaluationSuite>>(directory(slug) / "suites.json") ?: emptyList()

    internal fun saveSuites(
        slug: String,
        suites: List<SkillEvaluationSuite>,
    ) {
        validateSuites(suites)
        write(directory(slug) / "suites.json", suites)
    }

    fun revisionHash(skill: SkillEntry): String {
        val canonical = skill.copy(
            slug = slug(skill),
            content = normalizeSkillBody(skill.content),
            contract = skill.contract.copy(
                lifecycle = SkillLifecycle.DRAFT,
                contentHash = null,
                revisionHash = null,
                reviewedContentHash = null,
                reviewNote = null,
                reviewedAt = null,
                evaluatedRunId = null,
                reviewedRevisionHash = null,
            ),
        )
        val declarations = skill.contract.evalSuite.map { id -> suites(slug(skill)).find { it.id == id } }
        return skillContentDigest(json.encodeToString(canonical) + "\n" + json.encodeToString(declarations))
    }

    fun isManaged(skill: SkillEntry): Boolean = skill.contract.validationRequired || fs.exists(directory(slug(skill)))

    fun latestRun(slug: String): SkillEvaluationRun? = readOrNull<SkillEvaluationRun>(directory(slug) / "latest.json")

    fun validRun(skill: SkillEntry): SkillEvaluationRun? {
        val run = latestRun(slug(skill)) ?: return null
        if (run.status != SkillEvaluationStatus.PASSED || run.revisionHash != revisionHash(skill)) return null
        val declared = declaredSuites(skill)
        val expected = declared.flatMap { suite -> suite.cases.map { suite.id to it.id } }
        if (expected.isEmpty() || run.results.map { it.suiteId to it.caseId } != expected || run.results.any { it.status != SkillEvaluationStatus.PASSED }) return null
        return run
    }

    fun isExecutable(skill: SkillEntry): Boolean =
        try {
            !isManaged(skill) || (
                skill.contract.validationRequired &&
                    skill.contract.reviewedRevisionHash == revisionHash(skill) && !skill.contract.reviewNote.isNullOrBlank() &&
                    validRun(skill)?.id?.let { it == skill.contract.evaluatedRunId } == true
            )
        } catch (_: Exception) {
            false
        }

    fun declaredSuites(skill: SkillEntry): List<SkillEvaluationSuite> {
        val ids = skill.contract.evalSuite
        require(ids.isNotEmpty() && ids.size <= 8 && ids.distinct().size == ids.size) { "Declare at least one distinct evaluation suite" }
        val available = suites(slug(skill))
        return ids.map { id -> requireNotNull(available.find { it.id == id }) { "Missing evaluation suite: $id" } }.also(::validateSuites)
    }

    internal fun archive(
        skill: SkillEntry,
        document: String,
    ) {
        val id = skillContentDigest(document)
        val path = directory(slug(skill)) / "versions" / "$id.json"
        if (!fs.exists(path)) write(path, SkillVersionRecord(id, revisionHash(skill), Clock.System.now().toEpochMilliseconds(), document, suites(slug(skill))))
    }

    internal fun version(
        slug: String,
        id: String,
    ): SkillVersionRecord {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid version id" }
        val record = requireNotNull(readOrNull<SkillVersionRecord>(directory(slug) / "versions" / "$id.json")) { "Unknown version" }
        check(skillContentDigest(record.document) == id) { "Corrupt version snapshot" }
        return record
    }

    internal fun beginRun(
        slug: String,
        run: SkillEvaluationRun,
    ) {
        write(directory(slug) / "latest.json", run)
    }

    internal fun saveRun(
        slug: String,
        run: SkillEvaluationRun,
    ) {
        write(directory(slug) / "runs" / "${run.id}.json", run)
        write(directory(slug) / "latest.json", run)
    }

    fun state(skill: SkillEntry): SkillValidationState {
        val slug = slug(skill)
        val versionsPath = directory(slug) / "versions"
        val versions = if (!fs.exists(versionsPath)) {
            emptyList()
        } else {
            fs.list(versionsPath).filter { it.name.endsWith(".json") }.map { path ->
                val record = version(slug, path.name.removeSuffix(".json"))
                val entry = SkillLoader(fs, root).parseSkillMd(slug, record.document)
                SkillVersionDto(record.id, record.revisionHash, record.capturedAt, entry.content, entry.contract.lifecycle)
            }.sortedByDescending { it.capturedAt }
        }
        val runsPath = directory(slug) / "runs"
        val runs = if (!fs.exists(runsPath)) emptyList() else fs.list(runsPath).filter { it.name.endsWith(".json") }.mapNotNull { readOrNull<SkillEvaluationRun>(it) }.sortedByDescending { it.startedAt }
        return SkillValidationState(revisionHash(skill), suites(slug), latestRun(slug), runCatching { validRun(skill) != null }.getOrDefault(false), versions, runs)
    }

    private inline fun <reified T> readOrNull(path: Path): T? =
        if (!fs.exists(path)) {
            null
        } else {
            require((fs.metadata(path).size ?: 0) <= 4 * 1024 * 1024) { "Skill evidence exceeds size limit" }
            json.decodeFromString<T>(fs.source(path).buffer().use { it.readUtf8() })
        }

    private inline fun <reified T> write(
        path: Path,
        value: T,
    ) {
        val encoded = json.encodeToString(value)
        require(encoded.length <= 4 * 1024 * 1024) { "Skill evidence exceeds size limit" }
        fs.createDirectories(requireNotNull(path.parent))
        val temp = path.parent!! / "${path.name}.${newSkillRevisionId()}.tmp"
        try {
            fs.sink(temp).buffer().use { it.writeUtf8(encoded) }
            fs.atomicMove(temp, path)
        } finally {
            if (fs.exists(temp)) fs.delete(temp)
        }
    }
}

internal fun newSkillRevisionId() = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun validateSuites(suites: List<SkillEvaluationSuite>) {
    require(suites.size in 1..8 && suites.map { it.id }.distinct().size == suites.size) { "Provide 1 to 8 distinct suites" }
    require(suites.sumOf { it.cases.size } <= 20) { "At most 20 cases per evaluation" }
    for (suite in suites) {
        require(isValidSkillSlug(suite.id)) { "Invalid suite id" }
        require(suite.cases.size in 2..20 && suite.cases.map { it.id }.distinct().size == suite.cases.size) { "Each suite needs at least two distinct cases" }
        require(suite.cases.map { it.input.trim() }.distinct().size == suite.cases.size) { "Cases must use different inputs" }
        for (case in suite.cases) {
            require(isValidSkillSlug(case.id)) { "Invalid case id" }
            require(case.input.isNotBlank() && case.input.length <= 16_384 && case.expectedOutput.isNotBlank() && case.expectedOutput.length <= 16_384) { "Case input and expected output must contain 1 to 16384 characters" }
        }
    }
}
