package dev.promethe.core

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillCuratorTest {
    private lateinit var fs: FileSystem
    private lateinit var basePath: Path
    private lateinit var skillsPath: Path

    @BeforeTest
    fun setup() {
        fs = getFileSystem()
        basePath = getProfileDirectoryPath(AgentConfig()) / "curator_test"
        skillsPath = basePath / "skills"
        fs.createDirectories(skillsPath)
    }

    @AfterTest
    fun teardown() {
        if (fs.exists(basePath)) fs.deleteRecursively(basePath)
    }

    @Test
    fun `curation quarantines proposals without deleting or merging skills`() =
        runTest {
            writeSkill("low-quality", "old vague instructions")
            writeSkill("kotlin-guide", "Kotlin project setup, Kotlin compiler and Kotlin build instructions")
            writeSkill("kotlin-guide-copy", "Kotlin project setup, Kotlin compiler and Kotlin build instructions")

            val loader = SkillLoader(fs, skillsPath)
            val writer = SkillWriter(fs, skillsPath)
            val curator = SkillCurator(loader, writer, fixedScoreLlm(), AgentConfig())

            val report = curator.curate()

            assertEquals(3, report.total)
            assertTrue(report.merged.isEmpty())
            assertTrue(report.pruned.isEmpty())
            assertTrue(report.proposals.any { it.action == CurationAction.REVIEW_LOW_QUALITY })
            assertTrue(report.proposals.any { it.action == CurationAction.REVIEW_DUPLICATE })
            assertTrue(report.proposals.all { it.quarantined })
            assertEquals(3, loader.listSkills().size)
            assertTrue(fs.exists(skillsPath / "low-quality.md"))
            assertTrue(fs.exists(skillsPath / "kotlin-guide.md"))
            assertTrue(fs.exists(skillsPath / "kotlin-guide-copy.md"))
        }

    private fun writeSkill(
        name: String,
        content: String,
    ) {
        val sink = fs.sink(skillsPath / "$name.md").buffer()
        sink.writeUtf8(content)
        sink.close()
    }

    private fun fixedScoreLlm() =
        object : KoogLlmAdapter(AgentConfig()) {
            override suspend fun complete(
                systemPrompt: String,
                messages: List<Pair<String, String>>,
                model: String,
                temperature: Double,
            ): LlmResponse = LlmResponse("1", 1, 1, model, "test")
        }
}
