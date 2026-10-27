package com.zigorminsk.timemanager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.zigorminsk.timemanager.model.Settings

val Indigo = Color(0xFF4550E5)
val IndigoSoft = Color(0xFFE3E5FF)
val Green = Color(0xFF00A37A)
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFD93025)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoSoft,
    onPrimaryContainer = Color(0xFF10175B),
    secondary = Green,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F5E5),
    onSecondaryContainer = Color(0xFF00382A),
    tertiary = Amber,
    onTertiary = Color.White,
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF1B1B21),
    surface = Color.White,
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFEDEDF5),
    onSurfaceVariant = Color(0xFF4A4A57),
    outline = Color(0xFFB9B9C6),
    error = Red,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC2FF),
    onPrimary = Color(0xFF111A63),
    primaryContainer = Color(0xFF2F3A92),
    onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = Color(0xFF6FE0B8),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF00513E),
    onSecondaryContainer = Color(0xFFC9F5E5),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF452B00),
    background = Color(0xFF111116),
    onBackground = Color(0xFFE6E6EE),
    surface = Color(0xFF1A1A21),
    onSurface = Color(0xFFE6E6EE),
    surfaceVariant = Color(0xFF2A2A34),
    onSurfaceVariant = Color(0xFFC5C5D2),
    outline = Color(0xFF5C5C6B),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TimeManagerTheme(
    themeMode: Int = Settings.THEME_SYSTEM,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        Settings.THEME_LIGHT -> false
        Settings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
