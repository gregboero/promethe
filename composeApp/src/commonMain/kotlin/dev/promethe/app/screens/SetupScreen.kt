@file:Suppress("DEPRECATION")

package dev.promethe.app.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.promethe.app.screens.setup.SetupViewModel
import dev.promethe.app.screens.setup.components.StepIndicator
import dev.promethe.app.screens.setup.steps.*
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

// First-run setup screen. Shown when ~/.promethe/credentials.json is missing or empty.
// Mirrors the CLI SetupWizard flow but with a premium Desktop UI.

/**
 * Setup wizard shell — manages navigation between 6 steps.
 *
 * All form state lives in [SetupViewModel]; each step composable
 * reads from [dev.promethe.app.screens.setup.SetupUiState] and
 * writes through ViewModel functions.
 */
@Composable
fun SetupScreen(
    onSetupComplete: (
        provider: String,
        apiKey: String,
        model: String,
        localUrl: String,
        contextFiles: Map<String, String>,
        remoteUser: String,
        remotePassword: String,
    ) -> Unit,
) {
    val viewModel = remember { SetupViewModel() }
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.background, colors.surfaceContainerLowest),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxHeight()
                    .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Text(
                stringResource(Res.string.setup_brand_name),
                style =
                    MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    ),
                color = colors.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.setup_initial_config),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // Step indicator
            StepIndicator(current = state.step, total = 6, colors = colors)
            Spacer(Modifier.height(32.dp))

            // Step content — Crossfade gets bounded height via weight(1f).
            // Using Crossfade instead of AnimatedContent to avoid the infinite
            // height constraints issue with nested scrollable components.
            // Each step composable handles its own scrolling if needed
            // (e.g. IntegrationsStep has its own verticalScroll).
            Crossfade(
                targetState = state.step,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("setup_step_${state.step}"),
            ) { currentStep ->
                when (currentStep) {
                    0 -> ProviderStep(
                        selected = state.selectedProvider,
                        onSelect = viewModel::selectProvider,
                    )

                    1 -> CredentialsStep(state, viewModel)

                    2 -> RemoteAccessStep(state, viewModel)

                    3 -> ProjectContextStep(state, viewModel)

                    4 -> IntegrationsStep(state, viewModel)

                    5 -> ConfirmStep(state)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (state.step > 0) {
                    OutlinedButton(
                        onClick = viewModel::previousStep,
                        modifier = Modifier.testTag("setup_back"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                        border = ButtonDefaults.outlinedButtonBorder(true),
                    ) {
                        Icon(Icons.Filled.ArrowBack, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.setup_previous))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                Button(
                    onClick = {
                        if (state.step < 5) {
                            viewModel.nextStep()
                        } else {
                            // Save integration tokens to env (best-effort)
                            viewModel.saveIntegrationTokens(state.apiKey)
                            // Complete setup
                            onSetupComplete(
                                state.selectedProvider!!.key,
                                state.apiKey,
                                state.model,
                                state.localUrl,
                                state.contextFiles,
                                state.remoteUser,
                                state.remotePassword,
                            )
                        }
                    },
                    enabled = state.canProceed,
                    modifier = Modifier.testTag("setup_next"),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary,
                        ),
                ) {
                    Text(if (state.step == 5) stringResource(Res.string.setup_launch) else stringResource(Res.string.setup_next))
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (state.step == 5) Icons.Default.RocketLaunch else Icons.Filled.ArrowForward,
                        null,
                        Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
