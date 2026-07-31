package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.AIAgent
import dev.promethe.core.FeedbackCollector
import dev.promethe.core.KoogLlmAdapter
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Feedback and stats routes.
 */
fun Route.feedbackRoutes(
    feedbackCollector: FeedbackCollector,
    llmAdapter: KoogLlmAdapter,
    agent: AIAgent,
    gepaJobManager: GepaJobManager,
) {
    get("/stats") {
        val llmStats = llmAdapter.getStats()
        val (avgFeedback, fbCount) = feedbackCollector.getGlobalStats()
        call.respond(
            StatsResponse(
                totalTokens = (llmStats.promptTokens + llmStats.completionTokens).toLong(),
                totalRequests = llmStats.totalRequests.toLong(),
                estimatedCost = llmStats.totalCost,
                avgFeedback = avgFeedback,
                feedbackCount = fbCount,
            ),
        )
    }

    get("/feedback") {
        val (avg, count) = feedbackCollector.getGlobalStats()
        call.respond(FeedbackStats(avg, count))
    }

    post("/feedback") {
        val req = call.receive<FeedbackRequest>()
        feedbackCollector.record(req.sessionId, req.score, req.comment)
        call.respond(HttpStatusCode.OK, mapOf("status" to "recorded"))
    }

    // ── GEPA Background Job API ──────────────────────────────────────────────

    // Start a new GEPA optimization job (returns immediately with jobId)
    post("/gepa/optimize") {
        if (gepaJobManager.hasRunningJob()) {
            val running = gepaJobManager.getRunningJob()!!
            call.respond(HttpStatusCode.Conflict, running)
            return@post
        }
        val request = try {
            call.receive<GepaOptimizeRequest>()
        } catch (_: Exception) {
            GepaOptimizeRequest()
        }
        val jobId = gepaJobManager.startOptimization(request)
        call.respond(HttpStatusCode.Accepted, GepaStartResponse(jobId = jobId))
    }

    // Get status of a specific job
    get("/gepa/jobs/{id}") {
        val jobId = call.parameters["id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing job ID"))
        val job = gepaJobManager.getJob(jobId)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
        call.respond(job)
    }

    // List all jobs (most recent first)
    get("/gepa/jobs") {
        call.respond(gepaJobManager.getAllJobs())
    }

    // Get the currently running job (if any) — convenience endpoint
    get("/gepa/current") {
        val running = gepaJobManager.getRunningJob()
        if (running != null) {
            call.respond(running)
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
