package com.ethic.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val LocalLightTheme = staticCompositionLocalOf { false }

/**
 * Brand palette; every color has a dark and a light variant for the theme setting. Backgrounds
 * are neutral black, gray and white; only the accents carry color.
 */
object Brand {
    val Background: Color @Composable @ReadOnlyComposable get() = pick(0xFF000000, 0xFFF2F2F7)
    val Surface: Color @Composable @ReadOnlyComposable get() = pick(0xFF1C1C1E, 0xFFFFFFFF)
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = pick(0xFF2C2C2E, 0xFFE5E5EA)
    val Cyan: Color @Composable @ReadOnlyComposable get() = pick(0xFF00D1FF, 0xFF0094C7)
    val Violet: Color @Composable @ReadOnlyComposable get() = pick(0xFF7B5CFF, 0xFF6A4BFF)
    val Green: Color @Composable @ReadOnlyComposable get() = pick(0xFF2BD98B, 0xFF12A866)
    val Amber: Color @Composable @ReadOnlyComposable get() = pick(0xFFFFB547, 0xFFD98A00)
    val Red: Color @Composable @ReadOnlyComposable get() = pick(0xFFFF5C7A, 0xFFE0445F)
    val TextMuted: Color @Composable @ReadOnlyComposable get() = pick(0xFF8E8E93, 0xFF6C6C70)
    val Text: Color @Composable @ReadOnlyComposable get() = pick(0xFFFFFFFF, 0xFF000000)
    val Gradient: Brush @Composable @ReadOnlyComposable get() = Brush.linearGradient(listOf(Cyan, Violet))

    @Composable
    @ReadOnlyComposable
    private fun pick(dark: Long, light: Long) = Color(if (LocalLightTheme.current) light else dark)
}

@Composable
fun EthicTheme(light: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLightTheme provides light) {
        val colors = if (light) lightColorScheme() else darkColorScheme()
        MaterialTheme(
            colorScheme = colors.copy(
                primary = Brand.Cyan,
                secondary = Brand.Violet,
                background = Brand.Background,
                surface = Brand.Surface,
                surfaceVariant = Brand.SurfaceHigh,
                onBackground = Brand.Text,
                onSurface = Brand.Text,
                onSurfaceVariant = Brand.TextMuted,
                error = Brand.Red,
            ),
            content = content,
        )
    }
}
