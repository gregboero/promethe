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
            val dtos = skills.map { entry -> entry.toDto() }
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

            call.respond(skill.toDto())
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
            if (safeName.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill name has no usable characters"))
            }

            val path =
                skillWriter.write(
                    SkillEntry(
                        name = safeName,
                        description = req.description,
                        content = req.content,
                        contract =
                            SkillContract(
                                lifecycle = SkillLifecycle.DRAFT,
                                provenance = "owner",
                            ),
                    ),
                )
            if (path != null) {
                skillLoader.invalidateCache()
                val created = skillLoader.listSkills().first { skill -> skill.name == safeName }
                call.respond(HttpStatusCode.Created, created.toDto())
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
            val existing =
                skillLoader.listSkills().find { skill -> skill.name == name || skill.slug == name }
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))
            val skillSlug = existing.slug.ifBlank { existing.name }
            val isSystemSkill = SkillSeeder.isSystemSkill(skillSlug)

            // If empty/blank content and it is a system skill, restore default
            val targetContent = if (req.content.isBlank()) {
                if (isSystemSkill) {
                    SkillSeeder.getDefaultContent(skillSlug)
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill content cannot be blank"))
                } else {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Skill content cannot be blank"))
                }
            } else {
                req.content
            }

            if (req.content.isBlank() && isSystemSkill) {
                val dirPath = skillsDir / skillSlug / "SKILL.md"
                val flatPath = skillsDir / "$skillSlug.md"
                val targetPath =
                    when {
                        fs.exists(dirPath) -> dirPath
                        fs.exists(flatPath) -> flatPath
                        else -> return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))
                    }
                fs.sink(targetPath).buffer().use { sink -> sink.writeUtf8(targetContent) }
            } else {
                val nextLifecycle =
                    if (isSystemSkill) {
                        SkillLifecycle.ACTIVE
                    } else {
                        SkillLifecycle.QUARANTINED
                    }
                val updated =
                    existing.copy(
                        content = targetContent,
                        contract = existing.contract.copy(lifecycle = nextLifecycle, contentHash = null),
                    )
                if (skillWriter.update(updated) == null) {
                    return@put call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update skill '$name'"))
                }
            }
            skillLoader.invalidateCache()
            val saved = skillLoader.listSkills().first { skill -> skill.name == existing.name }
            call.respond(saved.toDto())
        } catch (e: Exception) {
            val skillName = call.parameters["name"] ?: "unknown"
            logger.error(e) { "Failed to update skill '$skillName'" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    put("/skills/{name}/lifecycle") {
        try {
            val name = call.parameters["name"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))
            val request = call.receive<UpdateSkillLifecycleRequest>()
            val existing =
                skillLoader.listSkills().find { skill -> skill.name == name || skill.slug == name }
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))
            if (SkillSeeder.isSystemSkill(existing.slug.ifBlank { existing.name })) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("System skill lifecycle cannot be changed"))
            }
            if (!existing.contract.lifecycle.canTransitionTo(request.lifecycle)) {
                return@put call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Invalid lifecycle transition: ${existing.contract.lifecycle} -> ${request.lifecycle}"),
                )
            }
            val updated = existing.copy(contract = existing.contract.copy(lifecycle = request.lifecycle))
            if (skillWriter.update(updated) == null) {
                return@put call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update skill '$name'"))
            }
            skillLoader.invalidateCache()
            val saved = skillLoader.listSkills().first { skill -> skill.name == existing.name }
            call.respond(saved.toDto())
        } catch (e: Exception) {
            logger.error(e) { "Failed to update skill lifecycle" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    // ── Delete a skill ──────────────────────────────────────────
    delete("/skills/{name}") {
        try {
            val name = call.parameters["name"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))
            val existing =
                skillLoader.listSkills().find { skill -> skill.name == name || skill.slug == name }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill '$name' not found"))
            val skillSlug = existing.slug.ifBlank { existing.name }

            if (SkillSeeder.isSystemSkill(skillSlug)) {
                return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot delete system skill '$name'"))
            }

            val deleted = skillLoader.deleteSkill(skillSlug)
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

private fun SkillEntry.toDto(): SkillDto =
    SkillDto(
        name = name,
        description = description,
        content = content,
        preview = content.lines().take(5).joinToString("\n"),
        isSystem = SkillSeeder.isSystemSkill(slug.ifBlank { name }),
        contract = contract,
    )
