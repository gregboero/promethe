package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.app.config.AppLocale
import dev.promethe.app.config.LocalAppLocale
import dev.promethe.app.config.LocalSetLocale
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

// Aligned with SettingsViewModel.THEMES — same IDs, same order
internal val THEMES = listOf("system", "dark", "light")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val themeLabels = mapOf(
        "system" to stringResource(Res.string.settings_advanced_theme_auto),
        "dark" to stringResource(Res.string.settings_advanced_theme_dark),
        "light" to stringResource(Res.string.settings_advanced_theme_light),
    )

    SectionHeader(
        stringResource(Res.string.settings_advanced_title),
        state.advancedExpanded,
        stringResource(Res.string.settings_advanced_description),
    ) {
        onUpdate { copy(advancedExpanded = !advancedExpanded) }
    }
    AnimatedVisibility(state.advancedExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Theme selector ──
            Text(stringResource(Res.string.settings_advanced_theme_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_theme")) {
                THEMES.forEachIndexed { index, key ->
                    SegmentedButton(
                        selected = state.selectedTheme == index,
                        onClick = { onUpdate { copy(selectedTheme = index) } },
                        shape = SegmentedButtonDefaults.itemShape(index, THEMES.size),
                    ) {
                        Text(themeLabels[key] ?: key, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Language selector ──
            val currentLocale = LocalAppLocale.current
            val setLocale = LocalSetLocale.current
            val locales = AppLocale.entries

            Text(stringResource(Res.string.settings_advanced_language_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_language")) {
                locales.forEachIndexed { index, locale ->
                    SegmentedButton(
                        selected = currentLocale == locale,
                        onClick = { setLocale(locale) },
                        shape = SegmentedButtonDefaults.itemShape(index, locales.size),
                    ) {
                        Text(locale.displayName, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
