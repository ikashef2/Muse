package com.kashef.archive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArchiveColors = darkColorScheme(
    primary = Color(0xFFFF6A00),
    onPrimary = Color(0xFF160A04),
    primaryContainer = Color(0xFF4A1D08),
    onPrimaryContainer = Color(0xFFFFDCC9),
    secondary = Color(0xFFFFA36F),
    onSecondary = Color(0xFF1A0C06),
    background = Color(0xFF000000),
    surface = Color(0xFF090909),
    surfaceVariant = Color(0xFF121212),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF181818),
    onBackground = Color(0xFFF4F4F4),
    onSurface = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFFA7A7A7),
    outline = Color(0xFF303030),
    error = Color(0xFFFF6B62),
)

@Composable
fun ArchiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ArchiveColors, content = content)
}
