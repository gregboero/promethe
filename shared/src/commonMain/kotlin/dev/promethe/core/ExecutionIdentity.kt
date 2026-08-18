package dev.promethe.core

import kotlin.random.Random
import kotlin.time.Clock

fun interface ExecutionIdGenerator {
    fun nextId(prefix: String): String
}

object DefaultExecutionIdGenerator : ExecutionIdGenerator {
    override fun nextId(prefix: String): String {
        require(EXECUTION_ID_PREFIX.matches(prefix)) { "Invalid execution ID prefix" }
        val timestamp = Clock.System.now().toEpochMilliseconds().toString(36)
        val entropy = Random.nextBytes(12).joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
        return "$prefix-$timestamp-$entropy"
    }
}

data class AgentRunIdentity(
    val runId: String,
    val parentRunId: String? = null,
) {
    init {
        require(isValidExecutionId(runId)) { "Invalid run ID" }
        require(parentRunId == null || isValidExecutionId(parentRunId)) { "Invalid parent run ID" }
    }

    fun step(index: Int): AgentStepIdentity {
        require(index > 0) { "Step index must be positive" }
        return AgentStepIdentity(
            runId = runId,
            stepId = "$runId-step-${index.toString().padStart(4, '0')}",
            index = index,
        )
    }
}

data class AgentStepIdentity(
    val runId: String,
    val stepId: String,
    val index: Int,
)

internal fun isValidExecutionId(value: String): Boolean = value.length in 8..160 && EXECUTION_ID.matches(value)

private val EXECUTION_ID_PREFIX = Regex("[a-z][a-z0-9-]{1,20}")
private val EXECUTION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
