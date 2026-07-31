package dev.promethe.app.navigation

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BackStackPersistenceTest {
    private lateinit var storageDir: File

    @BeforeTest
    fun setUp() {
        storageDir = Files.createTempDirectory("promethe-navigation-test").toFile()
        System.setProperty("promethe.storage.dir", storageDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("promethe.storage.dir")
        storageDir.deleteRecursively()
    }

    @Test
    fun saveAndRestoreSimpleRoute() {
        BackStackPersistence.clear()
        BackStackPersistence.save(listOf(PrometheRoute.Sessions))
        val restored = BackStackPersistence.restore()
        assertNotNull(restored)
        assertEquals(1, restored.size)
        assertEquals(PrometheRoute.Sessions, restored[0])
        BackStackPersistence.clear()
    }

    @Test
    fun saveAndRestoreMultipleRoutes() {
        BackStackPersistence.clear()
        val routes = listOf(PrometheRoute.Sessions, PrometheRoute.Chat("test-123"), PrometheRoute.Settings)
        BackStackPersistence.save(routes)
        val restored = BackStackPersistence.restore()
        assertNotNull(restored)
        assertEquals(3, restored.size)
        assertEquals(PrometheRoute.Sessions, restored[0])
        assertEquals(PrometheRoute.Chat("test-123"), restored[1])
        assertEquals(PrometheRoute.Settings, restored[2])
        BackStackPersistence.clear()
    }

    @Test
    fun restoreReturnsNullWhenEmpty() {
        BackStackPersistence.clear()
        assertNull(BackStackPersistence.restore())
    }

    @Test
    fun clearRemovesSavedState() {
        BackStackPersistence.save(listOf(PrometheRoute.Agents))
        BackStackPersistence.clear()
        assertNull(BackStackPersistence.restore())
    }

    @Test
    fun roundTripAllRoutes() {
        BackStackPersistence.clear()
        val allRoutes = listOf(
            PrometheRoute.Sessions,
            PrometheRoute.Chat("session-abc"),
            PrometheRoute.Agents,
            PrometheRoute.Monitor,
            PrometheRoute.Stats,
            PrometheRoute.Memory,
            PrometheRoute.Gepa,
            PrometheRoute.Settings,
            PrometheRoute.Tools,
            PrometheRoute.Channels,
            PrometheRoute.Scheduler,
            PrometheRoute.Mcp,
            PrometheRoute.Plugins,
            PrometheRoute.Knowledge,
            PrometheRoute.Orchestrator,
        )
        BackStackPersistence.save(allRoutes)
        val restored = BackStackPersistence.restore()
        assertNotNull(restored)
        assertEquals(allRoutes, restored)
        BackStackPersistence.clear()
    }
}
