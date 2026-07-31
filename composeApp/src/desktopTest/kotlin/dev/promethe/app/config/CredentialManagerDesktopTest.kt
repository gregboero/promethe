package dev.promethe.app.config

import dev.promethe.core.CredentialsStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CredentialManagerDesktopTest {
    private lateinit var home: File
    private var previousHome: String? = null

    @BeforeTest
    fun setUp() {
        home = kotlin.io.path.createTempDirectory("promethe-credentials-test").toFile()
        previousHome = System.getProperty("promethe.home")
        System.setProperty("promethe.home", home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        if (previousHome == null) {
            System.clearProperty("promethe.home")
        } else {
            System.setProperty("promethe.home", previousHome)
        }
        home.deleteRecursively()
    }

    @Test
    fun `desktop save merges settings without erasing gateway secrets`() {
        CredentialsStore.save(
            CredentialsStore.Credentials(
                apiKey = "pk-prom-existing",
                providerSecrets = mapOf("github" to "provider-secret", "openai" to "openai-secret"),
                runtimeConfig = mapOf("GITHUB_TOKEN" to "github-secret"),
                ragEnabled = true,
                ragEmbeddingApiKey = "rag-secret",
                voiceS2sProvider = "gemini_live",
            ),
        )

        CredentialManager.save(
            AppCredentials(
                apiKey = "",
                llmProvider = "google",
                llmApiKeys = mapOf("google" to "google-secret"),
                executionTimeoutMs = 45_000,
                maxContextTokens = 256_000,
                approvalMode = "all",
                language = "fr",
            ),
        )

        val saved = assertNotNull(CredentialsStore.load())
        assertEquals("pk-prom-existing", saved.apiKey)
        assertEquals("openai-secret", saved.providerSecrets["openai"])
        assertEquals("github-secret", saved.runtimeConfig["GITHUB_TOKEN"])
        assertEquals(true, saved.ragEnabled)
        assertEquals("rag-secret", saved.ragEmbeddingApiKey)
        assertEquals("gemini_live", saved.voiceS2sProvider)
        assertEquals(45_000, saved.executionTimeoutMs)
        assertEquals(256_000, saved.maxContextTokens)
        assertEquals("all", saved.approvalMode)
        assertEquals("fr", saved.language)
    }
}
