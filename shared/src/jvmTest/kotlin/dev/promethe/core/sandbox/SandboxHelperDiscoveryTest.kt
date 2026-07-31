package dev.promethe.core.sandbox

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxHelperDiscoveryTest {
    @Test
    fun `configured helper is accepted when checksum matches`() {
        val helper = Files.createTempFile("promethe-helper-test", executableSuffix())
        try {
            helper.writeText("trusted helper")
            helper.toFile().setExecutable(true)
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(Files.readAllBytes(helper))
                    .joinToString("") { byte -> "%02x".format(byte) }

            val discovered =
                SandboxHelperDiscovery(
                    environment =
                        mapOf(
                            SANDBOX_HELPER_ENV to helper.toString(),
                            SANDBOX_HELPER_SHA256_ENV to hash,
                        ),
                ).discover()

            assertTrue(discovered.isSuccess)
            assertEquals(helper.toAbsolutePath().normalize(), discovered.getOrThrow().path)
        } finally {
            helper.deleteIfExists()
        }
    }

    @Test
    fun `configured helper is rejected when checksum differs`() {
        val helper = Files.createTempFile("promethe-helper-test", executableSuffix())
        try {
            helper.writeText("tampered helper")
            helper.toFile().setExecutable(true)

            val discovered =
                SandboxHelperDiscovery(
                    environment =
                        mapOf(
                            SANDBOX_HELPER_ENV to helper.toString(),
                            SANDBOX_HELPER_SHA256_ENV to "00".repeat(32),
                        ),
                ).discover()

            assertTrue(discovered.isFailure)
        } finally {
            helper.deleteIfExists()
        }
    }

    @Test
    fun `configured helper is rejected without checksum`() {
        val helper = Files.createTempFile("promethe-helper-test", executableSuffix())
        try {
            helper.writeText("unverified helper")
            helper.toFile().setExecutable(true)

            val discovered =
                SandboxHelperDiscovery(
                    environment = mapOf(SANDBOX_HELPER_ENV to helper.toString()),
                ).discover()

            assertTrue(discovered.isFailure)
        } finally {
            helper.deleteIfExists()
        }
    }

    @Test
    fun `configured helper is verified again after discovery`() {
        val helper = Files.createTempFile("promethe-helper-test", executableSuffix())
        try {
            helper.writeText("trusted helper")
            helper.toFile().setExecutable(true)
            val environment = configuredEnvironment(helper)

            assertTrue(SandboxHelperDiscovery(environment = environment).discover().isSuccess)
            helper.writeText("replacement helper")

            assertFailsWith<IllegalArgumentException> {
                verifiedSandboxHelperLaunchCopy(helper, environment)
            }
        } finally {
            helper.deleteIfExists()
        }
    }

    @Test
    fun `verified launch copy is isolated from later source replacement`() {
        val helper = Files.createTempFile("promethe-helper-test", executableSuffix())
        var launchCopy: java.nio.file.Path? = null
        try {
            helper.writeText("trusted helper")
            helper.toFile().setExecutable(true)
            launchCopy = verifiedSandboxHelperLaunchCopy(helper, configuredEnvironment(helper))

            helper.writeText("replacement helper")

            assertEquals("trusted helper", launchCopy.readText())
        } finally {
            launchCopy?.deleteIfExists()
            helper.deleteIfExists()
        }
    }

    private fun configuredEnvironment(helper: java.nio.file.Path): Map<String, String> =
        mapOf(
            SANDBOX_HELPER_ENV to helper.toString(),
            SANDBOX_HELPER_SHA256_ENV to
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(Files.readAllBytes(helper))
                    .joinToString("") { byte -> "%02x".format(byte) },
        )

    private fun executableSuffix(): String = if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else ""
}
