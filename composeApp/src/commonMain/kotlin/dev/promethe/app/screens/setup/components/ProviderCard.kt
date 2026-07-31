package dev.promethe.app.screens.setup.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.setup.ProviderOption
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Selectable card displaying a single LLM provider with icon, name,
 * and optional "recommended" badge.
 */
@Composable
fun ProviderCard(
    provider: ProviderOption,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isSelected) colors.primary else colors.outline.copy(alpha = 0.3f)
    val bgColor = if (isSelected) colors.primaryContainer.copy(alpha = 0.15f) else Color.Transparent

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .testTag("setup_provider_${provider.key}")
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            provider.icon,
            contentDescription = null,
            tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            provider.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (isRecommended) {
            Surface(
                color = colors.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    stringResource(Res.string.setup_provider_recommended),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
    }
}
