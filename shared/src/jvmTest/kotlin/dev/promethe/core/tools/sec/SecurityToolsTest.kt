package dev.promethe.core.tools.sec

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class SecurityToolsTest {
    @Test
    fun testHashTool() =
        runTest {
            val tool = HashTool()

            // SHA-256
            val shaResult = tool.execute(HashArgs(input = "hello", algorithm = "sha256"))
            assertTrue(
                shaResult.contains("SHA256: 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                "SHA-256 of 'hello' incorrect: $shaResult",
            )

            // MD5
            val mdResult = tool.execute(HashArgs(input = "hello", algorithm = "md5"))
            assertTrue(mdResult.contains("MD5: 5d41402abc4b2a76b9719d911017c592"), "MD5 of 'hello' incorrect: $mdResult")

            // HMAC-SHA256
            val hmacResult = tool.execute(HashArgs(input = "hello", algorithm = "hmac-sha256", key = "secret"))
            assertTrue(hmacResult.contains("HMAC-SHA256:"), "HMAC-SHA256 result incorrect: $hmacResult")
        }

    @Test
    fun testEncryptTool() =
        runTest {
            val tool = EncryptTool()
            val plain = "Secret message to encrypt"
            val key = "my-super-secret-key-123"

            // Encrypt
            val encResult = tool.execute(EncryptArgs(action = "encrypt", input = plain, key = key))
            assertTrue(encResult.startsWith("Encrypted: "), "Result should show encrypted")
            val base64Cipher = encResult.removePrefix("Encrypted: ")

            // Decrypt
            val decResult = tool.execute(EncryptArgs(action = "decrypt", input = base64Cipher, key = key))
            assertEquals("Decrypted: $plain", decResult)
        }

    @Test
    fun testCertCheckTool() =
        runTest {
            val tool = CertCheckTool()
            // Cert check on invalid port or non-existent domain should catch exception and return [ERROR]
            val result = tool.execute(CertCheckArgs(host = "localhost", port = 1))
            assertTrue(result.contains("[ERROR]"), "SSL handshake failure should return error: $result")
        }
}
