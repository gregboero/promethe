package dev.promethe.gateway

import com.twilio.http.TwilioRestClient
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import dev.promethe.api.ErrorResponse
import dev.promethe.core.config.ConfigProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val twilioLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

internal fun interface TwilioAgentExecutor {
    suspend fun execute(
        sessionId: String,
        text: String,
    ): String
}

internal fun interface TwilioReplySender {
    suspend fun send(
        to: String,
        body: String,
    )
}

internal class TwilioSdkReplySender : TwilioReplySender {
    override suspend fun send(
        to: String,
        body: String,
    ) {
        val config = ConfigProvider.get()
        val accountSid = config.get("TWILIO_ACCOUNT_SID", "")
        val authToken = config.get("TWILIO_AUTH_TOKEN", "")
        val fromNumber = config.get("TWILIO_PHONE_NUMBER", "")
        require(accountSid.isNotBlank() && authToken.isNotBlank() && fromNumber.isNotBlank()) {
            "Twilio is not fully configured"
        }

        val client = TwilioRestClient.Builder(accountSid, authToken).build()
        val parts = splitTwilioReply(body)
        withContext(Dispatchers.IO) {
            parts.forEach { part ->
                Message
                    .creator(PhoneNumber(to), PhoneNumber(fromNumber), part)
                    .create(client)
            }
        }
    }
}

internal fun Route.twilioWebhookRoute(
    agentExecutor: TwilioAgentExecutor,
    publicBaseUrl: String?,
    replySender: TwilioReplySender = TwilioSdkReplySender(),
) {
    post("/webhook/sms") {
        val config = ConfigProvider.get()
        val accountSid = config.get("TWILIO_ACCOUNT_SID", "")
        val authToken = config.get("TWILIO_AUTH_TOKEN", "")
        val fromNumber = config.get("TWILIO_PHONE_NUMBER", "")
        if (accountSid.isBlank() || authToken.isBlank() || fromNumber.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Twilio webhook is not configured"))
            return@post
        }
        if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
            call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("Twilio webhook must be form encoded"))
            return@post
        }

        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        val form = parseQueryString(rawBody)
        val parameters = form.names().associateWith { name -> form[name].orEmpty() }
        val requestUrl = call.twilioRequestUrl(publicBaseUrl)
        if (!WebhookAuth.verifyTwilio(call, authToken, requestUrl, parameters)) return@post

        if (parameters["AccountSid"] != accountSid) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid Twilio account"))
            return@post
        }

        val messageSid = parameters["MessageSid"].orEmpty()
        val from = parameters["From"].orEmpty()
        val text = parameters["Body"].orEmpty()
        if (messageSid.isBlank() || from.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed Twilio webhook payload"))
            return@post
        }

        if (!WebhookReplayGuard.accept("twilio", messageSid)) {
            call.respondText(EMPTY_TWIML, ContentType.Application.Xml, HttpStatusCode.OK)
            return@post
        }

        call.respondText(EMPTY_TWIML, ContentType.Application.Xml, HttpStatusCode.OK)
        if (text.isBlank()) return@post

        call.application.launch {
            try {
                val response = agentExecutor.execute("sms-$from", text)
                val reply = response.toSafeTwilioReply()
                replySender.send(from, reply)
            } catch (error: Exception) {
                twilioLogger.warn(error) { "Twilio message processing failed [messageSid=$messageSid]" }
            }
        }
    }
}

internal fun splitTwilioReply(
    body: String,
    maxChars: Int = 1_500,
    maxParts: Int = 3,
): List<String> {
    require(maxChars >= 16)
    require(maxParts >= 1)
    var remaining = body.trim()
    if (remaining.isEmpty()) return listOf(TWILIO_FAILURE_REPLY)

    val parts = mutableListOf<String>()
    while (remaining.isNotEmpty() && parts.size < maxParts) {
        if (remaining.length <= maxChars) {
            parts += remaining
            remaining = ""
            continue
        }
        val whitespace = remaining.lastIndexOf(' ', startIndex = maxChars)
        val splitAt = whitespace.takeIf { it >= maxChars / 2 } ?: maxChars
        parts += remaining.substring(0, splitAt).trimEnd()
        remaining = remaining.substring(splitAt).trimStart()
    }
    if (remaining.isNotEmpty()) {
        parts[parts.lastIndex] = parts.last().take(maxChars - 3).trimEnd() + "..."
    }
    return parts
}

private fun ApplicationCall.twilioRequestUrl(publicBaseUrl: String?): String {
    if (!publicBaseUrl.isNullOrBlank()) return publicBaseUrl.trimEnd('/') + request.uri

    val scheme = request.local.scheme
    val port = request.port()
    val portSuffix =
        if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
            ""
        } else {
            ":$port"
        }
    return "$scheme://${request.host()}$portSuffix${request.uri}"
}

private fun String.toSafeTwilioReply(): String =
    trim().takeIf { it.isNotEmpty() && !it.startsWith("Error:", ignoreCase = true) }
        ?: TWILIO_FAILURE_REPLY

private const val EMPTY_TWIML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>"
private const val TWILIO_FAILURE_REPLY = "Promethe could not process your message. Please try again."
