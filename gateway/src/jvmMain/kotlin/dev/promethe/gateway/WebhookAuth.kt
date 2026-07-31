package dev.promethe.gateway

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.header
import io.ktor.server.response.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// Webhook signature verification for external messaging platforms.
// Each platform uses its own signature scheme to authenticate incoming webhooks.
object WebhookAuth {
    // Verify Telegram webhook using secret token header
    suspend fun verifyTelegram(
        call: ApplicationCall,
        secretToken: String,
    ): Boolean {
        if (!requireConfigured(call, secretToken, "Telegram")) return false
        val headerToken = call.request.header("X-Telegram-Bot-Api-Secret-Token") ?: ""
        if (!timeSafeEquals(headerToken, secretToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid Telegram secret token"))
            return false
        }
        return true
    }

    // Verify WhatsApp (Meta) webhook using HMAC-SHA256 signature
    suspend fun verifyWhatsApp(
        call: ApplicationCall,
        appSecret: String,
        rawBody: String,
    ): Boolean {
        if (!requireConfigured(call, appSecret, "WhatsApp")) return false
        val signature = call.request.header("X-Hub-Signature-256") ?: ""
        if (!signature.startsWith("sha256=")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing WhatsApp signature"))
            return false
        }

        val expectedSig = "sha256=" + hmacSha256(appSecret, rawBody)
        if (!timeSafeEquals(signature, expectedSig)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid WhatsApp signature"))
            return false
        }
        return true
    }

    // Verify Slack webhook using signing secret
    suspend fun verifySlack(
        call: ApplicationCall,
        signingSecret: String,
        rawBody: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        if (!requireConfigured(call, signingSecret, "Slack")) return false
        val timestamp = call.request.header("X-Slack-Request-Timestamp") ?: ""
        val signature = call.request.header("X-Slack-Signature") ?: ""
        val parsedTimestamp = timestamp.toLongOrNull()
        if (parsedTimestamp == null || abs(nowEpochSeconds - parsedTimestamp) > MAX_TIMESTAMP_SKEW_SECONDS) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Stale or invalid Slack timestamp"))
            return false
        }

        val baseString = "v0:$timestamp:$rawBody"
        val expectedSig = "v0=" + hmacSha256(signingSecret, baseString)
        if (!timeSafeEquals(signature, expectedSig)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid Slack signature"))
            return false
        }
        return true
    }

    // Verify Discord webhook using Ed25519 signature (BouncyCastle)
    suspend fun verifyDiscord(
        call: ApplicationCall,
        publicKeyHex: String,
        rawBody: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        if (!requireConfigured(call, publicKeyHex, "Discord")) return false
        val signature = call.request.header("X-Signature-Ed25519")
        val timestamp = call.request.header("X-Signature-Timestamp")
        if (signature == null || timestamp == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing Discord signature headers"))
            return false
        }

        val parsedTimestamp = timestamp.toLongOrNull()
        if (parsedTimestamp == null || abs(nowEpochSeconds - parsedTimestamp) > MAX_TIMESTAMP_SKEW_SECONDS) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Stale or invalid Discord timestamp"))
            return false
        }

        return try {
            val message = (timestamp + rawBody).toByteArray()
            val signatureBytes = hexToBytes(signature)
            val publicKeyBytes = hexToBytes(publicKeyHex)

            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)

            if (!verifier.verifySignature(signatureBytes)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid Discord signature"))
                false
            } else {
                true
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Discord verification failed: ${e.message}"))
            false
        }
    }

    suspend fun verifySharedToken(
        call: ApplicationCall,
        configuredToken: String,
        channel: String,
    ): Boolean {
        if (!requireConfigured(call, configuredToken, channel)) return false
        val supplied = call.request.header("X-Promethe-Webhook-Token") ?: ""
        if (!timeSafeEquals(supplied, configuredToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid $channel webhook token"))
            return false
        }
        return true
    }

    suspend fun verifyChallengeToken(
        call: ApplicationCall,
        suppliedToken: String,
        configuredToken: String,
        channel: String,
    ): Boolean {
        if (!requireConfigured(call, configuredToken, channel)) return false
        if (!timeSafeEquals(suppliedToken, configuredToken)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid $channel verification token"))
            return false
        }
        return true
    }

    private fun hmacSha256(
        key: String,
        data: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun requireConfigured(
        call: ApplicationCall,
        value: String,
        channel: String,
    ): Boolean {
        if (value.isNotBlank()) return true
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "$channel webhook is not configured"))
        return false
    }

    private fun timeSafeEquals(
        a: String,
        b: String,
    ): Boolean =
        java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex input must contain an even number of characters" }
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val high = Character.digit(hex[i], 16)
            val low = Character.digit(hex[i + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid hexadecimal input" }
            data[i / 2] = ((high shl 4) + low).toByte()
        }
        return data
    }

    private const val MAX_TIMESTAMP_SKEW_SECONDS = 300L
}
