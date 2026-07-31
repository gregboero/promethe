package dev.promethe.core

/**
 * Interface for human-in-the-loop approval before tool execution.
 * Implemented by [ToolApprovalGate] on JVM.
 */
interface ApprovalGate {
    enum class ApprovalScope {
        ONCE,
        SESSION,
        PERSISTENT,
    }

    data class ApprovalResult(
        val allowed: Boolean,
        val reason: String,
        val scope: ApprovalScope? = null,
        val approvalId: String? = null,
    )

    /**
     * Check whether the given tool call is allowed to proceed.
     * May suspend while waiting for user response.
     */
    suspend fun check(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalResult

    /**
     * Approval that cannot be disabled by APPROVAL_MODE=auto. Used for actions
     * that can change the host, workspace, configuration, or external state.
     */
    suspend fun checkMandatory(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalResult = check(toolName, args, sessionId)
}
