package dev.promethe.core.sandbox

import dev.promethe.api.SandboxBackend
import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxedExecutionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class UnavailableSandboxManagerTest {
    @Test
    fun `unavailable manager rejects every execution`() =
        runBlocking {
            val manager = UnavailableSandboxManager("missing helper token=top-secret")
            val request =
                SandboxedExecutionRequest(
                    executionId = "denied",
                    sessionId = "session",
                    executable = "echo",
                    workingDirectory = "workspace",
                )

            val result = manager.execute(request)
            val status = manager.status()

            assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, result.errorCode)
            assertFalse(status.available)
            assertEquals(SandboxBackend.UNAVAILABLE, status.backend)
            assertFalse(status.message.orEmpty().contains("top-secret"))
            assertFalse(manager.cancel("denied"))
        }
}
