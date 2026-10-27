// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Static fallback palette: soft blue/lavender tonal surfaces like Files by Google on large screens.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF445E91), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8D9FA), onSecondaryContainer = Color(0xFF001B3E),
    tertiary = Color(0xFF6B5778), tertiaryContainer = Color(0xFFF3DAFF),
    background = Color(0xFFF8FAFF), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF8FAFF), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC), onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF2FB),
    surfaceContainer = Color(0xFFE6EBF8), surfaceContainerHigh = Color(0xFFDDE4F5),
    surfaceContainerHighest = Color(0xFFD0DAF0),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFB0C6FF), onSecondary = Color(0xFF152F60),
    secondaryContainer = Color(0xFF2D4777), onSecondaryContainer = Color(0xFFD9E2FF),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191B20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474F),
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.Normal),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun WayfinderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
