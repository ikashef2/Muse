package com.kashef.archive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArchiveColors = darkColorScheme(
    primary = Color(0xFFB7FF6A),
    onPrimary = Color(0xFF152000),
    secondary = Color(0xFFFFC66A),
    background = Color(0xFF11130F),
    surface = Color(0xFF191C17),
    surfaceVariant = Color(0xFF23271F),
    onBackground = Color(0xFFF1F4EA),
    onSurface = Color(0xFFF1F4EA),
    onSurfaceVariant = Color(0xFFADB6A3),
    error = Color(0xFFFF766E),
)

@Composable
fun ArchiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ArchiveColors, content = content)
}
