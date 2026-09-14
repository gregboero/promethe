package dev.promethe.core

import dev.promethe.core.config.ConfigProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TwilioToolTest {
    @AfterTest
    fun clearTwilioOverrides() {
        ConfigProvider.clearOverride("TWILIO_ACCOUNT_SID")
        ConfigProvider.clearOverride("TWILIO_AUTH_TOKEN")
        ConfigProvider.clearOverride("TWILIO_PHONE_NUMBER")
    }

    @Test
    fun `credentials saved after registration are used without recreating the tool`() =
        runTest {
            var requestedUrl = ""
            var authorization = ""
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestedUrl = request.url.toString()
                        authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                        respond(
                            content = """{"sid":"SM123","status":"queued"}""",
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val tool = TwilioTool(client)

            assertTrue(tool.execute(TwilioArgs(action = "send_sms", to = "+14155550199", body = "hello")).startsWith("[ERROR]"))

            ConfigProvider.setOverride("TWILIO_ACCOUNT_SID", "AC123")
            ConfigProvider.setOverride("TWILIO_AUTH_TOKEN", "token")
            ConfigProvider.setOverride("TWILIO_PHONE_NUMBER", "+14155550100")
            val result = tool.execute(TwilioArgs(action = "send_sms", to = "+14155550199", body = "hello"))

            assertTrue(result.startsWith("SMS sent"))
            assertEquals("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json", requestedUrl)
            assertTrue(authorization.startsWith("Basic "))
        }
}
