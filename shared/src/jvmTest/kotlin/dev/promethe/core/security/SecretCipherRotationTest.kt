package dev.promethe.core.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretCipherRotationTest {
    @Test
    fun `active key encrypts while previous keys decrypt`() {
        val oldKey = encodedKey(1)
        val newKey = encodedKey(2)
        val oldCipher = SecretCipher.fromKeyRing("old", oldKey, emptyMap())
        val oldEnvelope = oldCipher.encrypt("secret")
        val rotatingCipher = SecretCipher.fromKeyRing("new", newKey, mapOf("old" to oldKey))

        assertEquals("secret", rotatingCipher.decrypt(oldEnvelope))
        assertTrue(rotatingCipher.needsRotation(oldEnvelope))
        val rotated = rotatingCipher.encrypt(rotatingCipher.decrypt(oldEnvelope))
        assertFalse(rotatingCipher.needsRotation(rotated))
        assertTrue(rotated.startsWith("v2:new:"))
    }

    @Test
    fun `legacy v1 envelope remains readable during migration`() {
        val key = encodedKey(3)
        val cipher = SecretCipher.fromBase64Key(key)
        val current = cipher.encrypt("legacy-secret").split(':')
        val legacy = listOf("v1", current[2], current[3]).joinToString(":")

        assertEquals("legacy-secret", cipher.decrypt(legacy))
        assertTrue(cipher.needsRotation(legacy))
    }

    private fun encodedKey(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (seed + index).toByte() })
}
