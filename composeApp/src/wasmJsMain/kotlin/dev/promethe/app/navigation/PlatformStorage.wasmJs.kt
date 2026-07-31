package dev.promethe.app.navigation

import kotlinx.browser.localStorage

/**
 * WasmJS: browser localStorage.
 * Note: For WasmJs, the URL hash already provides route persistence across
 * page reloads. This storage is used for full back stack persistence
 * (not just the top route).
 */
actual object PlatformStorage {
    actual fun read(key: String): String? = localStorage.getItem(key)

    actual fun write(
        key: String,
        value: String,
    ) {
        try {
            localStorage.setItem(key, value)
        } catch (_: Exception) {
            // localStorage may be unavailable (incognito, quota exceeded)
        }
    }

    actual fun remove(key: String) {
        localStorage.removeItem(key)
    }
}
