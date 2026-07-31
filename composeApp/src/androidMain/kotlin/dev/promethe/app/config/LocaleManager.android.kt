package dev.promethe.app.config

import java.util.Locale

/**
 * Android implementation: updates java.util.Locale.setDefault()
 * Compose Multiplatform Resources on Android also uses the JVM locale.
 */
actual fun setPlatformLocale(locale: AppLocale) {
    val javaLocale = when (locale) {
        AppLocale.ENGLISH -> Locale.ENGLISH
        AppLocale.FRENCH -> Locale.FRENCH
        AppLocale.SYSTEM -> Locale.getDefault()
    }
    Locale.setDefault(javaLocale)
}
