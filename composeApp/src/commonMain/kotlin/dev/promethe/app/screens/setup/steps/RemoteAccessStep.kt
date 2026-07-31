package dev.promethe.app.screens.setup.steps

import androidx.compose.foundation.layout.*
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
import dev.promethe.app.screens.setup.SetupViewModel
import dev.promethe.app.screens.setup.SetupUiState
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Step 2: Remote access credentials.
 * Configures username + password for browser/mobile login.
 */
@Composable
fun RemoteAccessStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
) {
    val colors = MaterialTheme.colorScheme
    var showPassword by remember { mutableStateOf(false) }
    val passwordsMatch = state.remotePassword == state.remotePasswordConfirm || state.remotePasswordConfirm.isEmpty()
    val passwordStrong = state.remotePassword.length >= 12

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(Res.string.setup_remote_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            stringResource(Res.string.setup_remote_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        // Username
        OutlinedTextField(
            value = state.remoteUser,
            onValueChange = viewModel::updateRemoteUser,
            label = { Text(stringResource(Res.string.setup_remote_username_label)) },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("setup_remote_user"),
            shape = RoundedCornerShape(12.dp),
        )

        // Password
        OutlinedTextField(
            value = state.remotePassword,
            onValueChange = viewModel::updateRemotePassword,
            label = { Text(stringResource(Res.string.setup_remote_password_label)) },
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
            modifier = Modifier.fillMaxWidth().testTag("setup_remote_password"),
            shape = RoundedCornerShape(12.dp),
            isError = state.remotePassword.isNotEmpty() && !passwordStrong,
            supportingText =
                if (state.remotePassword.isNotEmpty() && !passwordStrong) {
                    { Text(stringResource(Res.string.setup_remote_password_min_length), color = colors.error) }
                } else {
                    null
                },
        )

        // Confirm password
        OutlinedTextField(
            value = state.remotePasswordConfirm,
            onValueChange = viewModel::updateRemotePasswordConfirm,
            label = { Text(stringResource(Res.string.setup_remote_confirm_password_label)) },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            isError = !passwordsMatch,
            supportingText =
                if (!passwordsMatch) {
                    { Text(stringResource(Res.string.setup_remote_passwords_mismatch), color = colors.error) }
                } else {
                    null
                },
        )

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = colors.primaryContainer.copy(alpha = 0.3f),
                ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Info, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(Res.string.setup_remote_info_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
