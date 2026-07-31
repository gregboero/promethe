package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

internal val APPROVAL_MODES = listOf("auto", "dangerous", "all")
internal val APPROVAL_MODE_LABELS = mapOf(
    "auto" to "Auto",
    "dangerous" to "Dangerous Only",
    "all" to "All Tools",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
    onSaveRemoteAccess: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    // ── Tool Approval ─────────────────────────────────────────────────────────
    SectionHeader(stringResource(Res.string.settings_security_title), state.securityExpanded, stringResource(Res.string.settings_security_description)) {
        onUpdate { copy(securityExpanded = !securityExpanded) }
    }
    AnimatedVisibility(state.securityExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.settings_security_approval_mode), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            val approvalAutoLabel = stringResource(Res.string.settings_security_approval_auto)
            val approvalDangerousLabel = stringResource(Res.string.settings_security_approval_dangerous)
            val approvalAllLabel = stringResource(Res.string.settings_security_approval_all)
            val approvalLabelsMap = mapOf(
                "auto" to approvalAutoLabel,
                "dangerous" to approvalDangerousLabel,
                "all" to approvalAllLabel,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_approval_mode")) {
                APPROVAL_MODES.forEachIndexed { index, key ->
                    SegmentedButton(
                        selected = state.selectedApprovalMode == index,
                        onClick = { onUpdate { copy(selectedApprovalMode = index) } },
                        shape = SegmentedButtonDefaults.itemShape(index, APPROVAL_MODES.size),
                    ) {
                        Text(approvalLabelsMap[key] ?: key, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Text(
                when (APPROVAL_MODES[state.selectedApprovalMode]) {
                    "auto" -> stringResource(Res.string.settings_security_approval_auto_desc)
                    "dangerous" -> stringResource(Res.string.settings_security_approval_dangerous_desc)
                    "all" -> stringResource(Res.string.settings_security_approval_all_desc)
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            AnimatedVisibility(APPROVAL_MODES[state.selectedApprovalMode] != "auto") {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(Res.string.settings_security_approval_timeout), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                        Text(
                            "${(state.approvalTimeoutMs / 1000).toInt()}s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.primary,
                        )
                    }
                    Slider(
                        value = state.approvalTimeoutMs,
                        onValueChange = { onUpdate { copy(approvalTimeoutMs = it) } },
                        valueRange = 30_000f..600_000f,
                        steps = 18,
                        modifier = Modifier.testTag("settings_approval_timeout"),
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                    )
                    Text(
                        stringResource(Res.string.settings_security_approval_timeout_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ── Remote Access ─────────────────────────────────────────────────────────
    SectionHeader(
        stringResource(Res.string.settings_security_remote_title),
        state.remoteAccessExpanded,
        if (state.remoteUser.isNotBlank()) stringResource(Res.string.settings_security_remote_configured, state.remoteUser) else stringResource(Res.string.settings_security_remote_not_configured),
    ) {
        onUpdate { copy(remoteAccessExpanded = !remoteAccessExpanded) }
    }
    AnimatedVisibility(state.remoteAccessExpanded) {
        var showPassword by remember { mutableStateOf(false) }
        val passwordsMatch = state.remoteNewPassword == state.remoteNewPasswordConfirm ||
            state.remoteNewPasswordConfirm.isEmpty()
        val passwordStrong = state.remoteNewPassword.length >= 12
        val canSave = state.remoteUser.isNotBlank() && passwordStrong && passwordsMatch &&
            state.remoteNewPassword.isNotEmpty()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.settings_security_remote_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            // Username
            OutlinedTextField(
                value = state.remoteUser,
                onValueChange = { onUpdate { copy(remoteUser = it, remoteAccessSaved = false) } },
                label = { Text(stringResource(Res.string.settings_security_username_label)) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_remote_user"),
                shape = RoundedCornerShape(12.dp),
            )

            // New password
            OutlinedTextField(
                value = state.remoteNewPassword,
                onValueChange = { onUpdate { copy(remoteNewPassword = it, remoteAccessSaved = false) } },
                label = { Text(stringResource(Res.string.settings_security_new_password_label)) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) stringResource(Res.string.settings_security_hide_password) else stringResource(Res.string.settings_security_show_password),
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_remote_password"),
                shape = RoundedCornerShape(12.dp),
                isError = state.remoteNewPassword.isNotEmpty() && !passwordStrong,
                supportingText = if (state.remoteNewPassword.isNotEmpty() && !passwordStrong) {
                    { Text(stringResource(Res.string.settings_security_min_chars), color = colors.error) }
                } else {
                    null
                },
            )

            // Confirm password
            OutlinedTextField(
                value = state.remoteNewPasswordConfirm,
                onValueChange = { onUpdate { copy(remoteNewPasswordConfirm = it, remoteAccessSaved = false) } },
                label = { Text(stringResource(Res.string.settings_security_confirm_password_label)) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_remote_password_confirm"),
                shape = RoundedCornerShape(12.dp),
                isError = !passwordsMatch,
                supportingText = if (!passwordsMatch) {
                    { Text(stringResource(Res.string.settings_security_passwords_mismatch), color = colors.error) }
                } else {
                    null
                },
            )

            // Feedback
            AnimatedVisibility(state.remoteAccessSaved) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Text(stringResource(Res.string.settings_security_remote_saved), style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
            }
            AnimatedVisibility(state.remoteAccessError != null) {
                Text(
                    state.remoteAccessError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }

            // Save button
            val saveRemoteA11y = stringResource(Res.string.settings_security_save_remote_a11y)
            Button(
                onClick = onSaveRemoteAccess,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_remote_save")
                    .semantics { contentDescription = saveRemoteA11y },
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(Res.string.settings_security_save_remote_button), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
