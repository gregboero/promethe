package dev.promethe.app.navigation

import java.io.File

/**
 * Android: file-based storage under app internal storage.
 * Falls back to ~/.promethe/ since we don't have Context access here.
 */
actual object PlatformStorage {
    private val storageDir by lazy {
        File(System.getProperty("user.home") ?: "/tmp", ".promethe").also { it.mkdirs() }
    }

    actual fun read(key: String): String? {
        val file = File(storageDir, "$key.json")
        return if (file.exists()) file.readText() else null
    }

    actual fun write(
        key: String,
        value: String,
    ) {
        File(storageDir, "$key.json").writeText(value)
    }

    actual fun remove(key: String) {
        File(storageDir, "$key.json").delete()
    }
}
