package com.velastudio.teltv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val TelTvColorScheme = darkColorScheme(
    primary = Color(0xFF29B6F6),              // Bright Telegram Blue accent
    onPrimary = Color(0xFF000000),            // High-contrast black on active buttons
    primaryContainer = Color(0xFF004D73),
    onPrimaryContainer = Color(0xFFE1F5FE),
    secondary = Color(0xFFE5A93C),            // Warm gold accent
    onSecondary = Color(0xFF000000),
    background = Color(0xFF0B0E14),           // Deep cinematic TV black
    onBackground = Color(0xFFF0F4F8),         // Crisp off-white
    surface = Color(0xFF161D27),              // Elevated card surface
    onSurface = Color(0xFFFFFFFF),            // Pure white text
    surfaceVariant = Color(0xFF212B3A),       // Secondary card surface
    onSurfaceVariant = Color(0xFFB0BEC5),     // Slate secondary text
    border = Color(0xFF29B6F6)                // Highlighting border on remote focus
)

@Composable
fun TelTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TelTvColorScheme, content = content)
}
