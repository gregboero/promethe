package dev.promethe.core

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/**
 * JVM-specific tests for SkillLoader focusing on caching behaviour,
 * cache invalidation, and edge cases not covered by the commonTest suite.
 * Uses okio FakeFileSystem for hermetic, deterministic testing.
 */
class SkillLoaderCacheTest {
    private lateinit var fs: FakeFileSystem
    private val skillsDir = "/skills".toPath()

    @BeforeTest
    fun setup() {
        fs = FakeFileSystem()
        fs.createDirectories(skillsDir)
    }

    @AfterTest
    fun tearDown() {
        fs.checkNoOpenFiles()
    }

    // ── helpers ──

    private fun writeSkill(
        name: String,
        content: String,
    ) {
        fs.sink(skillsDir / "$name.md").buffer().use { it.writeUtf8(content) }
    }

    private fun createLoader(): SkillLoader = SkillLoader(fs, skillsDir)

    // ── 1. listSkills returns non-empty list when skills exist ──

    @Test
    fun listSkills_returnsAllMdFiles() =
        runTest {
            writeSkill("kotlin-patterns", "# Kotlin Patterns\nCoroutines, flows, sealed classes")
            writeSkill("debugging", "# Debugging\nBreakpoints, logging, profiling")

            val loader = createLoader()
            val skills = loader.listSkills()

            assertEquals(2, skills.size)
            val names = skills.map { it.name }.toSet()
            assertTrue("kotlin-patterns" in names)
            assertTrue("debugging" in names)
        }

    @Test
    fun listSkills_ignoresNonMdFiles() =
        runTest {
            writeSkill("real-skill", "# Skill content here")
            // Write a non-.md file
            fs.sink(skillsDir / "readme.txt").buffer().use { it.writeUtf8("not a skill") }

            val loader = createLoader()
            val skills = loader.listSkills()

            assertEquals(1, skills.size)
            assertEquals("real-skill", skills.first().name)
        }

    // ── 2. Direct edits invalidate previously observed content ──

    @Test
    fun listSkills_observesSameSizeEditsWithoutInvalidation() =
        runTest {
            writeSkill("cached-skill", "# Cached\nSome skill content for caching")

            val loader = createLoader()
            val first = loader.listSkills()
            writeSkill("cached-skill", "# Cached\nMore skill content for caching")
            val second = loader.listSkills()

            assertTrue(second.single().content.contains("More skill content"))
            assertNotEquals(first.single().contract.contentHash, second.single().contract.contentHash)
        }

    // ── 3. invalidateCache forces re-read ──

    @Test
    fun invalidateCache_forcesReRead() =
        runTest {
            writeSkill("original", "# Original\nOriginal content")

            val loader = createLoader()
            val before = loader.listSkills()
            assertEquals(1, before.size)

            // Add another skill file
            writeSkill("added", "# Added\nNew skill added after initial load")

            // Explicitly invalidate to test the API contract
            loader.invalidateCache()
            val after = loader.listSkills()

            assertEquals(2, after.size)
            val names = after.map { it.name }.toSet()
            assertTrue("original" in names)
            assertTrue("added" in names)
        }

    @Test
    fun invalidateCache_returnsFreshInstance() =
        runTest {
            writeSkill("skill-a", "# A\nContent A")

            val loader = createLoader()
            val first = loader.listSkills()

            loader.invalidateCache()
            val second = loader.listSkills()

            // After invalidation, should be a new list even if content is the same
            assertNotSame(first, second, "After invalidation a new list should be created")
            assertEquals(first, second, "Content should still match")
        }

    // ── 4. findRelevantSkills: keyword match ──

    @Test
    fun findRelevantSkills_findsMatchingSkills() =
        runTest {
            writeSkill("kotlin-coroutines", "# Kotlin Coroutines\nLearn about coroutines, suspend functions, flows")
            writeSkill("python-basics", "# Python Basics\nVariables, loops, functions in Python")
            writeSkill("debugging-guide", "# Debugging\nBreakpoints, logging, stack traces")

            val loader = createLoader()
            val results = loader.findRelevantSkills("kotlin coroutines")

            assertTrue(results.isNotEmpty(), "Should find at least one matching skill")
            assertEquals("kotlin-coroutines", results.first().name)
        }

    @Test
    fun findRelevantSkills_respectsMaxResults() =
        runTest {
            writeSkill("skill-1", "# Kotlin patterns and coroutines flows channels")
            writeSkill("skill-2", "# Kotlin advanced coroutines and dispatchers")
            writeSkill("skill-3", "# Kotlin basic syntax and coroutines intro")
            writeSkill("skill-4", "# Kotlin coroutines testing strategies")

            val loader = createLoader()
            val results = loader.findRelevantSkills("kotlin coroutines", maxResults = 2)

            assertTrue(results.size <= 2, "Should respect maxResults limit")
        }

    // ── 5. findRelevantSkills: no match ──

    @Test
    fun findRelevantSkills_returnsEmptyForNoMatch() =
        runTest {
            writeSkill("kotlin-skills", "# Kotlin\nCoroutines, sealed classes, data classes")

            val loader = createLoader()
            val results = loader.findRelevantSkills("xyzzythingamajig")

            assertTrue(results.isEmpty(), "Should return empty list for non-matching keywords")
        }

    @Test
    fun findRelevantSkills_returnsEmptyForShortKeywords() =
        runTest {
            // Keywords ≤ 3 chars are filtered out, so a query of only short words returns nothing
            writeSkill("any-skill", "# Any\nSome content here with words")

            val loader = createLoader()
            val results = loader.findRelevantSkills("a is to the")

            assertTrue(results.isEmpty(), "Short keywords (≤3 chars) should be ignored")
        }

    @Test
    fun findRelevantSkills_returnsEmptyForEmptyQuery() =
        runTest {
            writeSkill("any-skill", "# Any\nContent")

            val loader = createLoader()
            val results = loader.findRelevantSkills("")

            assertTrue(results.isEmpty(), "Empty query should return empty results")
        }

    // ── 6. Edge case: empty skills directory ──

    @Test
    fun listSkills_emptyDirectory_returnsEmptyList() =
        runTest {
            // skillsDir exists but has no .md files
            val loader = createLoader()
            val skills = loader.listSkills()

            assertTrue(skills.isEmpty(), "Empty directory should yield empty skill list")
        }

    @Test
    fun listSkills_nonExistentDirectory_returnsEmptyList() =
        runTest {
            val loader = SkillLoader(fs, "/does/not/exist".toPath())
            val skills = loader.listSkills()

            assertTrue(skills.isEmpty(), "Non-existent directory should yield empty list")
        }

    @Test
    fun findRelevantSkills_emptyDirectory_returnsEmpty() =
        runTest {
            val loader = createLoader()
            val results = loader.findRelevantSkills("kotlin coroutines")

            assertTrue(results.isEmpty(), "Search on empty directory should return empty")
        }
}
