package dev.promethe.app.screens.setup.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Dot-based step progress indicator for the setup wizard.
 */
@Composable
fun StepIndicator(
    current: Int,
    total: Int,
    colors: ColorScheme,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val isActive = i <= current
            Box(
                modifier =
                    Modifier
                        .size(if (i == current) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) colors.primary else colors.outline.copy(alpha = 0.3f)),
            )
        }
    }
}
