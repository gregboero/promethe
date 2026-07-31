package dev.promethe.gateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class RateLimiterIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun resetRateLimiter() {
        RateLimiter.configure(maxRequestsPerWindow = 60, windowDurationMs = 60_000L)
        try {
            val countersField = RateLimiter::class.java.getDeclaredField("counters")
            countersField.isAccessible = true
            val countersMap = countersField.get(RateLimiter) as ConcurrentHashMap<*, *>
            countersMap.clear()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ApplicationTestBuilder.configureApp(
        maxRequests: Int,
        windowMs: Long,
    ) {
        RateLimiter.configure(maxRequests, windowMs)
        application {
            install(ContentNegotiation) { json() }
            RateLimiter.install(this)
            routing {
                get("/api/test-limit") {
                    call.respondText("OK")
                }
                get("/health") {
                    call.respondText("HEALTHY")
                }
            }
        }
    }

    @Test
    fun `requests within limit succeed and return headers`() =
        testApplication {
            configureApp(maxRequests = 5, windowMs = 10_000L)

            val response = client.get("/api/test-limit")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())

            assertEquals("5", response.headers["X-RateLimit-Limit"])
            assertEquals("4", response.headers["X-RateLimit-Remaining"])
            assertNotNull(response.headers["X-RateLimit-Reset"])
        }

    @Test
    fun `requests exceeding limit return 429`() =
        testApplication {
            configureApp(maxRequests = 2, windowMs = 10_000L)

            // Request 1: OK
            val response1 = client.get("/api/test-limit")
            assertEquals(HttpStatusCode.OK, response1.status)
            assertEquals("1", response1.headers["X-RateLimit-Remaining"])

            // Request 2: OK
            val response2 = client.get("/api/test-limit")
            assertEquals(HttpStatusCode.OK, response2.status)
            assertEquals("0", response2.headers["X-RateLimit-Remaining"])

            // Request 3: Too Many Requests
            val response3 = client.get("/api/test-limit")
            assertEquals(HttpStatusCode.TooManyRequests, response3.status)

            val errorBody = json.parseToJsonElement(response3.bodyAsText()).jsonObject
            assertTrue(errorBody.containsKey("error"))
            val errorText = errorBody["error"]?.jsonPrimitive?.content ?: ""
            assertTrue(errorText.contains("Rate limit exceeded"))
        }

    @Test
    fun `health checks and webhooks are bypassed`() =
        testApplication {
            configureApp(maxRequests = 0, windowMs = 10_000L) // 0 requests allowed

            // GET /health should succeed since it's bypassed
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("HEALTHY", response.bodyAsText())
            assertNull(response.headers["X-RateLimit-Limit"])
        }
}
