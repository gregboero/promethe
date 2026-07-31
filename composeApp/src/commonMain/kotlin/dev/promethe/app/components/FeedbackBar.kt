package dev.promethe.app.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun FeedbackBar(
    onFeedback: (score: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<Double?>(null) }
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbs up → 1.0
        IconButton(
            onClick = {
                selected = 1.0
                onFeedback(1.0)
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = stringResource(Res.string.component_feedback_good_a11y),
                tint = if (selected == 1.0) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(2.dp))

        // Meh → 0.5
        IconButton(
            onClick = {
                selected = 0.5
                onFeedback(0.5)
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SentimentNeutral,
                contentDescription = stringResource(Res.string.component_feedback_neutral_a11y),
                tint = if (selected == 0.5) colors.secondary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(2.dp))

        // Thumbs down → 0.0
        IconButton(
            onClick = {
                selected = 0.0
                onFeedback(0.0)
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ThumbDown,
                contentDescription = stringResource(Res.string.component_feedback_bad_a11y),
                tint = if (selected == 0.0) colors.error else colors.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
