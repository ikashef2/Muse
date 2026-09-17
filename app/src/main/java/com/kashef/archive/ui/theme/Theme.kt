package com.kashef.archive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MuseOrange = Color(0xFFFF6A00)
private val MuseOrangeSoft = Color(0xFFFF8A3D)

private val ArchiveColors = darkColorScheme(
    primary = MuseOrange,
    onPrimary = Color(0xFF160A04),
    primaryContainer = Color(0xFF4A1D08),
    onPrimaryContainer = Color(0xFFFFDCC9),
    secondary = MuseOrangeSoft,
    onSecondary = Color(0xFF1A0C06),
    background = Color(0xFF090909),
    surface = Color(0xFF111111),
    surfaceVariant = Color(0xFF181818),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF222222),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF2A2A2A),
    error = Color(0xFFFF6B62),
)

private val MuseTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun ArchiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArchiveColors,
        typography = MuseTypography,
        content = content,
    )
}
