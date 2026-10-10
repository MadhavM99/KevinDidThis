package com.megathon.incidentassistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// High-contrast palettes: the main text/background pairs below measure 7.7:1 or higher (WCAG AAA).
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B3D91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondaryContainer = Color(0xFFE3E8F5),
    onSecondaryContainer = Color(0xFF0E1B33),
    error = Color(0xFF9C1C13),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color.White,
    onBackground = Color(0xFF111418),
    surface = Color.White,
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF1F2430),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4CAFF),
    onPrimary = Color(0xFF00255C),
    primaryContainer = Color(0xFF1F3B73),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondaryContainer = Color(0xFF2A3042),
    onSecondaryContainer = Color(0xFFE6EBFA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1A12),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFF1F3F8),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFF1F3F8),
    surfaceVariant = Color(0xFF1C212B),
    onSurfaceVariant = Color(0xFFE2E6F0),
)

@Composable
fun IncidentTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
