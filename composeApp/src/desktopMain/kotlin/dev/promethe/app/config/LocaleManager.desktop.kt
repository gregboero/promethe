package dev.promethe.app.config

import java.util.Locale

/**
 * JVM/Desktop implementation: updates java.util.Locale.setDefault()
 * which Compose Multiplatform Resources uses to resolve string resources.
 */
actual fun setPlatformLocale(locale: AppLocale) {
    val javaLocale = when (locale) {
        AppLocale.ENGLISH -> Locale.ENGLISH
        AppLocale.FRENCH -> Locale.FRENCH
        AppLocale.SYSTEM -> Locale.getDefault()
    }
    Locale.setDefault(javaLocale)
}
