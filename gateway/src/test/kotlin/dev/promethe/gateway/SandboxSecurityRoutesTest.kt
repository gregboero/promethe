package dev.promethe.gateway

import dev.promethe.api.SandboxBackend
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxStatus
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.AgentConfig
import dev.promethe.core.sandbox.SandboxManager
import dev.promethe.core.sandbox.SandboxRuntimePolicy
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxSecurityRoutesTest {
    @Test
    fun `status route reports the active runtime profile`() =
        testApplication {
            val policy = runtimePolicy()
            application {
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(FakeSandboxManager, policy)
                        }
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.get("/api/v1/security/sandbox/status").status)
        }

    @Test
    fun `network and full access updates fail closed`() =
        testApplication {
            val policy = runtimePolicy()
            application {
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(FakeSandboxManager, policy)
                        }
                    }
                }
            }

            val response =
                client.put("/api/v1/security/permission-profile") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"mode":"WORKSPACE_WRITE","approvalPolicy":"ON_REQUEST","networkMode":"ALLOWLIST","readableRoots":[],"writableRoots":[],"protectedPaths":[".git"],"allowedDomains":["example.com"],"limits":{"timeoutMillis":30000,"maxOutputBytesPerStream":50000,"memoryBytes":536870912,"cpuLimit":1.0,"processLimit":128}}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    private fun runtimePolicy(): SandboxRuntimePolicy =
        SandboxRuntimePolicy(
            workspaceRoot = Files.createTempDirectory("promethe-sandbox-routes").toString(),
            config = AgentConfig(),
        )

    private object FakeSandboxManager : SandboxManager {
        override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult = SandboxedExecutionResult(executionId = request.executionId)

        override suspend fun cancel(executionId: String): Boolean = true

        override suspend fun status(): SandboxStatus =
            SandboxStatus(
                available = true,
                backend = SandboxBackend.WINDOWS_ELEVATED,
                mode = SandboxMode.READ_ONLY,
                networkMode = SandboxNetworkMode.OFF,
            )

        override suspend fun selfTest(): SandboxStatus = status().copy(selfTestPassed = true)
    }
}
