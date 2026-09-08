package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/**
 * Integration tests for SkillRoutes — focuses on the isSystem slug matching
 * logic that guards system skills from deletion and correctly tags them.
 *
 * Uses a real [SkillLoader]/[SkillWriter] backed by okio [FakeFileSystem]
 * so the full route → loader → filesystem chain is exercised.
 *
 * The [SkillSeeder.isSystemSkill] check reads from the real manifest resource,
 * so "clean-code" (a bundled skill) is treated as a system skill, while
 * "my-custom-skill" is not.
 */
class SkillRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var fs: FakeFileSystem
    private val skillsDir: Path = "/skills".toPath()

    @BeforeTest
    fun setUp() {
        fs = FakeFileSystem()
        fs.createDirectories(skillsDir)
    }

    @AfterTest
    fun tearDown() {
        fs.checkNoOpenFiles()
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun writeSkillDir(
        name: String,
        content: String,
    ) {
        val dir = skillsDir / name
        fs.createDirectories(dir)
        fs.sink(dir / "SKILL.md").buffer().use { it.writeUtf8(content) }
    }

    /**
     * Configures the Ktor test application with ContentNegotiation and
     * skill routes mounted at /api. Curation uses a deterministic no-op LLM.
     */
    private fun ApplicationTestBuilder.configureApp(
        skillLoader: SkillLoader,
        skillWriter: SkillWriter,
    ) {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
        }
        routing {
            route("/api/v1") {
                // We create a stub SkillCurator-free route subset by manually
                // mounting only the endpoints we test. But since skillRoutes
                // requires a SkillCurator parameter, we use a no-op proxy.
                skillRoutes(
                    skillLoader = skillLoader,
                    skillWriter = skillWriter,
                    skillCurator = stubSkillCurator(skillLoader, skillWriter),
                    fs = fs,
                    skillsDir = skillsDir,
                    evaluationSubject = dev.promethe.core.SkillEvaluationSubject { _, input -> input.uppercase() },
                    evaluatorId = "deterministic-uppercase-fixture",
                )
            }
        }
    }

    /**
     * Builds a real [SkillCurator] with a no-op LLM adapter.
     */
    private fun stubSkillCurator(
        loader: SkillLoader,
        writer: SkillWriter,
    ): dev.promethe.core.SkillCurator {
        val defaultConfig = dev.promethe.core.AgentConfig(
            modelName = "test-model",
        )
        val noOpLlm = object : dev.promethe.core.KoogLlmAdapter(defaultConfig) {
            override suspend fun complete(
                systemPrompt: String,
                messages: List<Pair<String, String>>,
                model: String,
                temperature: Double,
            ): dev.promethe.core.LlmResponse =
                dev.promethe.core.LlmResponse(
                    content = "",
                    promptTokens = 0,
                    completionTokens = 0,
                    model = "test",
                    provider = "test",
                )
        }
        return dev.promethe.core.SkillCurator(loader, writer, noOpLlm, defaultConfig)
    }

    private fun systemSkillContent() =
        """
        ---
        name: Clean Code
        description: Write clean, maintainable code
        ---
        # Clean Code
        Best practices for clean code.
        """.trimIndent()

    private fun customSkillContent() =
        """
        ---
        name: My Custom Skill
        description: A custom user skill
        ---
        # My Custom Skill
        Custom content here.
        """.trimIndent()

    // ── 1. GET /api/v1/skills returns list with correct isSystem flags ──

    @Test
    fun `GET skills returns list with correct isSystem flags`() =
        testApplication {
            writeSkillDir("clean-code", systemSkillContent())
            writeSkillDir("my-custom-skill", customSkillContent())

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.get("/api/v1/skills")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<SkillListResponse>(response.bodyAsText())
            assertEquals(2, body.skills.size)

            val system = body.skills.find { it.name == "Clean Code" }
            val custom = body.skills.find { it.name == "My Custom Skill" }
            assertNotNull(system, "Should contain system skill")
            assertNotNull(custom, "Should contain custom skill")
            assertTrue(system.isSystem, "clean-code should be isSystem=true")
            assertFalse(custom.isSystem, "my-custom-skill should be isSystem=false")
        }

    // ── 2. System skill (slug=clean-code) has isSystem = true ──

    @Test
    fun `system skill clean-code has isSystem true`() =
        testApplication {
            writeSkillDir("clean-code", systemSkillContent())

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.get("/api/v1/skills")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<SkillListResponse>(response.bodyAsText())
            val skill = body.skills.single()
            assertTrue(skill.isSystem, "clean-code slug should mark isSystem=true")
        }

    // ── 3. Custom skill (slug=my-custom-skill) has isSystem = false ──

    @Test
    fun `custom skill my-custom-skill has isSystem false`() =
        testApplication {
            writeSkillDir("my-custom-skill", customSkillContent())

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.get("/api/v1/skills")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<SkillListResponse>(response.bodyAsText())
            val skill = body.skills.single()
            assertFalse(skill.isSystem, "my-custom-skill should be isSystem=false")
        }

    // ── 4. GET /api/v1/skills/clean-code returns skill with isSystem = true ──

    @Test
    fun `GET skill by slug returns isSystem true for system skill`() =
        testApplication {
            writeSkillDir("clean-code", systemSkillContent())

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.get("/api/v1/skills/clean-code")
            assertEquals(HttpStatusCode.OK, response.status)

            val skill = json.decodeFromString<SkillDto>(response.bodyAsText())
            assertEquals("Clean Code", skill.name)
            assertTrue(skill.isSystem, "GET by slug should return isSystem=true for system skill")
        }

    // ── 5. DELETE /api/v1/skills/clean-code returns 403 Forbidden ──

    @Test
    fun `DELETE system skill returns 403 Forbidden`() =
        testApplication {
            writeSkillDir("clean-code", systemSkillContent())

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.delete("/api/v1/skills/clean-code")
            assertEquals(HttpStatusCode.Forbidden, response.status)

            val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(error.error.contains("system skill"), "Error message should mention system skill")
        }

    @Test
    fun `system skill display name cannot bypass lifecycle or deletion protection`() =
        testApplication {
            writeSkillDir("clean-code", systemSkillContent())
            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val lifecycleResponse = client.put("/api/v1/skills/Clean%20Code/lifecycle") {
                contentType(ContentType.Application.Json)
                setBody("""{"lifecycle":"DEPRECATED"}""")
            }
            val deleteResponse = client.delete("/api/v1/skills/Clean%20Code")

            assertEquals(HttpStatusCode.Forbidden, lifecycleResponse.status)
            assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
        }

    @Test
    fun `editing or restoring defaults of a system skill requires new evidence`() = testApplication {
        writeSkillDir("clean-code", systemSkillContent())
        val loader = SkillLoader(fs, skillsDir)
        val writer = SkillWriter(fs, skillsDir)
        configureApp(loader, writer)
        assertEquals(1, loader.listExecutableSkills().size)
        for (content in listOf("Modified system instructions", "")) {
            val response = client.put("/api/v1/skills/clean-code") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(UpdateSkillRequest(content, loader.listSkills().single().contract.revisionHash)))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(SkillLifecycle.QUARANTINED, json.decodeFromString<SkillDto>(response.bodyAsText()).contract.lifecycle)
            assertTrue(loader.listExecutableSkills().isEmpty())
        }
        assertTrue(writer.governance.state(loader.listSkills().single()).versions.size >= 3)
    }

    @Test
    fun `stale suite configuration and unknown rollback are rejected without modifying skill`() = testApplication {
        writeSkillDir("my-custom-skill", customSkillContent())
        val loader = SkillLoader(fs, skillsDir)
        val writer = SkillWriter(fs, skillsDir)
        configureApp(loader, writer)
        val before = loader.listSkills().single()
        val suite = SkillEvaluationSuite("fixture", listOf(SkillEvaluationCase("one", "one", "ONE"), SkillEvaluationCase("two", "two", "TWO")))
        val stale = client.put("/api/v1/skills/my-custom-skill/evaluation-suites") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ConfigureSkillEvaluationRequest("stale", listOf(suite))))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val restore = client.post("/api/v1/skills/my-custom-skill/restore") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RestoreSkillVersionRequest(before.contract.revisionHash!!, "../outside")))
        }
        assertEquals(HttpStatusCode.BadRequest, restore.status)
        assertEquals(before, loader.listSkills().single())
    }

    // ── 6. DELETE /api/v1/skills/my-custom-skill succeeds ──

    @Test
    fun `DELETE custom skill succeeds`() =
        testApplication {
            writeSkillDir("my-custom-skill", customSkillContent())

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.delete("/api/v1/skills/my-custom-skill")
            assertEquals(HttpStatusCode.OK, response.status)

            // Verify the skill is actually gone from the filesystem
            assertFalse(
                fs.exists(skillsDir / "my-custom-skill" / "SKILL.md"),
                "Skill file should be deleted from disk",
            )
        }

    // ── 7. POST /api/v1/skills creates with sanitized name ──

    @Test
    fun `POST skills creates with sanitized name`() =
        testApplication {
            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.post("/api/v1/skills") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"My Cool Skill!","description":"test desc","content":"# Content\nHello world"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)

            val created = json.decodeFromString<SkillDto>(response.bodyAsText())
            // Sanitization: lowercase, non-alnum → underscore, collapse, trim
            assertEquals("my_cool_skill", created.name, "Name should be sanitized to lowercase underscored")
            assertEquals("test desc", created.description)
            assertEquals(SkillLifecycle.DRAFT, created.contract.lifecycle)
            assertNotNull(created.contract.contentHash)
        }

    @Test
    fun `skill activation requires reviewed lifecycle transitions`() =
        testApplication {
            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)
            client.post("/api/v1/skills") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"reviewed","content":"Review this procedure"}""")
            }

            val invalid = client.put("/api/v1/skills/reviewed/lifecycle") {
                contentType(ContentType.Application.Json)
                setBody("""{"lifecycle":"ACTIVE"}""")
            }
            assertEquals(HttpStatusCode.Conflict, invalid.status)

            val revision = loader.listSkills().single().contract.revisionHash
            val configured = client.put("/api/v1/skills/reviewed/evaluation-suites") {
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevisionHash":"$revision","suites":[{"id":"acceptance","cases":[{"id":"first","input":"one","expectedOutput":"ONE"},{"id":"second","input":"two","expectedOutput":"TWO"}]}]}""")
            }
            assertEquals(HttpStatusCode.OK, configured.status)
            val beforeEval = loader.listSkills().single()
            val missingEvidence = client.put("/api/v1/skills/reviewed/lifecycle") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(UpdateSkillLifecycleRequest(SkillLifecycle.CANDIDATE, beforeEval.contract.contentHash, "reviewed", beforeEval.contract.revisionHash)))
            }
            assertEquals(HttpStatusCode.Conflict, missingEvidence.status)
            val evaluated = client.post("/api/v1/skills/reviewed/evaluations") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(EvaluateSkillRequest(beforeEval.contract.revisionHash!!)))
            }
            assertEquals(HttpStatusCode.OK, evaluated.status)
            assertEquals(SkillEvaluationStatus.PASSED, json.decodeFromString<SkillEvaluationRun>(evaluated.bodyAsText()).status)

            listOf(SkillLifecycle.QUARANTINED, SkillLifecycle.CANDIDATE, SkillLifecycle.ACTIVE).forEach { lifecycle ->
                if (lifecycle != SkillLifecycle.QUARANTINED) {
                    val missingReview = client.put("/api/v1/skills/reviewed/lifecycle") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"lifecycle":"$lifecycle"}""")
                    }
                    assertEquals(HttpStatusCode.Conflict, missingReview.status)
                    val staleReview = client.put("/api/v1/skills/reviewed/lifecycle") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"lifecycle":"$lifecycle","expectedContentHash":"stale","reviewNote":"checked"}""")
                    }
                    assertEquals(HttpStatusCode.Conflict, staleReview.status)
                }
                val hash = loader.listSkills().single().contract.contentHash
                val response = client.put("/api/v1/skills/reviewed/lifecycle") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"lifecycle":"$lifecycle","expectedContentHash":"$hash","reviewNote":"Reviewed fixture procedure","expectedRevisionHash":"${loader.listSkills().single().contract.revisionHash}"}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            assertEquals(listOf("reviewed"), loader.listExecutableSkills().map { skill -> skill.name })
            val reopened = SkillLoader(fs, skillsDir).listSkills().single()
            assertEquals(reopened.contract.contentHash, reopened.contract.reviewedContentHash)
            assertEquals("Reviewed fixture procedure", reopened.contract.reviewNote)
            assertNotNull(reopened.contract.reviewedAt)
            val edit = client.put("/api/v1/skills/reviewed") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"Changed procedure after review"}""")
            }
            assertEquals(HttpStatusCode.OK, edit.status)
            val changed = json.decodeFromString<SkillDto>(edit.bodyAsText())
            assertEquals(SkillLifecycle.QUARANTINED, changed.contract.lifecycle)
            assertEquals(null, changed.contract.reviewedContentHash)
            val validation = json.decodeFromString<SkillValidationState>(client.get("/api/v1/skills/reviewed/validation").bodyAsText())
            assertFalse(validation.canPromote)
            val version = validation.versions.first { it.lifecycle == SkillLifecycle.ACTIVE }
            val restored = client.post("/api/v1/skills/reviewed/restore") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RestoreSkillVersionRequest(changed.contract.revisionHash!!, version.id)))
            }
            assertEquals(HttpStatusCode.OK, restored.status)
            val restoredSkill = json.decodeFromString<SkillDto>(restored.bodyAsText())
            assertEquals("Review this procedure", restoredSkill.content)
            assertEquals(SkillLifecycle.QUARANTINED, restoredSkill.contract.lifecycle)
            assertNotEquals(reopened.contract.revisionHash, restoredSkill.contract.revisionHash)
        }

    @Test
    fun `POST curate returns quarantined proposals without mutating skills`() =
        testApplication {
            val duplicateContent =
                """
                ---
                name: Kotlin Build Guide
                description: Kotlin compiler and project build instructions
                ---
                # Kotlin Build Guide
                Kotlin compiler project setup and Kotlin build instructions.
                """.trimIndent()
            writeSkillDir("kotlin-guide-a", duplicateContent)
            writeSkillDir("kotlin-guide-b", duplicateContent)

            val loader = SkillLoader(fs, skillsDir)
            val writer = SkillWriter(fs, skillsDir)
            configureApp(loader, writer)

            val response = client.post("/api/v1/skills/curate")
            assertEquals(HttpStatusCode.OK, response.status)

            val report = json.decodeFromString<SkillCurationReport>(response.bodyAsText())
            assertEquals(0, report.merged)
            assertEquals(0, report.deleted)
            assertTrue(report.proposals.any { it.action == SkillCurationActionDto.REVIEW_DUPLICATE })
            assertTrue(report.proposals.all { it.status == SkillLifecycle.QUARANTINED })
            assertEquals(2, loader.listSkills().size)
        }
}
