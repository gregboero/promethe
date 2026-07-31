package dev.promethe.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CredentialsStoreTest {
    private lateinit var home: File
    private var previousHome: String? = null

    @BeforeTest
    fun setUp() {
        home = kotlin.io.path.createTempDirectory("promethe-credential-store").toFile()
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
    fun `concurrent updates preserve every credential entry and leave valid json`() =
        runTest {
            CredentialsStore.save(CredentialsStore.Credentials(apiKey = "pk-prom-test"))

            (0 until 20)
                .map { index ->
                    launch(Dispatchers.Default) {
                        CredentialsStore.update { credentials ->
                            credentials.copy(providerSecrets = credentials.providerSecrets + ("provider-$index" to "secret-$index"))
                        }
                    }
                }.joinAll()

            val saved = assertNotNull(CredentialsStore.load())
            assertEquals("pk-prom-test", saved.apiKey)
            assertEquals(20, saved.providerSecrets.size)
            assertTrue(home.resolve("credentials.json").readText().startsWith("{"))
            assertTrue(home.listFiles().orEmpty().none { it.name.startsWith(".credentials-") })
        }
}
