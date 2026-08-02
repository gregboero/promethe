package dev.promethe.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotReloadWatcherTest {
    @Test
    fun `only files with registered listeners are read`() {
        val watcher = HotReloadWatcher()
        watcher.onFileChange(".env") { _, _ -> }
        watcher.onFileChange("plugins/*") { _, _ -> }

        assertTrue(watcher.hasListenerFor(".env"))
        assertTrue(watcher.hasListenerFor("plugins/example.jar"))
        assertFalse(watcher.hasListenerFor(".credentials.lock"))
        assertFalse(watcher.hasListenerFor(".credentials-save.tmp"))
    }
}
