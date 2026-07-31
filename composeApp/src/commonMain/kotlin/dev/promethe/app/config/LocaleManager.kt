package dev.promethe.app.config

import androidx.compose.runtime.*

// Application-wide locale state.
//
// Manages the current language preference and provides a Composition Local
// so any Composable can access the current locale without prop-drilling.
//
// Usage:
//   // At the app root:
//   LocaleProvider(initialLanguage = credentials.language) { ... }
//
//   // Anywhere in the tree:
//   val locale = LocalAppLocale.current  // SYSTEM, ENGLISH, or FRENCH

/**
 * Supported application locales.
 */
enum class AppLocale(
    val code: String,
    val displayName: String,
) {
    SYSTEM("system", "System"),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    ;

    companion object {
        fun fromCode(code: String): AppLocale = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

/**
 * Composition Local for the current app locale.
 * Default is "system" (follow platform locale).
 */
val LocalAppLocale = staticCompositionLocalOf { AppLocale.SYSTEM }

/**
 * Composition Local for the locale change callback.
 * Screens can call this to switch the locale.
 */
val LocalSetLocale = staticCompositionLocalOf<(AppLocale) -> Unit> { {} }

/**
 * Platform-specific locale setter.
 * On JVM: updates java.util.Locale.setDefault()
 * On other targets: no-op (uses system locale).
 */
expect fun setPlatformLocale(locale: AppLocale)

/**
 * Provider that wraps the app content with locale state.
 *
 * @param initialLanguage The persisted language code from AppCredentials.
 * @param onLocaleChanged Callback when user changes locale (to persist in credentials).
 * @param content The app content.
 */
@Composable
fun LocaleProvider(
    initialLanguage: String = "system",
    onLocaleChanged: (AppLocale) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var currentLocale by remember {
        mutableStateOf(AppLocale.fromCode(initialLanguage))
    }

    // Apply platform locale on first composition and when locale changes
    LaunchedEffect(currentLocale) {
        setPlatformLocale(currentLocale)
    }

    val setLocale: (AppLocale) -> Unit = { locale ->
        currentLocale = locale
        onLocaleChanged(locale)
    }

    CompositionLocalProvider(
        LocalAppLocale provides currentLocale,
        LocalSetLocale provides setLocale,
    ) {
        // key() forces full recomposition when locale changes,
        // which makes stringResource() re-resolve with new locale
        key(currentLocale) {
            content()
        }
    }
}
