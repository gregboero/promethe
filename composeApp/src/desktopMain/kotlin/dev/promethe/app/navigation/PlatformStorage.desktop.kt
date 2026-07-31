package dev.promethe.app.navigation

import java.io.File

/**
 * Desktop (JVM): file-based storage under ~/.promethe/
 */
actual object PlatformStorage {
    private fun storageDir(): File =
        System.getProperty(STORAGE_DIR_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".promethe")

    actual fun read(key: String): String? {
        val file = File(storageDir(), "$key.json")
        return if (file.exists()) file.readText() else null
    }

    actual fun write(
        key: String,
        value: String,
    ) {
        File(storageDir().also { it.mkdirs() }, "$key.json").writeText(value)
    }

    actual fun remove(key: String) {
        File(storageDir(), "$key.json").delete()
    }

    private const val STORAGE_DIR_PROPERTY = "promethe.storage.dir"
}
