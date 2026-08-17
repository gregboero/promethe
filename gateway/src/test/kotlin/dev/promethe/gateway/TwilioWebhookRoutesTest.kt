package dev.promethe.gateway

import dev.promethe.core.config.ConfigProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class TwilioWebhookRoutesTest {
    @BeforeTest
    fun configureTwilio() {
        ConfigProvider.setOverride("TWILIO_ACCOUNT_SID", ACCOUNT_SID)
        ConfigProvider.setOverride("TWILIO_AUTH_TOKEN", AUTH_TOKEN)
        ConfigProvider.setOverride("TWILIO_PHONE_NUMBER", PROMETHE_NUMBER)
    }

    @AfterTest
    fun clearTwilio() {
        ConfigProvider.clearOverride("TWILIO_ACCOUNT_SID")
        ConfigProvider.clearOverride("TWILIO_AUTH_TOKEN")
        ConfigProvider.clearOverride("TWILIO_PHONE_NUMBER")
    }

    @Test
    fun `valid signed SMS is acknowledged then routed through the agent`() =
        testApplication {
            val execution = CompletableDeferred<Pair<String, String>>()
            val reply = CompletableDeferred<Pair<String, String>>()
            configureRoute(
                executor =
                    TwilioAgentExecutor { sessionId, text ->
                        execution.complete(sessionId to text)
                        "Tomorrow will be 21 C."
                    },
                sender = TwilioReplySender { to, body -> reply.complete(to to body) },
            )

            val response = client.postSignedTwilio(MESSAGE_SID)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Xml, response.contentType()?.withoutParameters())
            assertTrue(response.bodyAsText().contains("<Response></Response>"))
            assertEquals("sms-$USER_NUMBER" to "What is the weather tomorrow?", withTimeout(1_000) { execution.await() })
            assertEquals(USER_NUMBER to "Tomorrow will be 21 C.", withTimeout(1_000) { reply.await() })
        }

    @Test
    fun `invalid signature is rejected before agent execution`() =
        testApplication {
            val executions = AtomicInteger()
            configureRoute(
                executor = TwilioAgentExecutor { _, _ -> executions.incrementAndGet().toString() },
                sender = TwilioReplySender { _, _ -> fail("No reply should be sent") },
            )

            val response = client.post(TWILIO_PATH) {
                contentType(ContentType.Application.FormUrlEncoded)
                header("X-Twilio-Signature", "invalid")
                setBody(twilioForm(MESSAGE_SID))
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, executions.get())
        }

    @Test
    fun `replayed MessageSid is acknowledged without a second reply`() =
        testApplication {
            val replies = AtomicInteger()
            val firstReply = CompletableDeferred<Unit>()
            configureRoute(
                executor = TwilioAgentExecutor { _, _ -> "ok" },
                sender =
                    TwilioReplySender { _, _ ->
                        replies.incrementAndGet()
                        firstReply.complete(Unit)
                    },
            )
            val messageSid = "SM-replay-${System.nanoTime()}"

            assertEquals(HttpStatusCode.OK, client.postSignedTwilio(messageSid).status)
            withTimeout(1_000) { firstReply.await() }
            assertEquals(HttpStatusCode.OK, client.postSignedTwilio(messageSid).status)
            delay(50)

            assertEquals(1, replies.get())
        }

    @Test
    fun `long replies are bounded to three Twilio messages`() {
        val result = splitTwilioReply("word ".repeat(2_000), maxChars = 100, maxParts = 3)

        assertEquals(3, result.size)
        assertTrue(result.all { it.length <= 100 })
        assertTrue(result.last().endsWith("..."))
    }

    private fun ApplicationTestBuilder.configureRoute(
        executor: TwilioAgentExecutor,
        sender: TwilioReplySender,
    ) {
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                twilioWebhookRoute(executor, PUBLIC_BASE_URL, sender)
            }
        }
    }

    private suspend fun io.ktor.client.HttpClient.postSignedTwilio(messageSid: String): HttpResponse {
        val form = twilioForm(messageSid)
        val parameters = parseQueryString(form).names().associateWith { name -> parseQueryString(form)[name].orEmpty() }
        return post(TWILIO_PATH) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("X-Twilio-Signature", twilioSignature(PUBLIC_BASE_URL + TWILIO_PATH, parameters))
            setBody(form)
        }
    }

    private fun twilioForm(messageSid: String): String =
        Parameters.build {
            append("AccountSid", ACCOUNT_SID)
            append("MessageSid", messageSid)
            append("From", USER_NUMBER)
            append("To", PROMETHE_NUMBER)
            append("Body", "What is the weather tomorrow?")
        }.formUrlEncode()

    private fun twilioSignature(
        url: String,
        parameters: Map<String, String>,
    ): String {
        val payload = buildString {
            append(url)
            parameters.toSortedMap().forEach { (name, value) ->
                append(name)
                append(value)
            }
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(AUTH_TOKEN.toByteArray(), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray()))
    }

    private companion object {
        const val ACCOUNT_SID = "AC00000000000000000000000000000000"
        const val AUTH_TOKEN = "test-auth-token"
        const val PROMETHE_NUMBER = "+14155550100"
        const val USER_NUMBER = "+14155550199"
        const val MESSAGE_SID = "SM00000000000000000000000000000000"
        const val PUBLIC_BASE_URL = "https://promethe.example.com"
        const val TWILIO_PATH = "/webhook/sms"
    }
}
