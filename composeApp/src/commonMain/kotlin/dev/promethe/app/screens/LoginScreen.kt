package dev.promethe.app.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.promethe.app.network.PrometheClient
import dev.promethe.api.LoginClientKind
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Login screen for remote devices.
 * Shown when no active session is available.
 * The user enters gateway URL + owner credentials to create an in-memory session.
 */
@Composable
fun LoginScreen(
    initialGatewayUrl: String = "http://localhost:8080",
    clientKind: LoginClientKind = LoginClientKind.NATIVE,
    onLoginSuccess: (sessionToken: String?, gatewayUrl: String, csrfToken: String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var gatewayUrl by remember(initialGatewayUrl) { mutableStateOf(initialGatewayUrl) }
    var user by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Hoist error strings for use inside non-composable connectAction lambda
    val errorEmptyFields = stringResource(Res.string.login_error_empty_fields)
    val errorInvalidCredentials = stringResource(Res.string.login_error_invalid_credentials)
    val errorRemoteNotConfigured = stringResource(Res.string.login_error_remote_not_configured)
    val errorCannotReach = stringResource(Res.string.login_error_cannot_reach, gatewayUrl)
    val errorFailedPrefix = stringResource(Res.string.login_error_failed, "")

    // Extract connect action for reuse (Button + Enter key)
    val connectAction: () -> Unit = {
        if (user.isBlank() || password.isBlank()) {
            errorMessage = errorEmptyFields
        } else if (!isLoading) {
            isLoading = true
            errorMessage = null
            scope.launch {
                try {
                    val tempClient = PrometheClient(clientKind = clientKind)
                    val response =
                        tempClient.login(
                            gatewayUrl = gatewayUrl.trimEnd('/'),
                            user = user.trim(),
                            password = password,
                        )
                    if (clientKind == LoginClientKind.NATIVE && response.accessToken.isNullOrBlank()) {
                        error("Gateway did not return a native session token")
                    }
                    onLoginSuccess(response.accessToken, gatewayUrl.trimEnd('/'), response.csrfToken)
                } catch (e: Exception) {
                    errorMessage =
                        when {
                            e.message?.contains("401") == true -> errorInvalidCredentials
                            e.message?.contains("403") == true -> errorRemoteNotConfigured
                            e.message?.contains("Connection") == true -> errorCannotReach
                            else -> errorFailedPrefix + (e.message?.take(80) ?: "")
                        }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.background, Color(0xFF0A0A18)),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = colors.surface.copy(alpha = 0.9f),
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // ── Header ──
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = colors.primary,
                )
                Text(
                    stringResource(Res.string.login_connect_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Text(
                    stringResource(Res.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )

                Spacer(Modifier.height(4.dp))

                // ── Gateway URL ──
                OutlinedTextField(
                    value = gatewayUrl,
                    onValueChange = {
                        gatewayUrl = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(Res.string.login_gateway_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("login_gateway_url"),
                    shape = RoundedCornerShape(12.dp),
                )

                // ── Username ──
                OutlinedTextField(
                    value = user,
                    onValueChange = {
                        user = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(Res.string.login_username)) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("login_username"),
                    shape = RoundedCornerShape(12.dp),
                )

                // ── Password ──
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(Res.string.login_password)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) stringResource(Res.string.login_hide_password) else stringResource(Res.string.login_show_password),
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("login_password")
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                                connectAction()
                                true
                            } else {
                                false
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                )

                // ── Error ──
                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        errorMessage ?: "",
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("login_error"),
                    )
                }

                // ── Connect button ──
                Button(
                    onClick = connectAction,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("login_connect_btn"),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isLoading,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                        ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = colors.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(Res.string.login_connect), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // ── Help text ──
                Text(
                    stringResource(Res.string.login_help_text),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
