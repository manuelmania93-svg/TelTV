package com.velastudio.teltv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val TelTvColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF8A5CF6),   // purple accent, nods to VelaTV's icon
    background = androidx.compose.ui.graphics.Color(0xFF0B0B0F),
    surface = androidx.compose.ui.graphics.Color(0xFF151519)
)

@Composable
fun TelTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TelTvColorScheme, content = content)
}
