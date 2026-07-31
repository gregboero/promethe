package dev.promethe.gateway

import dev.promethe.core.MemoryLayer
import dev.promethe.core.memory.MemoryFact
import dev.promethe.core.memory.MemoryTier
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Memory REST API — browse, search, create, update, and delete memory facts.
 */
fun Route.memoryRoutes(memoryLayer: MemoryLayer) {
    get("/memory/facts") {
        val query = call.request.queryParameters["q"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

        val facts =
            if (!query.isNullOrBlank()) {
                memoryLayer.recallFacts(query, limit)
            } else {
                memoryLayer.getAllFacts()
            }

        call.respond(
            MemoryFactsResponse(
                facts = facts.map { it.toDto() },
                total = facts.size,
                query = query,
            ),
        )
    }

    post("/memory/facts") {
        val req = call.receive<CreateFactRequest>()
        val fact =
            MemoryFact(
                category = req.category,
                content = req.content,
                confidence = req.confidence ?: 1.0f,
                sourceSession = req.sourceSession ?: "manual",
                tier =
                    try {
                        MemoryTier.valueOf(req.tier?.uppercase() ?: "ATOMIC")
                    } catch (e: Exception) {
                        logger.debug(e) { "Invalid memory tier '${req.tier}', defaulting to ATOMIC" }
                        MemoryTier.ATOMIC
                    },
            )
        memoryLayer.storeFact(fact)
        call.respond(HttpStatusCode.Created, mapOf("status" to "created", "category" to fact.category, "content" to fact.content.take(100)))
    }

    put("/memory/facts/{id}") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val req = call.receive<UpdateFactRequest>()
        val updated =
            memoryLayer.updateFact(
                id = id,
                category = req.category,
                content = req.content,
                confidence = req.confidence,
            )
        if (updated) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated", "id" to id))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Fact not found", "id" to id))
        }
    }

    delete("/memory/facts/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        memoryLayer.deleteFact(id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "deleted", "id" to id))
    }

    get("/memory/status") {
        call.respond(
            MemoryStatusResponse(
                provider = memoryLayer.providerName,
                factCount = memoryLayer.getAllFacts().size,
            ),
        )
    }
}

// ── DTOs ──

@Serializable
data class MemoryStatusResponse(
    val provider: String,
    val factCount: Int,
)

@Serializable
data class CreateFactRequest(
    val category: String,
    val content: String,
    val confidence: Float? = null,
    val sourceSession: String? = null,
    val tier: String? = null,
)

@Serializable
data class UpdateFactRequest(
    val category: String? = null,
    val content: String? = null,
    val confidence: Float? = null,
)

@Serializable
data class MemoryFactDto(
    val id: String,
    val userId: String,
    val category: String,
    val content: String,
    val confidence: Float,
    val tier: String,
    val createdAt: Long,
)

@Serializable
data class MemoryFactsResponse(
    val facts: List<MemoryFactDto>,
    val total: Int,
    val query: String? = null,
)

private fun MemoryFact.toDto() =
    MemoryFactDto(
        id = id,
        userId = userId,
        category = category,
        content = content,
        confidence = confidence,
        tier = tier.name,
        createdAt = createdAt,
    )
