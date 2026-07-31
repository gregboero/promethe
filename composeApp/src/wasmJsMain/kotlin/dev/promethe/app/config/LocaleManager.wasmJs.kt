package dev.promethe.app.config

// Official Compose Multiplatform recipe for runtime language switching on web:
// set window.__customLocale, whose value is returned by the patched
// Navigator.prototype.languages getter installed in index.html BEFORE the app
// scripts load. stringResource() re-resolves against navigator.languages on the
// recomposition triggered by key(currentLocale) in LocaleProvider.
// https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html

private fun setCustomLocale(tag: String): Unit = js("window.__customLocale = tag")

private fun clearCustomLocale(): Unit = js("window.__customLocale = null")

/**
 * Wasm/JS implementation: overrides the locale reported to the Compose
 * resource system via the `window.__customLocale` bridge (see index.html).
 */
actual fun setPlatformLocale(locale: AppLocale) {
    when (locale) {
        AppLocale.SYSTEM -> clearCustomLocale()
        else -> setCustomLocale(locale.code)
    }
}
