package com.madhu.bikeintercom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PureWhite,
    secondary = GlassWhiteHigh,
    background = DeepNavy,
    surface = GlassWhite,
    onPrimary = DeepNavy,
    onSecondary = PureWhite,
    onBackground = PureWhite,
    onSurface = PureWhite,
    error = StatusRed
)

@Composable
fun BikeIntercomTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
