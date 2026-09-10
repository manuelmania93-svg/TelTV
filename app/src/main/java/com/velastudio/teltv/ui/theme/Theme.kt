package com.velastudio.teltv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val TelTvYellow = Color(0xFFFFC107)
val TelTvBlack = Color(0xFF000000)
val TelTvWhite = Color(0xFFFFFFFF)
val TelTvPanel = Color(0xFF151515)
val TelTvPanelFocused = Color(0xFF292929)
val TelTvMuted = Color(0xFFBDBDBD)

private val TelTvColorScheme = darkColorScheme(
    primary = TelTvYellow,
    onPrimary = TelTvBlack,
    primaryContainer = Color(0xFF5C4600),
    onPrimaryContainer = TelTvWhite,
    secondary = TelTvWhite,
    onSecondary = TelTvBlack,
    background = TelTvBlack,
    onBackground = TelTvWhite,
    surface = TelTvPanel,
    onSurface = TelTvWhite,
    surfaceVariant = TelTvPanelFocused,
    onSurfaceVariant = TelTvMuted,
    border = TelTvYellow
)

@Composable
fun TelTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TelTvColorScheme, content = content)
}
