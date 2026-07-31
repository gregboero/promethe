package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DbSearchTest {
    private lateinit var database: PrometheDatabase

    @BeforeTest
    fun setup() {
        database = DatabaseFactory.createInMemory()
    }

    @Test
    fun testFts5Search() =
        runBlocking {
            val sessionId = "test-session-001"

            database.insertSession(sessionId, 123456789L, "{}")
            database.insertMessage(sessionId, "user", "Lancer la recherche sémantique avec un motclef unique", 123456790L)
            database.insertMessage(sessionId, "assistant", "Réponse générée", 123456791L)

            val results = database.searchMessages("motclef")
            assertEquals(1, results.size)
            assertEquals("Lancer la recherche sémantique avec un motclef unique", results[0].content)
        }
}
