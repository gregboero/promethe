package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Agent Profile CRUD routes.
 */
fun Route.agentProfileRoutes(database: PrometheDatabaseApi) {
    get("/agents") {
        val profiles = database.getAllAgentProfiles()
        val dtos = profiles.map { it.toDto() }
        call.respond(dtos)
    }

    get("/agents/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val profile =
            database.getAgentProfile(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Profile not found"))
        call.respond(profile.toDto())
    }

    post("/agents") {
        val req = call.receive<AgentProfileRequest>()
        val now = System.currentTimeMillis()
        val id = req.id ?: "agent-$now"
        val row =
            dev.promethe.db.AgentProfileRow(
                id = id,
                name = req.name,
                provider = req.provider,
                model = req.model,
                systemPrompt = req.systemPrompt,
                tools = "[" + req.tools.joinToString(",") { "\"$it\"" } + "]",
                skills = "[" + req.skills.joinToString(",") { "\"$it\"" } + "]",
                maxIterations = req.maxIterations,
                temperature = req.temperature,
                reasoningEffort = req.reasoningEffort,
                isSystem = req.isSystem,
                ephemeral = req.ephemeral,
                createdAt = now,
                updatedAt = now,
            )
        database.insertAgentProfile(row)
        call.respond(HttpStatusCode.Created, row.toDto())
    }

    put("/agents/{id}") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val existing =
            database.getAgentProfile(id)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Profile not found"))
        val req = call.receive<AgentProfileRequest>()
        val now = System.currentTimeMillis()
        val updated =
            dev.promethe.db.AgentProfileRow(
                id = id,
                name = req.name,
                provider = req.provider,
                model = req.model,
                systemPrompt = req.systemPrompt,
                tools = "[" + req.tools.joinToString(",") { "\"$it\"" } + "]",
                skills = "[" + req.skills.joinToString(",") { "\"$it\"" } + "]",
                maxIterations = req.maxIterations,
                temperature = req.temperature,
                reasoningEffort = req.reasoningEffort,
                isSystem = req.isSystem,
                ephemeral = req.ephemeral,
                createdAt = existing.createdAt,
                updatedAt = now,
            )
        database.updateAgentProfile(updated)
        call.respond(updated.toDto())
    }

    delete("/agents/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val profile =
            database.getAgentProfile(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Profile not found"))
        if (profile.isSystem) {
            return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot delete system agent"))
        }
        database.deleteAgentProfile(id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
    }
}

// ── Extension mapper ──

internal fun dev.promethe.db.AgentProfileRow.toDto() =
    AgentProfile(
        id = id,
        name = name,
        provider = provider,
        model = model,
        systemPrompt = systemPrompt,
        tools =
            if (tools.isBlank()) {
                emptyList()
            } else {
                try {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(tools)
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to parse agent profile tools JSON: '${tools.take(50)}'" }
                    emptyList()
                }
            },
        skills =
            if (skills.isBlank()) {
                emptyList()
            } else {
                try {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(skills)
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to parse agent profile skills JSON: '${skills.take(50)}'" }
                    emptyList()
                }
            },
        maxIterations = maxIterations,
        temperature = temperature,
        reasoningEffort = reasoningEffort,
        isSystem = isSystem,
        ephemeral = ephemeral,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
