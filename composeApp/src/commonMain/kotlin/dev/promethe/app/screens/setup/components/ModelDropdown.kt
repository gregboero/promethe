package dev.promethe.app.screens.setup.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

// ── Pricing utilities ───────────────────────────────────────────────────────

/** Format price: $0.15 or FREE */
internal fun formatPrice(price: Double?): String {
    if (price == null) return "?"
    if (price == 0.0) return "FREE"
    // Multiplatform-safe formatting (no String.format on WASM)
    return if (price < 1.0) {
        val cents = (price * 100).toInt()
        "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    } else {
        val whole = price.toInt()
        val frac = ((price - whole) * 10).toInt()
        if (frac == 0) "$$whole" else "$$whole.$frac"
    }
}

/** Color code: green=cheap, yellow=moderate, red=expensive */
internal fun priceColor(price: Double?): Color {
    if (price == null) return Color(0xFF9CA3AF)
    if (price == 0.0) return Color(0xFF22C55E)
    return when {
        price < 1.0 -> Color(0xFF22C55E)

        // green
        price < 5.0 -> Color(0xFFF59E0B)

        // amber
        price < 15.0 -> Color(0xFFF97316)

        // orange
        else -> Color(0xFFEF4444) // red
    }
}

// ── ModelDropdown ────────────────────────────────────────────────────────────

/**
 * Dropdown model selector with pricing badges and loading indicator.
 * Supports both statically known models and dynamically fetched ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(
    models: List<String>,
    entries: List<dev.promethe.app.network.ModelFetcher.ModelEntry> = emptyList(),
    selectedModel: String,
    defaultModel: String,
    onModelChange: (String) -> Unit,
    isFetching: Boolean,
    fetchError: String?,
    sourceLabel: String,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val entryMap = remember(entries) { entries.associateBy { it.id } }

    Text(
        stringResource(Res.string.setup_model_label_format, sourceLabel),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = onModelChange,
            readOnly = false,
            singleLine = true,
            trailingIcon = {
                if (isFetching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("setup_model"),
            shape = RoundedCornerShape(12.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outline,
                    cursorColor = colors.primary,
                ),
        )

        if (models.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                models.forEach { modelOption ->
                    val isDefault = modelOption == defaultModel
                    val entry = entryMap[modelOption]
                    val hasPrice = entry?.promptPricePerM != null || entry?.completionPricePerM != null

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                // Model name
                                Text(
                                    modelOption,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )

                                // Pricing badges
                                if (hasPrice) {
                                    Spacer(Modifier.width(8.dp))
                                    // Input price
                                    Surface(
                                        color = priceColor(entry.promptPricePerM).copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(
                                            "↓${formatPrice(entry.promptPricePerM)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = priceColor(entry.promptPricePerM),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(3.dp))
                                    // Output price
                                    Surface(
                                        color = priceColor(entry.completionPricePerM).copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(
                                            "↑${formatPrice(entry.completionPricePerM)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = priceColor(entry.completionPricePerM),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }

                                // Default badge
                                if (isDefault) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = colors.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(
                                            stringResource(Res.string.setup_model_default_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            onModelChange(modelOption)
                            expanded = false
                        },
                        leadingIcon = {
                            if (modelOption == selectedModel) {
                                Icon(Icons.Default.Check, null, tint = colors.primary, modifier = Modifier.size(16.dp))
                            }
                        },
                    )
                }
            }
        }
    }

    // Legend
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(Res.string.setup_model_custom_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
        )
        if (entries.any { it.promptPricePerM != null }) {
            Text(
                "↓in ↑out · $/M tokens",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
