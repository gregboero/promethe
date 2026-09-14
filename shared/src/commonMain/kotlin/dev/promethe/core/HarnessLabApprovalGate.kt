package dev.promethe.core

/** Installed only by the process owner LAB flag, for this fixed isolated experiment. */
class HarnessLabApprovalGate(
    private val delegate: ApprovalGate,
) : ApprovalGate {
    override suspend fun check(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalGate.ApprovalResult = if (toolName in TOOLS) grant(sessionId) else delegate.check(toolName, args, sessionId)

    override suspend fun checkMandatory(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalGate.ApprovalResult = if (toolName in TOOLS) grant(sessionId) else delegate.checkMandatory(toolName, args, sessionId)

    private fun grant(session: String) =
        ApprovalGate.ApprovalResult(
            allowed = session.isNotBlank() && session != "unknown",
            reason = "Process owner enabled session-scoped, network-free harness LAB",
            scope = ApprovalGate.ApprovalScope.SESSION,
            approvalId = "harness-lab:$session",
        )

    companion object {
        val TOOLS = setOf("harness_inspect", "harness_propose", "harness_evaluate", "harness_activate", "harness_rollback", "harness_disable", "harness_adapt")
    }
}
