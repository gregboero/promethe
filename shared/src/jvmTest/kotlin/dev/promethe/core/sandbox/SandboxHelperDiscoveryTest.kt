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
    fun `Windows setup script is packaged with the helper`() {
        if (!System.getProperty("os.name").contains("win", ignoreCase = true)) return
        val resource =
            requireNotNull(javaClass.classLoader.getResourceAsStream("sandbox/windows/setup.ps1")) {
                "sandbox/windows/setup.ps1 must be present in JVM resources"
            }
        resource.use { input ->
            val script = input.bufferedReader().readText()
            assertTrue(script.contains("PrometheSbxOffline"))
            assertTrue(script.contains("PrometheSbxOnline"))
            assertTrue(script.contains("PrometheSbxWriters"))
            assertTrue(!script.contains("PrometheSandboxOffline"))
            assertTrue(script.contains("Remove-LocalGroup -Name \$legacyWriterGroup"))
            assertTrue(script.contains("Add-Type -AssemblyName System.Security"))
            assertTrue(script.contains("PrometheSandboxAppContainer"))
            assertTrue(script.contains("appContainerSid = \$appContainerSid"))
            assertTrue(script.contains("\$setupVersion = 6"))
            assertTrue(script.contains("\$runnerVersion = 4"))
            assertTrue(script.contains("-WindowStyle Hidden"))
            assertTrue(!script.contains("ProfileImagePath"))
            assertTrue(script.contains("RandomNumberGenerator]::Create()"))
            assertTrue(script.contains("GetBytes(\$bytes)"))
            assertTrue(!script.contains("RandomNumberGenerator]::Fill"))
        }
    }

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

    @Test
    fun `helper environment retains required Windows sandbox roots without secrets`() {
        val environment =
            sandboxHelperEnvironment(
                mapOf(
                    "PATH" to "C:\\Windows\\System32",
                    "ProgramData" to "C:\\ProgramData",
                    "SystemDrive" to "C:",
                    "SystemRoot" to "C:\\Windows",
                    "PROMETHE_MASTER_KEY" to "secret",
                    "XAI_API_KEY" to "secret",
                ),
            )

        assertEquals("C:\\ProgramData", environment["ProgramData"])
        assertEquals("C:", environment["SystemDrive"])
        assertEquals("C:\\Windows", environment["SystemRoot"])
        assertTrue("PROMETHE_MASTER_KEY" !in environment)
        assertTrue("XAI_API_KEY" !in environment)
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
