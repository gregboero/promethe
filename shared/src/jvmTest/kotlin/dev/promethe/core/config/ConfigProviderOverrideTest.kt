package dev.promethe.core.config

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Process-level override mechanism (used by explicit CLI flags such as
 * --allow-local-exec). Overrides must beat every other config source and
 * survive provider re-initialization.
 */
class ConfigProviderOverrideTest {
    @AfterTest
    fun cleanup() {
        ConfigProvider.clearOverride("EXEC_BACKEND")
        ConfigProvider.clearOverride("SOME_TEST_FLAG")
        ConfigProvider.initialize(SystemEnvConfigProvider)
    }

    @Test
    fun testDefaultWithoutOverrideStaysSecure() {
        // Secure-by-default invariant: no override, no env → docker
        assertEquals("docker", ConfigProvider.get().get("EXEC_BACKEND", "docker"))
    }

    @Test
    fun testOverrideBeatsDefault() {
        ConfigProvider.setOverride("EXEC_BACKEND", "local")
        assertEquals("local", ConfigProvider.get().get("EXEC_BACKEND", "docker"))
    }

    @Test
    fun testOverrideBeatsInitializedProvider() {
        // Simulates the --allow-local-exec flow: the flag sets the override
        // BEFORE launchDesktop/launchDaemon initialize the merged provider.
        ConfigProvider.setOverride("EXEC_BACKEND", "local")
        ConfigProvider.initialize(MergedConfigProvider { mapOf("EXEC_BACKEND" to "docker") })
        assertEquals(
            "local",
            ConfigProvider.get().get("EXEC_BACKEND", "docker"),
            "A CLI-flag override must survive ConfigProvider.initialize()",
        )
    }

    @Test
    fun testClearOverrideRestoresUnderlyingSource() {
        ConfigProvider.initialize(MergedConfigProvider { mapOf("EXEC_BACKEND" to "docker") })
        ConfigProvider.setOverride("EXEC_BACKEND", "local")
        ConfigProvider.clearOverride("EXEC_BACKEND")
        assertEquals("docker", ConfigProvider.get().get("EXEC_BACKEND", "fallback"))
    }

    @Test
    fun testBooleanOverrideParsing() {
        ConfigProvider.setOverride("SOME_TEST_FLAG", "true")
        assertEquals(true, ConfigProvider.get().getBoolean("SOME_TEST_FLAG", false))
        ConfigProvider.setOverride("SOME_TEST_FLAG", "nope")
        assertFalse(ConfigProvider.get().getBoolean("SOME_TEST_FLAG", true), "Non-'true' override must parse as false")
    }

    @Test
    fun testOverrideKeysAppearInKeys() {
        ConfigProvider.setOverride("SOME_TEST_FLAG", "x")
        assertEquals(true, "SOME_TEST_FLAG" in ConfigProvider.get().keys())
    }
}
