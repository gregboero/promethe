package dev.promethe.core.sandbox

import dev.promethe.api.SandboxBackend
import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxStatus
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult

class UnavailableSandboxManager(
    private val reason: String = DEFAULT_REASON,
) : SandboxManager {
    override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult =
        SandboxedExecutionResult(
            executionId = request.executionId,
            errorCode = SandboxErrorCode.BACKEND_UNAVAILABLE,
            errorMessage = safeReason(),
        )

    override suspend fun cancel(executionId: String): Boolean = false

    override suspend fun status(): SandboxStatus = unavailableStatus()

    override suspend fun selfTest(): SandboxStatus = unavailableStatus()

    private fun unavailableStatus(): SandboxStatus =
        SandboxStatus(
            available = false,
            backend = SandboxBackend.UNAVAILABLE,
            mode = SandboxMode.READ_ONLY,
            networkMode = SandboxNetworkMode.OFF,
            message = safeReason(),
        )

    private fun safeReason(): String = SandboxErrorRedactor.redact(reason)

    private companion object {
        const val DEFAULT_REASON = "Sandbox helper is unavailable; process tools are disabled."
    }
}
