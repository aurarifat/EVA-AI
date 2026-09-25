package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EvaDarkColorScheme = darkColorScheme(
    primary = EvaYellowPrimary,
    onPrimary = Color(0xFF090A0E),
    primaryContainer = Color(0xFF2A2312),
    onPrimaryContainer = EvaYellowBright,
    secondary = EvaYellowGold,
    onSecondary = Color(0xFF090A0E),
    secondaryContainer = EvaSurfaceElevated,
    onSecondaryContainer = EvaYellowBright,
    tertiary = EvaCyanAccent,
    onTertiary = Color(0xFF090A0E),
    background = EvaObsidian,
    onBackground = EvaTextPrimary,
    surface = EvaSurface,
    onSurface = EvaTextPrimary,
    surfaceVariant = EvaSurfaceElevated,
    onSurfaceVariant = EvaTextSecondary,
    outline = Color(0x33FFD54F),
    outlineVariant = Color(0x1AFFFFFF),
    error = EvaErrorRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EvaDarkColorScheme,
        typography = Typography,
        content = content
    )
}
