package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun PersonalitySection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(stringResource(Res.string.settings_personality_title), state.personalityExpanded, stringResource(Res.string.settings_personality_description)) {
        onUpdate { copy(personalityExpanded = !personalityExpanded) }
    }
    AnimatedVisibility(state.personalityExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = { onUpdate { copy(systemPrompt = it) } },
                label = { Text(stringResource(Res.string.settings_personality_prompt_label)) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("settings_system_prompt"),
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors(colors),
                supportingText = { Text(stringResource(Res.string.settings_personality_prompt_hint)) },
            )
        }
    }
}
