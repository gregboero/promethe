package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.CurationAction
import dev.promethe.core.SkillEntry
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import dev.promethe.core.SkillCurator
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import okio.FileSystem
import okio.Path
import okio.buffer

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * REST routes for skill CRUD operations.
 *
 * Skills are stored as `.md` files on disk. These routes expose them
 * via the REST API for the UI and external clients.
 *
 * AI-powered operations (generate, evolve) go through A2A protocol,
 * NOT through these REST routes.
 */
fun Route.skillRoutes(
    skillLoader: SkillLoader,
    skillWriter: SkillWriter,
    skillCurator: SkillCurator,
    fs: FileSystem,
    skillsDir: Path,
) {
    // ── List all skills ──────────────────────────────────────────
    get("/skills") {
        try {
            val skills = skillLoader.listSkills()
            val dtos = skills.map { entry ->
                SkillDto(
                    name = entry.name,
                    description = entry.description,
                    content = entry.content,
                    preview = entry.content.lines().take(5).joinToString("\n"),
                    isSystem = SkillSeeder.isSystemSkill(entry.slug.ifBlank { entry.name }),
                )
            }
            call.respond(SkillListResponse(skills = dtos))
        } catch (e: Exception) {
            logger.error(e) { "Failed to list skills" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    // ── Get a single skill by name ──────────────────────────────
    get("/skills/{name}") {
        try {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))

            val skills = skillLoader.listSkills()
            val skill = skills.find { it.name == name || it.slug == name }
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))

            call.respond(
                SkillDto(
                    name = skill.name,
                    description = skill.description,
                    content = skill.content,
                    preview = skill.content.lines().take(5).joinToString("\n"),
                    isSystem = SkillSeeder.isSystemSkill(skill.slug.ifBlank { skill.name }),
                ),
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get skill" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    // ── Create a new skill ──────────────────────────────────────
    post("/skills") {
        try {
            val req = call.receive<CreateSkillRequest>()

            if (req.name.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill name cannot be blank"))
            }
            if (req.content.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill content cannot be blank"))
            }

            // Sanitize name: lowercase, alphanumeric + underscores only
            val safeName = req.name.lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')

            val path = skillWriter.write(SkillEntry(name = safeName, description = req.description, content = req.content))
            if (path != null) {
                skillLoader.invalidateCache()
                call.respond(
                    HttpStatusCode.Created,
                    SkillDto(
                        name = safeName,
                        description = req.description,
                        content = req.content,
                        preview = req.content.lines().take(5).joinToString("\n"),
                    ),
                )
            } else {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Skill '$safeName' already exists"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create skill" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    // ── Update an existing skill ────────────────────────────────
    put("/skills/{name}") {
        try {
            val name = call.parameters["name"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))
            val req = call.receive<UpdateSkillRequest>()

            // If empty/blank content and it is a system skill, restore default
            val targetContent = if (req.content.isBlank()) {
                if (SkillSeeder.isSystemSkill(name)) {
                    SkillSeeder.getDefaultContent(name)
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill content cannot be blank"))
                } else {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill content cannot be blank"))
                }
            } else {
                req.content
            }

            // Support both directory-based (standard) and flat-file (legacy)
            val dirPath = skillsDir / name / "SKILL.md"
            val flatPath = skillsDir / "$name.md"
            val targetPath = when {
                fs.exists(dirPath) -> dirPath
                fs.exists(flatPath) -> flatPath
                else -> return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))
            }

            // Direct overwrite
            fs.sink(targetPath).buffer().use { sink ->
                sink.writeUtf8(targetContent)
            }
            skillLoader.invalidateCache()

            call.respond(
                SkillDto(
                    name = name,
                    content = targetContent,
                    preview = targetContent.lines().take(5).joinToString("\n"),
                    isSystem = SkillSeeder.isSystemSkill(name),
                ),
            )
        } catch (e: Exception) {
            val skillName = call.parameters["name"] ?: "unknown"
            logger.error(e) { "Failed to update skill '$skillName'" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    // ── Delete a skill ──────────────────────────────────────────
    delete("/skills/{name}") {
        try {
            val name = call.parameters["name"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))

            if (SkillSeeder.isSystemSkill(name)) {
                return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot delete system skill '$name'"))
            }

            val deleted = skillLoader.deleteSkill(name)
            if (deleted) {
                call.respond(HttpStatusCode.OK, mapOf("deleted" to name))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete skill" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    // ── Curate all skills (quality pass) ────────────────────────
    post("/skills/curate") {
        try {
            val report = skillCurator.curate()
            call.respond(
                SkillCurationReport(
                    analyzed = report.total,
                    issues = report.graded
                        .filter { it.second <= 2 }
                        .map { "${it.first}: score=${it.second}" },
                    merged = report.merged.size,
                    deleted = report.pruned.size,
                    proposals =
                        report.proposals.map { proposal ->
                            SkillCurationProposalDto(
                                action =
                                    when (proposal.action) {
                                        CurationAction.REVIEW_LOW_QUALITY -> SkillCurationActionDto.REVIEW_LOW_QUALITY
                                        CurationAction.REVIEW_DUPLICATE -> SkillCurationActionDto.REVIEW_DUPLICATE
                                    },
                                skill = proposal.skill,
                                relatedSkills = proposal.relatedSkills,
                                score = proposal.score,
                                similarity = proposal.similarity,
                                rationale = proposal.rationale,
                            )
                        },
                ),
            )
        } catch (e: Exception) {
            logger.error(e) { "Curation failed" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Curation error"))
        }
    }
}
