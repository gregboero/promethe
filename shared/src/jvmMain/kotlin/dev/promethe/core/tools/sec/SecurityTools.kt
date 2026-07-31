package dev.promethe.core.tools.sec

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ── Args ─────────────────────────────────────────────────────────────

@Serializable
data class HashArgs(
    @property:LLMDescription("Text to hash.")
    val input: String,
    @property:LLMDescription("Algorithm: 'sha256', 'sha512', 'md5', or 'hmac-sha256'. Default 'sha256'.")
    val algorithm: String = "sha256",
    @property:LLMDescription("Secret key for HMAC algorithms. Required when algorithm is hmac-*.")
    val key: String = "",
)

@Serializable
data class EncryptArgs(
    @property:LLMDescription("Action: 'encrypt' or 'decrypt'.")
    val action: String = "encrypt",
    @property:LLMDescription("Text to encrypt or Base64 ciphertext to decrypt.")
    val input: String,
    @property:LLMDescription("Encryption key (will be padded/truncated to 32 bytes for AES-256).")
    val key: String,
)

@Serializable
data class CertCheckArgs(
    @property:LLMDescription("Hostname to check SSL/TLS certificate (e.g. 'google.com').")
    val host: String,
    @property:LLMDescription("Port. Default 443.")
    val port: Int = 443,
)

// ── Tools ────────────────────────────────────────────────────────────

class HashTool :
    SimpleTool<HashArgs>(
        argsType = typeToken<HashArgs>(),
        name = "hash",
        description = "Compute hash digests: SHA-256, SHA-512, MD5, or HMAC-SHA256.",
    ) {
    override suspend fun execute(args: HashArgs): String {
        return try {
            val hex = when (args.algorithm.lowercase()) {
                "sha256" -> {
                    digest("SHA-256", args.input)
                }

                "sha512" -> {
                    digest("SHA-512", args.input)
                }

                "md5" -> {
                    digest("MD5", args.input)
                }

                "hmac-sha256" -> {
                    if (args.key.isBlank()) return "[ERROR] Key required for HMAC."
                    hmac("HmacSHA256", args.input, args.key)
                }

                else -> {
                    return "[ERROR] Unsupported algorithm '${args.algorithm}'. Use: sha256, sha512, md5, hmac-sha256."
                }
            }
            "${args.algorithm.uppercase()}: $hex"
        } catch (e: Exception) {
            "[ERROR] Hash failed: ${e.message}"
        }
    }

    private fun digest(
        algo: String,
        input: String,
    ): String {
        val md = MessageDigest.getInstance(algo)
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmac(
        algo: String,
        input: String,
        key: String,
    ): String {
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key.toByteArray(), algo))
        return mac.doFinal(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

class EncryptTool :
    SimpleTool<EncryptArgs>(
        argsType = typeToken<EncryptArgs>(),
        name = "encrypt",
        description = "AES-256-CBC encrypt or decrypt text. Returns Base64 ciphertext or plaintext.",
    ) {
    override suspend fun execute(args: EncryptArgs): String =
        try {
            val keyBytes = args.key.toByteArray().copyOf(32) // Pad/truncate to 32 bytes
            val secretKey = SecretKeySpec(keyBytes, "AES")
            when (args.action.lowercase()) {
                "encrypt" -> {
                    val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
                    val encrypted = cipher.doFinal(args.input.toByteArray())
                    val combined = iv + encrypted
                    "Encrypted: ${java.util.Base64.getEncoder().encodeToString(combined)}"
                }

                "decrypt" -> {
                    val combined = java.util.Base64.getDecoder().decode(args.input)
                    val iv = combined.copyOfRange(0, 16)
                    val ciphertext = combined.copyOfRange(16, combined.size)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
                    "Decrypted: ${String(cipher.doFinal(ciphertext))}"
                }

                else -> {
                    "[ERROR] Unknown action '${args.action}'. Use: encrypt, decrypt."
                }
            }
        } catch (e: Exception) {
            "[ERROR] ${args.action} failed: ${e.message}"
        }
}

class CertCheckTool :
    SimpleTool<CertCheckArgs>(
        argsType = typeToken<CertCheckArgs>(),
        name = "cert_check",
        description = "Inspect SSL/TLS certificate for a hostname: issuer, expiry, subject, chain.",
    ) {
    override suspend fun execute(args: CertCheckArgs): String =
        try {
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val factory = sslContext.socketFactory
            val socket = factory.createSocket(args.host, args.port) as javax.net.ssl.SSLSocket
            socket.soTimeout = 10_000
            socket.startHandshake()
            val certs = socket.session.peerCertificates
            socket.close()

            val sb = StringBuilder()
            sb.appendLine("Host: ${args.host}:${args.port}")
            sb.appendLine("Protocol: ${socket.session.protocol}")
            sb.appendLine("Cipher: ${socket.session.cipherSuite}")
            sb.appendLine("Chain (${certs.size} cert(s)):")
            certs.forEachIndexed { i, cert ->
                if (cert is java.security.cert.X509Certificate) {
                    sb.appendLine("  [$i] Subject: ${cert.subjectX500Principal}")
                    sb.appendLine("      Issuer: ${cert.issuerX500Principal}")
                    sb.appendLine("      Valid: ${cert.notBefore} → ${cert.notAfter}")
                    sb.appendLine("      Serial: ${cert.serialNumber}")
                    val daysLeft = ((cert.notAfter.time - System.currentTimeMillis()) / 86400000)
                    sb.appendLine("      Days until expiry: $daysLeft")
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "[ERROR] Certificate check failed: ${e.message}"
        }
}
