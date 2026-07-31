package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillWriterTest {
    @Test
    fun testWriteAndDedup() =
        runTest {
            val fs = getFileSystem()
            val config = AgentConfig()
            val dir = getProfileDirectoryPath(config) / "test_skills_writer"
            if (!fs.exists(dir)) fs.createDirectories(dir)

            val writer = SkillWriter(fs, dir)
            val skill = SkillEntry(name = "test_skill", content = "# Skill: Test\n## Objective\nTest\n## Steps\n1. Do thing")

            // Premier write → succès
            val path1 = writer.write(skill)
            assertNotNull(path1)
            assertTrue(fs.exists(path1))

            // Deuxième write → null (doublon)
            val path2 = writer.write(skill)
            assertNull(path2)

            // Cleanup
            fs.deleteRecursively(dir)
        }
}
