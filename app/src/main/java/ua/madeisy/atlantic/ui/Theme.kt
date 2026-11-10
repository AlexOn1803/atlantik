package ua.madeisy.atlantic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Warm = Color(0xFFE8622A)
val Cool = Color(0xFF3B82C4)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC4501E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3A0E00),
    secondary = Color(0xFF6E5A50),
    secondaryContainer = Color(0xFFF6DED3),
    tertiary = Color(0xFF2E6A9E),
    tertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF3E6E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB596),
    onPrimary = Color(0xFF5E1A00),
    primaryContainer = Color(0xFF7F2A05),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFD9C2B8),
    secondaryContainer = Color(0xFF54433B),
    tertiary = Color(0xFFA2C9FF),
    tertiaryContainer = Color(0xFF07487A),
    background = Color(0xFF1A1110),
    surface = Color(0xFF1A1110),
    surfaceVariant = Color(0xFF3A2D28),
)

@Composable
fun AtlanticTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
