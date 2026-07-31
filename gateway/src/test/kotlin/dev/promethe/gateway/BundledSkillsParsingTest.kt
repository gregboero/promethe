package dev.promethe.gateway

import kotlin.test.*

/**
 * P0 — Verify that ALL bundled system skills parse correctly.
 *
 * Reads the same `bundled-skills/manifest.txt` resource that [SkillSeeder] uses and
 * asserts every listed skill has loadable, well-formed SKILL.md content.
 */
class BundledSkillsParsingTest {
    /** Load manifest the same way SkillSeeder does (private field, so we replicate). */
    private val manifest: List<String> by lazy {
        val stream = SkillSeeder::class.java.classLoader
            .getResourceAsStream("bundled-skills/manifest.txt")
            ?: error("manifest.txt not found on classpath")
        stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        }
    }

    // ── 1. manifest is not empty ────────────────────────────────────────

    @Test
    fun `manifest is not empty`() {
        assertTrue(manifest.size > 90, "Expected > 90 skills in manifest but got ${manifest.size}")
    }

    // ── 2. all manifest skills have SKILL_MD content ────────────────────

    @Test
    fun `all manifest skills have SKILL_MD content`() {
        val missing = manifest.filter { name ->
            SkillSeeder.getDefaultContent(name) == null
        }
        assertTrue(missing.isEmpty(), "Skills with missing SKILL.md content: $missing")
    }

    // ── 3. all skill contents have valid frontmatter ────────────────────

    @Test
    fun `all skill contents have valid frontmatter`() {
        val errors = mutableListOf<String>()
        for (name in manifest) {
            val content = SkillSeeder.getDefaultContent(name)
            if (content == null) {
                errors += "$name: content is null"
                continue
            }
            if (!content.startsWith("---")) {
                errors += "$name: does not start with '---' frontmatter delimiter"
                continue
            }
            if (!content.contains("name:")) {
                errors += "$name: missing 'name:' in frontmatter"
            }
            if (!content.contains("description:")) {
                errors += "$name: missing 'description:' in frontmatter"
            }
        }
        assertTrue(errors.isEmpty(), "Frontmatter errors:\n${errors.joinToString("\n")}")
    }

    // ── 4. isSystemSkill returns true for all manifest skills ───────────

    @Test
    fun `isSystemSkill returns true for all manifest skills`() {
        val notRecognised = manifest.filter { name ->
            !SkillSeeder.isSystemSkill(name)
        }
        assertTrue(
            notRecognised.isEmpty(),
            "Skills NOT recognised as system: $notRecognised",
        )
    }

    // ── 5. isSystemSkill returns false for unknown skill ────────────────

    @Test
    fun `isSystemSkill returns false for unknown skill`() {
        assertFalse(
            SkillSeeder.isSystemSkill("definitely-not-a-skill"),
            "Unknown skill name should not be recognised as system",
        )
    }

    // ── 6. no duplicate entries in manifest ─────────────────────────────

    @Test
    fun `no duplicate entries in manifest`() {
        val duplicates = manifest.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "Duplicate manifest entries: $duplicates")
    }
}
