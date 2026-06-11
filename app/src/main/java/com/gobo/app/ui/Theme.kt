package com.gobo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.gobo.app.board.BoardColors
import com.gobo.app.board.LocalBoardColors
import com.gobo.app.settings.ThemeMode

// Semantic palette (see the design spec). Defined once here so no UI component
// hardcodes a hex; everything reads MaterialTheme.colorScheme or LocalBoardColors.
private val MintGreen = Color(0xFF20C997)      // light accent
private val ElectricCyan = Color(0xFF06B6D4)   // dark accent

private val LightScheme = lightColorScheme(
    primary = MintGreen,
    onPrimary = Color(0xFF06291F),
    secondary = MintGreen,
    onSecondary = Color(0xFF06291F),
    background = Color(0xFFF8F9FA),             // soft cream
    onBackground = Color(0xFF212529),
    surface = Color(0xFFF8F9FA),
    onSurface = Color(0xFF212529),
    surfaceVariant = Color(0xFFE9ECEF),
    onSurfaceVariant = Color(0xFF495057),
    outline = Color(0xFFCED4DA),
    error = Color(0xFFDC3545),
    onError = Color(0xFFFFFFFF),
)

private val DarkScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF012A33),
    secondary = ElectricCyan,
    onSecondary = Color(0xFF012A33),
    background = Color(0xFF0F172A),             // deep slate
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF1A0A0A),
)

/**
 * App theme. Honors the user's [ThemeMode] choice, falling back to the system
 * setting. Explicit hand-tuned schemes (no Material You dynamic color) so the
 * flat, geometry-first look is identical across devices, plus a parallel
 * [BoardColors] set the goban canvas reads via [LocalBoardColors].
 */
@Composable
fun GoboTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalBoardColors provides if (dark) BoardColors.Dark else BoardColors.Light,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = GoboTypography,
            content = content,
        )
    }
}
