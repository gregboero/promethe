package dev.promethe.app.navigation

import platform.Foundation.NSUserDefaults

/**
 * iOS: NSUserDefaults-based storage.
 */
actual object PlatformStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun read(key: String): String? {
        return defaults.stringForKey(key)
    }

    actual fun write(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
