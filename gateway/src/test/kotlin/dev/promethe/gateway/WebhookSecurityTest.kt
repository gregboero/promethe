package dev.promethe.gateway

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSecurityTest {
    @BeforeTest
    fun resetReplayGuard() {
        WebhookReplayGuard.clear()
    }

    @Test
    fun `Slack verifier consumes a supplied raw body once and rejects replay timestamps`() =
        testApplication {
            application { install(ContentNegotiation) { json() } }
            routing {
                post("/slack") {
                    val rawBody = call.receiveText()
                    if (!WebhookAuth.verifySlack(call, "signing-secret", rawBody, nowEpochSeconds = 1_000)) return@post
                    call.respondText(rawBody)
                }
            }

            val body = """{"type":"event_callback"}"""
            val signature = "v0=" + hmac("signing-secret", "v0:1000:$body")
            val accepted =
                client.post("/slack") {
                    contentType(ContentType.Application.Json)
                    header("X-Slack-Request-Timestamp", "1000")
                    header("X-Slack-Signature", signature)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals(body, accepted.bodyAsText())

            val stale =
                client.post("/slack") {
                    contentType(ContentType.Application.Json)
                    header("X-Slack-Request-Timestamp", "1")
                    header("X-Slack-Signature", "v0=" + hmac("signing-secret", "v0:1:$body"))
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Unauthorized, stale.status)
        }

    @Test
    fun `webhook verification fails closed when a secret is absent`() =
        testApplication {
            application { install(ContentNegotiation) { json() } }
            routing {
                post("/telegram") {
                    if (!WebhookAuth.verifyTelegram(call, "")) return@post
                    call.respondText("unexpected")
                }
            }

            val response = client.post("/telegram")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `replay guard accepts an event once per TTL`() {
        assertTrue(WebhookReplayGuard.accept("slack", "evt-1", now = 1_000, ttlMs = 100))
        assertFalse(WebhookReplayGuard.accept("slack", "evt-1", now = 1_050, ttlMs = 100))
        assertTrue(WebhookReplayGuard.accept("slack", "evt-1", now = 1_101, ttlMs = 100))
    }

    private fun hmac(
        key: String,
        data: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
