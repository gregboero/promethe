package dev.promethe.gateway

import dev.promethe.api.OpenAIChatChoice
import dev.promethe.api.OpenAIChatMessage
import dev.promethe.api.OpenAIChatRequest
import dev.promethe.api.OpenAIChatResponse
import dev.promethe.api.OpenAIDelta
import dev.promethe.api.OpenAIError
import dev.promethe.api.OpenAIErrorResponse
import dev.promethe.api.OpenAIModel
import dev.promethe.api.OpenAIModelList
import dev.promethe.api.OpenAIStreamChoice
import dev.promethe.api.OpenAIStreamChunk
import dev.promethe.api.OpenAIUsage
import dev.promethe.core.AgentExecutionEvent
import dev.promethe.core.AgentExecutionOrigin
import dev.promethe.core.AgentExecutionRequest
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.AgentHistoryMessage
import dev.promethe.core.KoogLlmAdapter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.*
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** OpenAI-compatible transport adapter backed by the same agent loop as A2A. */
fun Route.openAiCompatRoutes(
    executionService: AgentExecutionPort,
    llmAdapter: KoogLlmAdapter,
) {
    val json = Json { encodeDefaults = true }

    post("/v1/chat/completions") {
        val request =
            try {
                call.receive<OpenAIChatRequest>()
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    OpenAIErrorResponse(error = OpenAIError(message = e.message ?: "Invalid request")),
                )
            }

        val prepared = request.toAgentRequest()
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                OpenAIErrorResponse(
                    error = OpenAIError(
                        message = "At least one non-empty user message is required",
                        code = "empty_input",
                    ),
                ),
            )

        val completionId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000

        if (request.stream == true) {
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                val roleChunk =
                    OpenAIStreamChunk(
                        id = completionId,
                        created = created,
                        model = request.model,
                        choices = listOf(OpenAIStreamChoice(delta = OpenAIDelta(role = "assistant"))),
                    )
                writeStringUtf8("data: ${json.encodeToString(roleChunk)}\n\n")
                flush()

                var failed = false
                executionService.execute(prepared).collect { event ->
                    when (event) {
                        is AgentExecutionEvent.Step -> {
                            val content = event.trajectory.outputs["response"]?.takeIf { it.isNotBlank() }
                            if (content != null) {
                                val chunk =
                                    OpenAIStreamChunk(
                                        id = completionId,
                                        created = created,
                                        model = request.model,
                                        choices = listOf(OpenAIStreamChoice(delta = OpenAIDelta(content = content))),
                                    )
                                writeStringUtf8("data: ${json.encodeToString(chunk)}\n\n")
                                flush()
                            }
                        }

                        is AgentExecutionEvent.Failed -> {
                            failed = true
                            val error = OpenAIErrorResponse(error = OpenAIError(message = event.message, code = event.code))
                            writeStringUtf8("data: ${json.encodeToString(error)}\n\n")
                            flush()
                        }

                        is AgentExecutionEvent.Completed -> {}
                    }
                }

                if (!failed) {
                    val finishChunk =
                        OpenAIStreamChunk(
                            id = completionId,
                            created = created,
                            model = request.model,
                            choices =
                                listOf(
                                    OpenAIStreamChoice(
                                        delta = OpenAIDelta(),
                                        finishReason = "stop",
                                    ),
                                ),
                            usage = OpenAIUsage(0, 0, 0),
                        )
                    writeStringUtf8("data: ${json.encodeToString(finishChunk)}\n\n")
                }
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        } else {
            try {
                val response = executionService.executeToCompletion(prepared)
                call.respond(
                    OpenAIChatResponse(
                        id = completionId,
                        created = created,
                        model = request.model,
                        choices =
                            listOf(
                                OpenAIChatChoice(
                                    message = OpenAIChatMessage(role = "assistant", content = response),
                                ),
                            ),
                        usage = OpenAIUsage(0, 0, 0),
                    ),
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    OpenAIErrorResponse(
                        error = OpenAIError(message = e.message ?: "Agent execution failed", code = "agent_error"),
                    ),
                )
            }
        }
    }

    get("/v1/models") {
        try {
            val models = llmAdapter.getAvailableModels()
            call.respond(
                OpenAIModelList(
                    data =
                        models.map { model ->
                            OpenAIModel(
                                id = model.id,
                                created = System.currentTimeMillis() / 1000,
                                ownedBy = model.provider,
                            )
                        },
                ),
            )
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                OpenAIErrorResponse(error = OpenAIError(message = e.message ?: "Error listing models")),
            )
        }
    }
}

private fun OpenAIChatRequest.toAgentRequest(): AgentExecutionRequest? {
    val lastUserIndex = messages.indexOfLast { it.role == "user" && it.content.isNotBlank() }
    if (lastUserIndex < 0) return null

    val userMessage = messages[lastUserIndex].content.trim()
    val history =
        messages.take(lastUserIndex)
            .filter { it.content.isNotBlank() }
            .map { AgentHistoryMessage(role = it.role, content = it.content) }

    return AgentExecutionRequest(
        sessionId = "openai-${UUID.randomUUID()}",
        text = userMessage,
        history = history,
        model = model.takeIf { it.isNotBlank() },
        origin = AgentExecutionOrigin.OPENAI_COMPAT,
        channelHint = "openai-compatible",
    )
}
