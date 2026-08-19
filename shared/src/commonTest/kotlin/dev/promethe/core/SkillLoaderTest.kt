package dev.promethe.core

import dev.promethe.api.SkillLifecycle
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillLoaderTest {
    private lateinit var fs: FileSystem
    private lateinit var basePath: Path
    private lateinit var skillsPath: Path

    @BeforeTest
    fun setup() {
        fs = getFileSystem()
        val config = AgentConfig()
        basePath = getProfileDirectoryPath(config) / "test_sandbox"
        skillsPath = basePath / "test_skills"
        if (!fs.exists(skillsPath)) {
            fs.createDirectories(skillsPath)
        }
    }

    @AfterTest
    fun teardown() {
        if (fs.exists(basePath)) {
            try {
                fs.deleteRecursively(basePath)
            } catch (_: Exception) {
            }
        }
    }

    private fun writeSkill(
        name: String,
        content: String,
    ) {
        val file = skillsPath / "$name.md"
        val sink = fs.sink(file).buffer()
        try {
            sink.writeUtf8(content)
        } finally {
            try {
                sink.close()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun testListSkills() =
        runTest {
            writeSkill("file_management", "Create, read, modify, and organize files in the workspace.")
            writeSkill("database_usage", "Query SQLite databases using SQLDelight matching queries.")

            val loader = SkillLoader(fs, skillsPath)
            val skills = loader.listSkills()

            assertEquals(2, skills.size)
            val names = skills.map { it.name }.toSet()
            assertTrue(names.contains("file_management"))
            assertTrue(names.contains("database_usage"))
        }

    @Test
    fun testFindRelevantSkillsHappyPath() =
        runTest {
            writeSkill("file_management", "Create, read, modify, and organize files in the workspace.")
            writeSkill("database_usage", "Query SQLite databases using SQLDelight matching queries.")
            writeSkill("git_commands", "Use git CLI to add, commit, push, and review changes.")

            val loader = SkillLoader(fs, skillsPath)

            // "files" should match file_management
            val match1 = loader.findRelevantSkills("how to write files in workspace")
            assertEquals(1, match1.size)
            assertEquals("file_management", match1[0].name)

            // "database" should match database_usage
            val match2 = loader.findRelevantSkills("sqlite database querying")
            assertEquals(1, match2.size)
            assertEquals("database_usage", match2[0].name)

            // "git" should match git_commands
            val match3 = loader.findRelevantSkills("git commit push")
            assertEquals(1, match3.size)
            assertEquals("git_commands", match3[0].name)
        }

    @Test
    fun testFindRelevantSkillsNoMatches() =
        runTest {
            writeSkill("file_management", "Create, read, modify, and organize files in the workspace.")

            val loader = SkillLoader(fs, skillsPath)
            val matches = loader.findRelevantSkills("unrelated queries without match")
            assertTrue(matches.isEmpty())
        }

    @Test
    fun testFindRelevantSkillsShortWordsIgnored() =
        runTest {
            writeSkill("file_management", "Create, read, modify, and organize files in the workspace.")

            val loader = SkillLoader(fs, skillsPath)
            // Words like "the", "and", "in" are short and should be ignored, and not cause matches.
            val matches = loader.findRelevantSkills("the and in")
            assertTrue(matches.isEmpty())
        }

    @Test
    fun `only active skills are executable`() =
        runTest {
            writeSkill(
                "active_skill",
                """
                ---
                name: active_skill
                lifecycle: ACTIVE
                ---
                Kotlin release procedure.
                """.trimIndent(),
            )
            writeSkill(
                "draft_skill",
                """
                ---
                name: draft_skill
                lifecycle: DRAFT
                ---
                Kotlin draft procedure.
                """.trimIndent(),
            )

            val loader = SkillLoader(fs, skillsPath)

            assertEquals(2, loader.listSkills().size)
            assertEquals(listOf("active_skill"), loader.listExecutableSkills().map { skill -> skill.name })
            assertEquals(listOf("active_skill"), loader.findRelevantSkills("Kotlin procedure").map { skill -> skill.name })
            assertTrue(loader.loadByNames(listOf("draft_skill")).isEmpty())
        }

    @Test
    fun `content hash mismatch quarantines skill`() =
        runTest {
            writeSkill(
                "tampered_skill",
                """
                ---
                name: tampered_skill
                lifecycle: ACTIVE
                content_hash: stale-hash
                ---
                Changed instructions.
                """.trimIndent(),
            )

            val loader = SkillLoader(fs, skillsPath)
            val skill = loader.listSkills().single()

            assertEquals(SkillLifecycle.QUARANTINED, skill.contract.lifecycle)
            assertTrue(loader.listExecutableSkills().isEmpty())
        }
}
