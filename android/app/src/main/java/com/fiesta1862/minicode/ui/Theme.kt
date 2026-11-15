package com.fiesta1862.minicode.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object MinicodeColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF111820)
    val SurfaceHigh = Color(0xFF17212B)
    val Border = Color(0xFF243040)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B98A9)
    val Accent = Color(0xFF4ADE80)
    val AccentDark = Color(0xFF10321F)
    val Danger = Color(0xFFF87171)
    val DangerDark = Color(0xFF35161A)
    val Code = Color(0xFF0E141B)
    val UserBubble = Color(0xFF14351F)
}

private val scheme = darkColorScheme(
    primary = MinicodeColors.Accent,
    onPrimary = Color(0xFF04140A),
    primaryContainer = MinicodeColors.UserBubble,
    onPrimaryContainer = MinicodeColors.Text,
    secondary = MinicodeColors.Accent,
    onSecondary = Color(0xFF04140A),
    background = MinicodeColors.Background,
    onBackground = MinicodeColors.Text,
    surface = MinicodeColors.Background,
    onSurface = MinicodeColors.Text,
    surfaceVariant = MinicodeColors.SurfaceHigh,
    onSurfaceVariant = MinicodeColors.Muted,
    outline = MinicodeColors.Border,
    error = MinicodeColors.Danger,
    onError = Color(0xFF2A0A0C),
    errorContainer = MinicodeColors.DangerDark,
    onErrorContainer = MinicodeColors.Text,
)

private val typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
    ),
)

@Composable
fun MinicodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}
