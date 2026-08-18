package dev.promethe.core

import dev.promethe.api.ToolIntentRecord
import dev.promethe.api.ToolIntentStatus
import dev.promethe.db.PrometheDatabaseApi
import okio.ByteString.Companion.encodeUtf8

sealed interface ToolIntentAdmission {
    data class Proceed(
        val intentId: String?,
    ) : ToolIntentAdmission

    data class Replay(
        val intentId: String,
        val message: String,
    ) : ToolIntentAdmission

    data class Denied(
        val intentId: String?,
        val message: String,
    ) : ToolIntentAdmission
}

interface ToolIntentLedger {
    suspend fun prepare(
        request: ToolExecutionRequest,
        risk: ToolRisk,
        now: Long,
    ): ToolIntentAdmission

    suspend fun markExecuting(
        intentId: String,
        now: Long,
    ): Boolean

    suspend fun markSucceeded(
        intentId: String,
        result: String,
        now: Long,
    ): Boolean

    suspend fun markFailed(
        intentId: String,
        errorCode: String,
        now: Long,
    ): Boolean

    suspend fun markBlocked(
        intentId: String,
        errorCode: String,
        now: Long,
    ): Boolean
}

object NoOpToolIntentLedger : ToolIntentLedger {
    override suspend fun prepare(
        request: ToolExecutionRequest,
        risk: ToolRisk,
        now: Long,
    ): ToolIntentAdmission = ToolIntentAdmission.Proceed(null)

    override suspend fun markExecuting(
        intentId: String,
        now: Long,
    ): Boolean = true

    override suspend fun markSucceeded(
        intentId: String,
        result: String,
        now: Long,
    ): Boolean = true

    override suspend fun markFailed(
        intentId: String,
        errorCode: String,
        now: Long,
    ): Boolean = true

    override suspend fun markBlocked(
        intentId: String,
        errorCode: String,
        now: Long,
    ): Boolean = true
}

class PersistentToolIntentLedger(
    private val database: PrometheDatabaseApi,
    private val executionIdGenerator: ExecutionIdGenerator = DefaultExecutionIdGenerator,
) : ToolIntentLedger {
    override suspend fun prepare(
        request: ToolExecutionRequest,
        risk: ToolRisk,
        now: Long,
    ): ToolIntentAdmission {
        val intentId = executionIdGenerator.nextId("tool")
        val invocationHash = toolInvocationHash(request)
        val idempotencyKeyHash =
            toolIntentDigest(
                request.idempotencyKey
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: request.runId?.let { runId -> "promethe-tool-run-v1\u0000$runId\u0000$invocationHash" }
                    ?: "promethe-tool-unique-v1\u0000$intentId",
            )
        val record =
            ToolIntentRecord(
                intentId = intentId,
                idempotencyKeyHash = idempotencyKeyHash,
                invocationHash = invocationHash,
                runId = request.runId,
                stepId = request.stepId,
                sessionId = request.sessionId,
                toolName = request.toolName,
                risk = risk,
                status = ToolIntentStatus.PREPARED,
                createdAt = now,
                updatedAt = now,
            )
        if (database.insertToolIntent(record)) return ToolIntentAdmission.Proceed(intentId)

        val existing = database.getToolIntentByIdempotencyKeyHash(idempotencyKeyHash)
            ?: return ToolIntentAdmission.Denied(null, "Durable tool intent conflict")
        if (existing.invocationHash != invocationHash) {
            return ToolIntentAdmission.Denied(existing.intentId, "Idempotency key reused for a different tool invocation")
        }
        if (existing.canRetrySafely(risk, now)) {
            val reset =
                database.resetToolIntentForRetry(
                    intentId = existing.intentId,
                    expectedStatuses = setOf(existing.status),
                    updatedAt = now,
                )
            if (reset) return ToolIntentAdmission.Proceed(existing.intentId)
            return ToolIntentAdmission.Denied(existing.intentId, "An identical tool call was claimed concurrently")
        }
        return when (existing.status) {
            ToolIntentStatus.SUCCEEDED -> {
                ToolIntentAdmission.Replay(
                    existing.intentId,
                    "[IDEMPOTENT] This tool call already completed. Use its prior observation; it was not executed again.",
                )
            }

            ToolIntentStatus.BLOCKED -> {
                ToolIntentAdmission.Denied(existing.intentId, "This tool call was already blocked")
            }

            ToolIntentStatus.FAILED -> {
                ToolIntentAdmission.Denied(existing.intentId, "The previous tool attempt failed and will not be repeated automatically")
            }

            ToolIntentStatus.PREPARED -> {
                ToolIntentAdmission.Denied(existing.intentId, "An identical tool call is already awaiting execution")
            }

            ToolIntentStatus.EXECUTING -> {
                ToolIntentAdmission.Denied(
                    existing.intentId,
                    if (existing.risk == ToolRisk.READ) {
                        "An identical read is already executing"
                    } else {
                        "The previous tool execution outcome is uncertain; reconcile it before retrying"
                    },
                )
            }
        }
    }

    override suspend fun markExecuting(
        intentId: String,
        now: Long,
    ): Boolean =
        database.transitionToolIntent(
            intentId = intentId,
            expectedStatuses = setOf(ToolIntentStatus.PREPARED),
            status = ToolIntentStatus.EXECUTING,
            resultHash = null,
            errorCode = null,
            startedAt = now,
            finishedAt = null,
            updatedAt = now,
        )

    override suspend fun markSucceeded(
        intentId: String,
        result: String,
        now: Long,
    ): Boolean = finish(intentId, ToolIntentStatus.SUCCEEDED, toolIntentDigest(result), null, now)

    override suspend fun markFailed(
        intentId: String,
        errorCode: String,
        now: Long,
    ): Boolean = finish(intentId, ToolIntentStatus.FAILED, null, errorCode, now)

    override suspend fun markBlocked(
        intentId: String,
        errorCode: String,
        now: Long,
    ): Boolean =
        database.transitionToolIntent(
            intentId = intentId,
            expectedStatuses = setOf(ToolIntentStatus.PREPARED, ToolIntentStatus.EXECUTING),
            status = ToolIntentStatus.BLOCKED,
            resultHash = null,
            errorCode = errorCode,
            startedAt = null,
            finishedAt = now,
            updatedAt = now,
        )

    private suspend fun finish(
        intentId: String,
        status: ToolIntentStatus,
        resultHash: String?,
        errorCode: String?,
        now: Long,
    ): Boolean =
        database.transitionToolIntent(
            intentId = intentId,
            expectedStatuses = setOf(ToolIntentStatus.EXECUTING),
            status = status,
            resultHash = resultHash,
            errorCode = errorCode,
            startedAt = null,
            finishedAt = now,
            updatedAt = now,
        )

    private fun ToolIntentRecord.canRetrySafely(
        requestedRisk: ToolRisk,
        now: Long,
    ): Boolean {
        if (now - updatedAt < SAFE_RETRY_AFTER_MILLIS) return false
        return when (status) {
            ToolIntentStatus.PREPARED -> true
            ToolIntentStatus.EXECUTING, ToolIntentStatus.FAILED -> risk == ToolRisk.READ && requestedRisk == ToolRisk.READ
            ToolIntentStatus.SUCCEEDED, ToolIntentStatus.BLOCKED -> false
        }
    }

    private companion object {
        const val SAFE_RETRY_AFTER_MILLIS = 5 * 60 * 1_000L
    }
}

internal fun toolInvocationHash(request: ToolExecutionRequest): String =
    toolIntentDigest(
        buildString {
            append("promethe-tool-invocation-v1\u0000")
            append(request.toolName)
            append('\u0000')
            append(canonicalJson(request.arguments))
            append('\u0000')
            append(request.sessionId)
            append('\u0000')
            append(request.origin.name)
            append('\u0000')
            append(request.projectId.orEmpty())
            append('\u0000')
            append(request.memoryNamespace)
            append('\u0000')
            append(request.workspaceRelativePath.orEmpty())
        },
    )

internal fun toolIntentDigest(value: String): String = value.encodeUtf8().sha256().hex()
