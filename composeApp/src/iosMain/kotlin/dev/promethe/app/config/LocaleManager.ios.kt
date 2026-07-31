package dev.promethe.app.config

import platform.Foundation.NSUserDefaults

/**
 * iOS implementation: updates AppleLanguages in NSUserDefaults.
 * This hints the system about the preferred language for resource loading.
 */
actual fun setPlatformLocale(locale: AppLocale) {
    val languageCode = when (locale) {
        AppLocale.ENGLISH -> "en"
        AppLocale.FRENCH -> "fr"
        AppLocale.SYSTEM -> return // Let the system decide
    }
    NSUserDefaults.standardUserDefaults.setObject(listOf(languageCode), forKey = "AppleLanguages")
    NSUserDefaults.standardUserDefaults.synchronize()
}
