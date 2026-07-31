package dev.promethe.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.viewmodel.TranscriptItem
import dev.promethe.app.screens.viewmodel.VoiceUiState
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * VoiceOverlay — floating overlay for voice conversations.
 *
 * Shows: waveform animation, transcript, mute/end buttons.
 */
@Composable
fun VoiceOverlay(
    voiceState: VoiceUiState,
    transcripts: List<TranscriptItem>,
    onMuteToggle: () -> Unit,
    onEchoSuppressionToggle: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    AnimatedVisibility(
        // Only show overlay in S2S mode — in STT/dictation mode the text input stays visible
        visible = voiceState.isActive && !voiceState.isDictating,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
            shadowElevation = 4.dp,
            color = colors.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Status
                if (voiceState.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.component_voice_connecting), style = MaterialTheme.typography.bodySmall)
                } else {
                    // Waveform
                    WaveformAnimation(
                        isActive = voiceState.isSpeaking || !voiceState.isMuted,
                        isSpeaking = voiceState.isSpeaking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    )
                }

                // Error
                voiceState.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }

                // Transcripts
                if (transcripts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    val listState = rememberLazyListState()

                    LaunchedEffect(transcripts.size) {
                        listState.animateScrollToItem(transcripts.lastIndex)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceContainerLow)
                            .padding(8.dp),
                    ) {
                        items(transcripts) { item ->
                            TranscriptBubble(item)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Mute button
                    IconButton(
                        onClick = onMuteToggle,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (voiceState.isMuted) {
                                    colors.errorContainer
                                } else {
                                    colors.secondaryContainer
                                },
                            ),
                    ) {
                        Icon(
                            imageVector = if (voiceState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (voiceState.isMuted) {
                                stringResource(Res.string.component_voice_unmute_a11y)
                            } else {
                                stringResource(Res.string.component_voice_mute_a11y)
                            },
                            tint = if (voiceState.isMuted) colors.onErrorContainer else colors.onSecondaryContainer,
                        )
                    }

                    // Headset mode button (toggles echo suppression)
                    IconButton(
                        onClick = onEchoSuppressionToggle,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (!voiceState.echoSuppression) {
                                    colors.primaryContainer
                                } else {
                                    colors.secondaryContainer
                                },
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Headset,
                            contentDescription = stringResource(Res.string.component_voice_headset_a11y),
                            tint = if (!voiceState.echoSuppression) colors.onPrimaryContainer else colors.onSecondaryContainer,
                        )
                    }

                    // End button
                    IconButton(
                        onClick = onEndSession,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(colors.error),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = stringResource(Res.string.component_voice_end_call_a11y),
                            tint = colors.onError,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(item: TranscriptItem) {
    val colors = MaterialTheme.colorScheme
    val isUser = item.speaker == "user"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.isFinal) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isUser) colors.primaryContainer else colors.surfaceContainerHighest)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WaveformAnimation(
    isActive: Boolean,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition()
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
        ),
    )

    val barColor = if (isSpeaking) colors.primary else colors.secondary

    Canvas(modifier = modifier) {
        val centerY = size.height / 2
        val barWidth = 3.dp.toPx()
        val barSpacing = 5.dp.toPx()
        val maxHeight = size.height * 0.8f
        val barCount = (size.width / (barWidth + barSpacing)).toInt()

        for (i in 0 until barCount) {
            val x = i * (barWidth + barSpacing) + barWidth / 2

            val amplitude = if (isActive) {
                val wave = sin(phase + i * 0.5f)
                val envelope = 0.3f + 0.7f * ((sin(phase * 0.7f + i * 0.3f) + 1f) / 2f)
                (wave * envelope * maxHeight / 2).coerceAtLeast(barWidth)
            } else {
                barWidth // flat line when idle
            }

            drawLine(
                color = barColor,
                start = Offset(x, centerY - amplitude),
                end = Offset(x, centerY + amplitude),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
