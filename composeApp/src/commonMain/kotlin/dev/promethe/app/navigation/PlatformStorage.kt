package dev.promethe.app.navigation

/**
 * Simple key-value storage abstraction.
 * Each platform implements this with its native storage mechanism.
 */
expect object PlatformStorage {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun remove(key: String)
}
