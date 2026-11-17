package moe.hexzii.earpick.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8C6),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF9CF5E2),
    secondary = Color(0xFFB0CCC5),
    background = Color(0xFF0B1211),
    surface = Color(0xFF0B1211),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBFC9C6),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF5E2),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635E),
    background = Color(0xFFF5FBF8),
    surface = Color(0xFFF5FBF8),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4947),
)

@Composable
fun EarpickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
