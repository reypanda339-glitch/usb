package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = SignalEmerald,
    onSecondary = ObsidianCanvas,
    secondaryContainer = SignalEmeraldContainer,
    onSecondaryContainer = SignalEmerald,
    tertiary = StudioAmber,
    onTertiary = ObsidianCanvas,
    tertiaryContainer = StudioAmberContainer,
    onTertiaryContainer = StudioAmber,
    error = BroadcastCoral,
    onError = TextPrimary,
    errorContainer = BroadcastCoralContainer,
    onErrorContainer = BroadcastCoral,
    background = ObsidianCanvas,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = StudioSurface,
    surfaceContainer = StudioSurfaceElevated,
    surfaceContainerHigh = StudioSurfaceHighest,
    outline = StudioOutline,
    outlineVariant = StudioOutlineSubtle
)

val CamLinkShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CamLinkTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = CamLinkTypography,
        shapes = CamLinkShapes,
        content = content
    )
}
