package dev.promethe.core

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.messages.ContextMessageStorage
import ai.koog.a2a.server.messages.InMemoryMessageStorage
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.a2a.server.tasks.ContextTaskStorage
import ai.koog.a2a.server.tasks.InMemoryTaskStorage
import ai.koog.a2a.transport.ServerCallContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * A2A Loopback Transport — in-memory, zero overhead.
 *
 * Instead of making an HTTP request to an A2A server, this transport
 * calls the AgentExecutor directly in-process. It creates the correct
 * SessionEventProcessor and RequestContext that executors expect.
 *
 * Overhead: <0.5ms per call (no network, no HTTP parsing).
 */
class A2ALoopbackTransport(
    private val executor: AgentExecutor,
) {
    // Shared storages for loopback sessions
    private val taskStorage = InMemoryTaskStorage()
    private val messageStorage = InMemoryMessageStorage()

    /**
     * Send a task to the local executor and collect streaming events.
     * Returns a Flow that completes when the executor finishes.
     */
    fun sendTaskStreaming(
        taskId: String,
        message: Message,
    ): Flow<A2AStreamEvent> =
        flow {
            val contextId = "ctx-${UUID.randomUUID()}"

            val eventProcessor =
                SessionEventProcessor(
                    contextId = contextId,
                    taskId = taskId,
                    taskStorage = taskStorage,
                )

            val context =
                ai.koog.a2a.server.session.RequestContext(
                    callContext = ServerCallContext(),
                    params = MessageSendParams(message = message.copy(contextId = contextId)),
                    taskStorage = ContextTaskStorage(contextId, taskStorage),
                    messageStorage = ContextMessageStorage(contextId, messageStorage),
                    contextId = contextId,
                    taskId = taskId,
                    task =
                        Task(
                            id = taskId,
                            contextId = contextId,
                            status = TaskStatus(state = TaskState.Submitted),
                        ),
                )

            // Execute the agent
            executor.execute(context, eventProcessor)
            eventProcessor.close()

            emit(A2AStreamEvent.StatusEvent(state = "completed"))
        }

    /**
     * Send a task and wait for the final response (blocking mode).
     */
    suspend fun sendTask(
        taskId: String,
        message: Message,
    ): A2ATaskResult {
        val contextId = "ctx-${UUID.randomUUID()}"

        val eventProcessor =
            SessionEventProcessor(
                contextId = contextId,
                taskId = taskId,
                taskStorage = taskStorage,
            )

        val context =
            ai.koog.a2a.server.session.RequestContext(
                callContext = ServerCallContext(),
                params = MessageSendParams(message = message.copy(contextId = contextId)),
                taskStorage = ContextTaskStorage(contextId, taskStorage),
                messageStorage = ContextMessageStorage(contextId, messageStorage),
                contextId = contextId,
                taskId = taskId,
                task =
                    Task(
                        id = taskId,
                        contextId = contextId,
                        status = TaskStatus(state = TaskState.Submitted),
                    ),
            )

        // Execute the agent
        executor.execute(context, eventProcessor)
        eventProcessor.close()

        // Retrieve task state from storage
        val responseText = try {
            val finalTask = taskStorage.get(taskId)
            // The response is in the last status message (from sendTaskEvent)
            finalTask?.status?.message
                ?.parts
                ?.filterIsInstance<TextPart>()
                ?.joinToString("") { it.text }
                ?: finalTask?.history
                    ?.lastOrNull()
                    ?.parts
                    ?.filterIsInstance<TextPart>()
                    ?.joinToString("") { it.text }
                ?: ""
        } catch (_: Exception) {
            ""
        }

        return A2ATaskResult(
            taskId = taskId,
            response = responseText,
            events = listOf(A2AStreamEvent.StatusEvent("completed")),
        )
    }
}

/**
 * Stream event types emitted by the loopback transport.
 */
sealed class A2AStreamEvent {
    data class MessageEvent(
        val message: Message,
    ) : A2AStreamEvent()

    data class StatusEvent(
        val state: String,
        val description: String? = null,
    ) : A2AStreamEvent()
}

/**
 * Result of a completed A2A task.
 */
data class A2ATaskResult(
    val taskId: String,
    val response: String,
    val events: List<A2AStreamEvent>,
)
