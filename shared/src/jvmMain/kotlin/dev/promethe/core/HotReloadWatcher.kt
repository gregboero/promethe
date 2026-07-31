package dev.promethe.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * HotReloadWatcher monitors configuration files for changes
 * and triggers reload callbacks without requiring a restart.
 */
class HotReloadWatcher(
    private val workDir: String = ".",
    private val debounceMs: Long = 2000,
) {
    private val mutex = Mutex()
    private var watchJob: Job? = null
    private val listeners: MutableMap<String, MutableList<suspend (String, String) -> Unit>> = mutableMapOf()
    private var lastReloadTime = 0L

    fun onFileChange(
        pattern: String,
        callback: suspend (String, String) -> Unit,
    ) {
        listeners.getOrPut(pattern) { mutableListOf() }.add(callback)
    }

    fun start(scope: CoroutineScope) {
        watchJob =
            scope.launch(Dispatchers.IO) {
                logger.info { "Starting file watcher on: $workDir" }
                val dir = File(workDir)
                if (!dir.exists()) {
                    logger.warn { "Work directory does not exist: $workDir" }
                    return@launch
                }

                val path = dir.toPath()
                val watchService = FileSystems.getDefault().newWatchService()

                path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                )

                for (subdir in listOf("profiles", "plugins", ".promethe")) {
                    val sub = path.resolve(subdir)
                    if (Files.exists(sub) && Files.isDirectory(sub)) {
                        sub.register(
                            watchService,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE,
                        )
                    }
                }

                while (isActive) {
                    try {
                        val key = watchService.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                        if (key == null) continue

                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue

                            @Suppress("UNCHECKED_CAST")
                            val ctx = event.context() as? Path
                            val filename = ctx?.toString() ?: continue
                            val now = System.currentTimeMillis()

                            if (now - lastReloadTime < debounceMs) continue

                            mutex.withLock {
                                lastReloadTime = now
                                handleFileChange(filename)
                            }
                        }

                        key.reset()
                    } catch (e: ClosedWatchServiceException) {
                        logger.debug(e) { "Watch service closed, stopping watcher" }
                        break
                    } catch (e: Exception) {
                        logger.warn(e) { "Watcher error" }
                        delay(5000)
                    }
                }
            }
    }

    private suspend fun handleFileChange(filename: String) {
        val file = File(workDir, filename)
        if (!file.exists() || !file.isFile) return

        val content =
            try {
                if (file.length() > 100_000) return
                file.readText()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to read changed file: $filename" }
                return
            }

        logger.info { "Detected change: $filename" }

        for ((pattern, callbacks) in listeners) {
            val matches =
                when {
                    pattern == filename -> true
                    pattern.startsWith("*.") && filename.endsWith(pattern.removePrefix("*")) -> true
                    pattern.endsWith("/*") && filename.startsWith(pattern.removeSuffix("/*")) -> true
                    else -> false
                }

            if (matches) {
                for (callback in callbacks) {
                    try {
                        callback(filename, content)
                    } catch (e: Exception) {
                        logger.error(e) { "Callback error for $filename" }
                    }
                }
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        logger.info { "File watcher stopped" }
    }

    companion object {
        fun createDefault(
            workDir: String,
            onContextReload: suspend (Map<String, String>) -> Unit,
            onEnvReload: suspend (Map<String, String>) -> Unit = {},
            onPluginChange: suspend (String) -> Unit = {},
        ): HotReloadWatcher {
            val watcher = HotReloadWatcher(workDir)

            for (name in ContextFileLoader.CONTEXT_FILES) {
                watcher.onFileChange(name) { _, _ ->
                    val files = ContextFileLoader.loadContextFiles(workDir)
                    onContextReload(files)
                    logger.info { "Context files reloaded" }
                }
            }

            watcher.onFileChange(".env") { _, content ->
                val envMap = parseEnvFile(content)
                onEnvReload(envMap)
                logger.info { "Environment reloaded" }
            }

            watcher.onFileChange("plugins/*") { fname, _ ->
                onPluginChange(fname)
                logger.info { "Plugin change detected: $fname" }
            }

            return watcher
        }

        private fun parseEnvFile(content: String): Map<String, String> =
            buildMap {
                for (line in content.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank() || trimmed.startsWith("#")) continue
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value =
                            trimmed
                                .substring(eqIndex + 1)
                                .trim()
                                .removeSurrounding("\"")
                                .removeSurrounding("'")
                        put(key, value)
                    }
                }
            }
    }
}
