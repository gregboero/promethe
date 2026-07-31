package dev.promethe.core

import java.security.SecureRandom
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id + random salt password hashing.
 *
 * - Never stores plaintext passwords.
 * - Uses constant-time comparison to prevent timing attacks.
 */
object PasswordHasher {
    private const val SALT_LENGTH = 16 // bytes
    private const val HASH_LENGTH = 32 // bytes
    private const val ITERATIONS = 3
    private const val MEMORY_KIB = 65_536
    private const val PARALLELISM = 1
    private const val PREFIX = "argon2id"

    /**
     * Hash a plaintext password with a random salt.
     * @return Pair(encodedHash, hexSalt)
     */
    fun hash(password: String): Pair<String, String> {
        val saltBytes = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(saltBytes)
        val salt = saltBytes.toHex()
        val hash = "$PREFIX\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM\$$salt\$${computeHash(password, salt).toHex()}"
        return Pair(hash, salt)
    }

    /**
     * Verify a plaintext password against a stored hash + salt.
     * Uses constant-time comparison.
     */
    fun verify(
        password: String,
        storedHash: String,
        storedSalt: String,
    ): Boolean {
        if (!storedHash.startsWith("$PREFIX\$")) return false
        val parts = storedHash.split('$')
        if (parts.size < 5) return false
        val encodedSalt = parts[parts.lastIndex - 1].takeIf { it.isNotBlank() } ?: storedSalt
        val expected = parts.last().hexToBytesOrNull() ?: return false
        val computed = computeHash(password, encodedSalt)
        return java.security.MessageDigest.isEqual(computed, expected)
    }

    private fun computeHash(
        password: String,
        hexSalt: String,
    ): ByteArray {
        val salt = hexSalt.hexToBytesOrNull() ?: return ByteArray(HASH_LENGTH)
        val params =
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KIB)
                .withParallelism(PARALLELISM)
                .build()
        return ByteArray(HASH_LENGTH).also { output ->
            Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toByteArray(Charsets.UTF_8), output)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
