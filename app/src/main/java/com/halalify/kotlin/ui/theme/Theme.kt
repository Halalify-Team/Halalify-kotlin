package com.halalify.kotlin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HalalifyDarkColorScheme = darkColorScheme(
    primary = HalalifyAccent,
    onPrimary = HalalifyTextOnAccent,
    primaryContainer = HalalifyAccentDim,
    onPrimaryContainer = HalalifyTextPrimary,
    secondary = HalalifyAccentGold,
    onSecondary = HalalifyDark,
    background = HalalifyDark,
    onBackground = HalalifyTextPrimary,
    surface = HalalifyDarkSurface,
    onSurface = HalalifyTextPrimary,
    surfaceVariant = HalalifyDarkCard,
    onSurfaceVariant = HalalifyTextSecondary,
    error = HalalifyError,
    onError = HalalifyTextPrimary,
    outline = HalalifyDarkElevated,
)

@Composable
fun HalalifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HalalifyDarkColorScheme,
        typography = HalalifyTypography,
        content = content,
    )
}
