package com.tonbiattack.instantmic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6BCB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF),
    secondaryContainer = Color(0xFFE3F0E3),
    tertiary = Color(0xFF8A5A00),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC9FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF00497F),
    secondaryContainer = Color(0xFF2A3B2B),
    tertiary = Color(0xFFFFB960),
    errorContainer = Color(0xFF93000A),
)

@Composable
fun InstantMicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
