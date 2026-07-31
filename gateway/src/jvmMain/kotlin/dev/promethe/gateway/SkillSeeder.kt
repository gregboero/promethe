package dev.promethe.gateway

import okio.FileSystem
import okio.Path
import okio.buffer

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Seeds the skills directory with system skill files on first launch.
 *
 * Skills are bundled as resources in `bundled-skills/<name>/SKILL.md`.
 * A `bundled-skills/manifest.txt` lists all bundled skill names (one per line).
 *
 * Existing skills are NEVER overwritten (user may have edited them).
 */
object SkillSeeder {
    private const val RESOURCE_ROOT = "bundled-skills"
    private const val MANIFEST = "$RESOURCE_ROOT/manifest.txt"

    /** Names loaded lazily from the manifest resource. */
    private val systemSkillNames: List<String> by lazy {
        val stream = SkillSeeder::class.java.classLoader.getResourceAsStream(MANIFEST)
        if (stream == null) {
            logger.warn { "SkillSeeder: manifest not found at $MANIFEST" }
            return@lazy emptyList()
        }
        stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        }
    }

    /**
     * Seed skills from bundled resources into the skills directory.
     * Only creates skills that don't already exist on disk.
     */
    fun seed(
        skillsDir: Path,
        fs: FileSystem,
    ) {
        if (!fs.exists(skillsDir)) {
            fs.createDirectories(skillsDir)
        }

        val names = systemSkillNames
        if (names.isEmpty()) {
            logger.warn { "SkillSeeder: no skills in manifest" }
            return
        }

        var created = 0
        for (name in names) {
            val dir = skillsDir / name
            val file = dir / "SKILL.md"
            if (fs.exists(file)) continue

            val resourcePath = "$RESOURCE_ROOT/$name/SKILL.md"
            val content = loadResource(resourcePath)
            if (content == null) {
                logger.warn { "SkillSeeder: resource not found: $resourcePath" }
                continue
            }

            if (!fs.exists(dir)) {
                fs.createDirectories(dir)
            }
            fs.sink(file).buffer().use { it.writeUtf8(content) }
            created++
        }

        logger.info { "SkillSeeder: $created/${names.size} skills seeded (${names.size - created} already existed)" }
    }

    /** Check if a skill name is a system (bundled) skill. */
    fun isSystemSkill(name: String): Boolean = name in systemSkillNames

    /** Get the default content for a system skill (from bundled resources). */
    fun getDefaultContent(name: String): String? {
        if (name !in systemSkillNames) return null
        return loadResource("$RESOURCE_ROOT/$name/SKILL.md")
    }

    private fun loadResource(path: String): String? =
        SkillSeeder::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
}
