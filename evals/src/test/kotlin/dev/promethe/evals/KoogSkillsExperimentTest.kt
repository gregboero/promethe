package dev.promethe.evals

import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.discovery.SkillCollisionPrecedence
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.promethe.core.SkillLoader
import okio.FileSystem
import okio.Path.Companion.toPath

class KoogSkillsExperimentTest {
    @Test
    fun `ten skill catalog preserves local quarantine while comparing metadata context`() =
        runBlocking {
            val root = createTempDirectory("promethe-ten-skills")
            try {
                for (index in 1..10) {
                    val directory = Files.createDirectories(root.resolve("skill-$index"))
                    val state = if (index == 10) "QUARANTINED" else "ACTIVE"
                    Files.writeString(directory.resolve("SKILL.md"), "---\nname: skill-$index\ndescription: Procedure $index\nlifecycle: $state\n---\n" + "Procedure body $index. ".repeat(200))
                }
                val local = SkillLoader(FileSystem.SYSTEM, root.toString().toPath())
                val summaries = local.listSummaries()
                val koog = discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(root.toString()), maxDepth = 1, maxDirectories = 11)
                assertEquals(10, koog.size)
                assertEquals(9, local.listExecutableSkills().size)
                assertTrue(koog.any { it.name == "skill-10" }, "Upstream discovery does not enforce the local lifecycle")
                val localContext = summaries.joinToString("\n") { "${it.name}: ${it.description}" }
                val koogContext = generateSkillsPrompt(koog, SkillsPromptFormat.XML, includeLocation = false)
                val report = buildJsonObject {
                    put("experiment", "ten-skills-local-vs-koog")
                    put("localCatalogChars", localContext.length)
                    put("koogCatalogChars", koogContext.length)
                    put("koogDiscovered", koog.size)
                    put("localExecutable", 9)
                    put("decision", "Keep the local loader: both support metadata context, and Koog discovery alone includes quarantined skills")
                    put("limits", "Serialization differs; character counts are not tokenizer measurements or model-quality scores")
                }
                val output = Path.of(System.getProperty("promethe.eval.reportDir"), "koog-skills-comparison.json")
                Files.createDirectories(output.parent)
                Files.writeString(output, report.toString())
                Unit
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `bounded discovery reduces prompt context and has explicit collision policy`() =
        runBlocking {
            val root = createTempDirectory("promethe-koog-skills")
            try {
                val first = Files.createDirectories(root.resolve("first"))
                val second = Files.createDirectories(root.resolve("second"))
                val body = "PRIVATE_BODY_SENTINEL " + "Detailed procedure. ".repeat(500)
                Files.writeString(first.resolve("SKILL.md"), "---\nname: fixture\ndescription: First description\n---\n$body")
                Files.writeString(second.resolve("SKILL.md"), "---\nname: fixture\ndescription: Second description\n---\n$body")
                val warnings = mutableListOf<String>()
                val skills = discoverSkills(
                    JVMFileSystemProvider.ReadOnly,
                    listOf(first.toString(), second.toString()),
                    maxDepth = 0,
                    maxDirectories = 2,
                    precedenceRule = SkillCollisionPrecedence.FIRST_FOUND,
                    warningLogger = warnings::add,
                )
                assertEquals(1, skills.size)
                assertEquals("First description", skills.single().description)
                assertTrue(warnings.isNotEmpty())
                val prompt = generateSkillsPrompt(skills, SkillsPromptFormat.XML)
                assertFalse("PRIVATE_BODY_SENTINEL" in prompt)
                assertTrue(prompt.length < body.length / 4)
                val bounded = discoverSkills(
                    JVMFileSystemProvider.ReadOnly,
                    listOf(first.toString(), second.toString()),
                    maxDepth = 0,
                    maxDirectories = 1,
                )
                assertEquals(1, bounded.size)
                val report = buildJsonObject {
                    put("experiment", "koog-skills-metadata-context")
                    put("version", "1.2.0-beta")
                    put("fixtureBodyChars", body.length)
                    put("catalogChars", prompt.length)
                    put("bodyAbsentFromPrompt", true)
                    put("limits", "Discovery reads files; context reduction is not lazy IO or model quality evidence")
                }
                val output = Path.of(System.getProperty("promethe.eval.reportDir"), "koog-skills-experiment.json")
                Files.createDirectories(output.parent)
                Files.writeString(output, report.toString())
                Unit
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `missing description is rejected without promoting an incomplete skill`() =
        runBlocking {
            val root = createTempDirectory("promethe-koog-invalid-skill")
            try {
                Files.writeString(root.resolve("SKILL.md"), "---\nname: incomplete\n---\nBody")
                val warnings = mutableListOf<String>()
                val skills = discoverSkills(
                    JVMFileSystemProvider.ReadOnly,
                    listOf(root.toString()),
                    maxDepth = 0,
                    maxDirectories = 1,
                    warningLogger = warnings::add,
                )
                assertTrue(skills.isEmpty())
                assertTrue(warnings.isNotEmpty())
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
