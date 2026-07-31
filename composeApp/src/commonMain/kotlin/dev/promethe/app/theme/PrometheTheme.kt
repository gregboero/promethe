package dev.promethe.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Palette ──────────────────────────────────────────────────────────────────
private val Indigo500 = Color(0xFF6366F1)
private val Indigo400 = Color(0xFF818CF8)
private val Indigo700 = Color(0xFF4338CA)
private val Purple500 = Color(0xFFA855F7)
private val Purple300 = Color(0xFFD8B4FE)

private val BackgroundDark = Color(0xFF0F0F1A)
private val SurfaceDark = Color(0xFF1A1A2E)
private val SurfaceVariantDark = Color(0xFF252540)
private val SurfaceContainerDark = Color(0xFF16162B)
private val OnSurfaceDark = Color(0xFFE2E2F0)
private val OnSurfaceVariantDark = Color(0xFF9E9EB8)
private val OutlineDark = Color(0xFF3A3A55)
private val ErrorRed = Color(0xFFEF4444)
private val ErrorContainerDark = Color(0xFF3B1111)

private val PrometheColorScheme =
    darkColorScheme(
        primary = Indigo500,
        onPrimary = Color.White,
        primaryContainer = Indigo700,
        onPrimaryContainer = Indigo400,
        secondary = Purple500,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF3B1F60),
        onSecondaryContainer = Purple300,
        tertiary = Color(0xFF22D3EE),
        onTertiary = Color.Black,
        background = BackgroundDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        surfaceContainerLow = SurfaceContainerDark,
        surfaceContainer = SurfaceDark,
        surfaceContainerHigh = SurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = Color(0xFF2A2A42),
        error = ErrorRed,
        onError = Color.White,
        errorContainer = ErrorContainerDark,
        onErrorContainer = Color(0xFFFCA5A5),
        inverseSurface = OnSurfaceDark,
        inverseOnSurface = BackgroundDark,
        inversePrimary = Indigo700,
        scrim = Color.Black.copy(alpha = 0.6f),
    )

// ── Typography ───────────────────────────────────────────────────────────────
private val PrometheTypography =
    Typography(
        displayLarge = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Bold),
        displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.SemiBold),
        headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.15.sp),
        titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.25.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    )

// ── Shapes ───────────────────────────────────────────────────────────────────
private val PrometheShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(16.dp), // Buttons
        medium = RoundedCornerShape(28.dp), // Cards
        large = RoundedCornerShape(32.dp), // FABs
        extraLarge = RoundedCornerShape(32.dp),
    )

private val PrometheLightColorScheme = lightColorScheme(
    primary = Indigo500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E9FF),
    onPrimaryContainer = Indigo700,
    secondary = Purple500,
    onSecondary = Color.White,
    background = Color(0xFFF8F8FF),
    onBackground = Color(0xFF1A1A2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFEEEEFF),
    onSurfaceVariant = Color(0xFF4A4A6A),
    outline = Color(0xFFCCCCEE),
    error = ErrorRed,
    onError = Color.White,
)

@Composable
fun PrometheTheme(
    theme: String = "dark",
    content: @Composable () -> Unit,
) {
    val colorScheme = when (theme) {
        "light" -> PrometheLightColorScheme
        "system" -> if (isSystemInDarkTheme()) PrometheColorScheme else PrometheLightColorScheme
        else -> PrometheColorScheme // "dark" par défaut
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PrometheTypography,
        shapes = PrometheShapes,
        content = content,
    )
}
