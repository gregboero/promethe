package dev.promethe.gateway

import dev.promethe.api.ErrorResponse
import dev.promethe.api.SandboxBackend
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxStatus
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.AgentConfig
import dev.promethe.core.PrometheJson
import dev.promethe.core.sandbox.SandboxManager
import dev.promethe.core.sandbox.SandboxRuntimePolicy
import dev.promethe.db.DatabaseFactory
import dev.promethe.gateway.auth.OwnerAuthService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxSecurityRoutesTest {
    private val apiKey = "pk-prom-sandbox-test-key"

    @Test
    fun `status route reports the active runtime profile`() =
        testApplication {
            val policy = runtimePolicy()
            val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = false,
                    allowedOrigins = emptySet(),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                AuthMiddleware.install(this, apiKey, security, auth)
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(FakeSandboxManager, policy, persistProfile = {})
                        }
                    }
                }
            }

            val response =
                client.get("/api/v1/security/sandbox/status") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val status = Json.decodeFromString<SandboxStatus>(response.bodyAsText())
            assertEquals(true, status.localConfigurationAllowed)
            assertEquals(policy.workspaceRoot(), status.workspaceRoot)
        }

    @Test
    fun `full file access is rejected when remote access is enabled`() =
        testApplication {
            val policy = runtimePolicy()
            val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = true,
                    allowedOrigins = emptySet(),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                AuthMiddleware.install(this, apiKey, security, auth)
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(
                                FakeSandboxManager,
                                policy,
                                remoteAccessEnabled = true,
                                persistProfile = {},
                            )
                        }
                    }
                }
            }

            val response =
                client.put("/api/v1/security/permission-profile") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(PrometheJson.encodeToString(SandboxPermissionProfile(mode = SandboxMode.FULL_ACCESS)))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `failed persistence rolls the live policy back`() =
        testApplication {
            val policy = runtimePolicy()
            val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = false,
                    allowedOrigins = emptySet(),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                AuthMiddleware.install(this, apiKey, security, auth)
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(
                                FakeSandboxManager,
                                policy,
                                persistProfile = { error("disk unavailable") },
                            )
                        }
                    }
                }
            }

            val response =
                client.put("/api/v1/security/permission-profile") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(PrometheJson.encodeToString(SandboxPermissionProfile(mode = SandboxMode.FULL_ACCESS)))
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(SandboxMode.WORKSPACE_WRITE, policy.get().mode)
        }

    @Test
    fun `network and full access updates fail closed`() =
        testApplication {
            val policy = runtimePolicy()
            val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = false,
                    allowedOrigins = emptySet(),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                AuthMiddleware.install(this, apiKey, security, auth)
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(FakeSandboxManager, policy, persistProfile = {})
                        }
                    }
                }
            }

            val response =
                client.put("/api/v1/security/permission-profile") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"mode":"WORKSPACE_WRITE","approvalPolicy":"ON_REQUEST","networkMode":"ALLOWLIST","readableRoots":[],"writableRoots":[],"protectedPaths":[".git"],"allowedDomains":["example.com"],"limits":{"timeoutMillis":30000,"maxOutputBytesPerStream":50000,"memoryBytes":536870912,"cpuLimit":1.0,"processLimit":128}}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `sandbox setup requires the loopback local key`() =
        testApplication {
            val policy = runtimePolicy()
            val manager = RecordingSandboxManager()
            val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = false,
                    allowedOrigins = emptySet(),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                AuthMiddleware.install(this, apiKey, security, auth)
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(manager, policy, persistProfile = {})
                        }
                    }
                }
            }

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/v1/security/sandbox/setup").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/v1/security/sandbox/setup") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }.status,
            )
            assertEquals(policy.workspaceRoot(), manager.setupWorkspace)
        }

    @Test
    fun `sandbox setup returns its actionable failure`() =
        testApplication {
            val policy = runtimePolicy()
            val manager = RecordingSandboxManager(setupFailure = "Windows sandbox setup was cancelled or could not be elevated.")
            val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = false,
                    allowedOrigins = emptySet(),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                AuthMiddleware.install(this, apiKey, security, auth)
                routing {
                    installGatewayContentNegotiation()
                    route("api") {
                        route("v1") {
                            sandboxSecurityRoutes(manager, policy, persistProfile = {})
                        }
                    }
                }
            }

            val response =
                client.post("/api/v1/security/sandbox/setup") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(
                "Windows sandbox setup was cancelled or could not be elevated.",
                Json.decodeFromString<ErrorResponse>(response.bodyAsText()).error,
            )
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

    private class RecordingSandboxManager(
        private val setupFailure: String? = null,
    ) : SandboxManager {
        var setupWorkspace: String? = null

        override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult = SandboxedExecutionResult(executionId = request.executionId)

        override suspend fun cancel(executionId: String): Boolean = true

        override suspend fun status(): SandboxStatus =
            SandboxStatus(
                available = false,
                backend = SandboxBackend.WINDOWS_ELEVATED,
                mode = SandboxMode.WORKSPACE_WRITE,
                networkMode = SandboxNetworkMode.OFF,
                setupRequired = true,
            )

        override suspend fun selfTest(): SandboxStatus = status()

        override suspend fun setup(workspaceRoot: String): SandboxStatus {
            setupWorkspace = workspaceRoot
            setupFailure?.let { error(it) }
            return status().copy(available = true, setupRequired = false, selfTestPassed = true)
        }
    }
}
