package com.todayeat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 色板与 docs/SPEC.md、下载页一致 */
@Immutable
data class Palette(
    val paper: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val sage: Color,
    val sageSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val clay: Color,
    val claySoft: Color,
    val onSage: Color,
    val scrim: Color,
    val dark: Boolean
)

val LightPalette = Palette(
    paper = Color(0xFFF6F3EE), card = Color(0xFFFFFFFF),
    ink = Color(0xFF2B2722), ink2 = Color(0xFF6E665C), ink3 = Color(0xFF8E867B), line = Color(0xFFE9E3DA),
    sage = Color(0xFF4F8A76), sageSoft = Color(0xFFE3EFE9),
    amber = Color(0xFFD9A24A), amberSoft = Color(0xFFF8EDD6),
    clay = Color(0xFFC9785D), claySoft = Color(0xFFF6E3DB),
    onSage = Color.White, scrim = Color(0x662B2722), dark = false
)

val DarkPalette = Palette(
    paper = Color(0xFF171512), card = Color(0xFF211F1B),
    ink = Color(0xFFEDE7DD), ink2 = Color(0xFFB3AA9D), ink3 = Color(0xFF8A8276), line = Color(0xFF34302A),
    sage = Color(0xFF7DB8A2), sageSoft = Color(0xFF22332C),
    amber = Color(0xFFE2B66A), amberSoft = Color(0xFF3A3020),
    clay = Color(0xFFD9927A), claySoft = Color(0xFF3A2620),
    onSage = Color(0xFF10201A), scrim = Color(0x99000000), dark = true
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

object T {
    val c: Palette @Composable get() = LocalPalette.current

    val display = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val bodyStrong = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val tiny = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    /** 等宽数字，时间对齐 */
    val digits = TextStyle(fontFeatureSettings = "tnum")
}

@Composable
fun TodayEatTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.sage, onPrimary = p.onSage, surface = p.card, onSurface = p.ink, background = p.paper,
            surfaceContainerHigh = p.card, onSurfaceVariant = p.ink2, outline = p.line, primaryContainer = p.sageSoft,
            onPrimaryContainer = p.ink, tertiaryContainer = p.sageSoft, onTertiaryContainer = p.ink, secondaryContainer = p.sageSoft)
    } else {
        lightColorScheme(primary = p.sage, onPrimary = p.onSage, surface = p.card, onSurface = p.ink, background = p.paper,
            surfaceContainerHigh = p.card, onSurfaceVariant = p.ink2, outline = p.line, primaryContainer = p.sageSoft,
            onPrimaryContainer = p.ink, tertiaryContainer = p.sageSoft, onTertiaryContainer = p.ink, secondaryContainer = p.sageSoft)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
