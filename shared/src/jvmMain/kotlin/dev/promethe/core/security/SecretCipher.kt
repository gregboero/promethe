package dev.promethe.core.security

import dev.promethe.core.config.ConfigProvider
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts persisted gateway secrets with a process-supplied AES-256 key.
 * The key is deliberately never generated or written by Promethe.
 */
class SecretCipher private constructor(
    private val activeKeyId: String,
    private val activeKey: SecretKeySpec,
    private val decryptionKeys: Map<String, SecretKeySpec>,
) {
    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, activeKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, activeKeyId, Base64.getUrlEncoder().withoutPadding().encodeToString(iv), Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)).joinToString(":")
    }

    fun decrypt(envelope: String): String {
        val parts = envelope.split(':')
        return when {
            parts.size == 4 && parts[0] == VERSION -> {
                val key = decryptionKeys[parts[1]] ?: error("Encrypted secret references unknown key id '${parts[1]}'")
                decryptWithKey(key, parts[2], parts[3])
            }

            parts.size == 3 && parts[0] == LEGACY_VERSION -> {
                decryptionKeys.values.firstNotNullOfOrNull { key ->
                    runCatching { decryptWithKey(key, parts[1], parts[2]) }.getOrNull()
                } ?: error("Unable to decrypt legacy secret with the configured key ring")
            }

            else -> {
                error("Unsupported encrypted secret format")
            }
        }
    }

    fun needsRotation(envelope: String): Boolean = !envelope.startsWith("$VERSION:$activeKeyId:")

    /** HMAC signature for short-lived opaque protocol state such as OAuth callbacks. */
    fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(activeKey.encoded, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun verifySignature(
        value: String,
        signature: String,
    ): Boolean =
        decryptionKeys.values.any { key ->
            runCatching {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
                java.security.MessageDigest.isEqual(
                    mac.doFinal(value.toByteArray(Charsets.UTF_8)),
                    Base64.getUrlDecoder().decode(signature),
                )
            }.getOrDefault(false)
        }

    private fun decryptWithKey(
        key: SecretKeySpec,
        encodedIv: String,
        encodedCipherText: String,
    ): String {
        val iv = Base64.getUrlDecoder().decode(encodedIv)
        require(iv.size == IV_LENGTH) { "Invalid encrypted secret IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(Base64.getUrlDecoder().decode(encodedCipherText)).toString(Charsets.UTF_8)
    }

    companion object {
        private const val VERSION = "v2"
        private const val LEGACY_VERSION = "v1"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun fromConfig(required: Boolean = false): SecretCipher? {
            // A persisted Settings value would defeat the purpose of a master
            // key. It is accepted from the process environment only.
            val encoded = System.getenv("PROMETHE_MASTER_KEY")?.trim().orEmpty()
            if (encoded.isBlank()) {
                if (required) error("PROMETHE_MASTER_KEY is required when remote access or OAuth is enabled")
                return null
            }
            val activeKeyId = System.getenv("PROMETHE_MASTER_KEY_ID")?.trim().orEmpty().ifBlank { "primary" }
            validateKeyId(activeKeyId)
            val previous = parsePreviousKeys(System.getenv("PROMETHE_PREVIOUS_MASTER_KEYS").orEmpty())
            return fromKeyRing(activeKeyId, encoded, previous)
        }

        /** Validates a supplied Base64 key without storing it anywhere. */
        fun fromBase64Key(encoded: String): SecretCipher = fromKeyRing("primary", encoded, emptyMap())

        fun fromKeyRing(
            activeKeyId: String,
            activeEncodedKey: String,
            previousEncodedKeys: Map<String, String>,
        ): SecretCipher {
            validateKeyId(activeKeyId)
            require(activeKeyId !in previousEncodedKeys) { "Active key id must not be repeated in PROMETHE_PREVIOUS_MASTER_KEYS" }
            val activeKey = decodeKey(activeEncodedKey)
            val keys = linkedMapOf(activeKeyId to activeKey)
            previousEncodedKeys.forEach { (id, encoded) ->
                validateKeyId(id)
                keys[id] = decodeKey(encoded)
            }
            return SecretCipher(activeKeyId, activeKey, keys)
        }

        fun requireForEnabledFeatures(): SecretCipher? {
            val config = ConfigProvider.get()
            val required =
                config.getBoolean("REMOTE_ACCESS_ENABLED", false) ||
                    config.getBoolean("OAUTH_ENABLED", false)
            return fromConfig(required)
        }

        private fun decodeKey(encoded: String): SecretKeySpec {
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                error("PROMETHE master keys must be Base64 encoded")
            }
            require(bytes.size == 32) { "PROMETHE master keys must decode to exactly 32 bytes" }
            return SecretKeySpec(bytes, "AES")
        }

        private fun parsePreviousKeys(value: String): Map<String, String> =
            value
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .associate { entry ->
                    val separator = entry.indexOf('=')
                    require(separator > 0 && separator < entry.lastIndex) {
                        "PROMETHE_PREVIOUS_MASTER_KEYS must use id=base64 entries"
                    }
                    entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
                }

        private fun validateKeyId(value: String) {
            require(value.matches(Regex("[A-Za-z0-9._-]{1,32}"))) {
                "PROMETHE master key ids must contain 1-32 letters, digits, dot, underscore, or dash"
            }
        }
    }
}
