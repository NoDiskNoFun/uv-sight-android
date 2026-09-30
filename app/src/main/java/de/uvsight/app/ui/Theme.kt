package de.uvsight.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The web app's palette: paper, surface, ink, muted, line and the accent colours. */
data class UvColors(
    val paper: Color, val surface: Color, val ink: Color, val muted: Color, val line: Color,
    val gold: Color, val red: Color, val blue: Color, val ok: Color,
)

val LightUv = UvColors(
    paper = Color(0xFFE9ECE6), surface = Color(0xFFF7F8F5), ink = Color(0xFF1B2A22), muted = Color(0xFF5E6B62),
    line = Color(0xFFC9D0C6), gold = Color(0xFFE8B21C), red = Color(0xFFC8392B), blue = Color(0xFF2F6DB5), ok = Color(0xFF2E7D4F),
)
val DarkUv = UvColors(
    paper = Color(0xFF141C17), surface = Color(0xFF1E2922), ink = Color(0xFFE3E9E2), muted = Color(0xFF93A198),
    line = Color(0xFF33423A), gold = Color(0xFFF2C230), red = Color(0xFFE0584A), blue = Color(0xFF5B93D6), ok = Color(0xFF58B27E),
)

/** Target ring colours, constant in both themes. */
object Ring {
    val gold = Color(0xFFF2C230); val red = Color(0xFFD6402F); val blue = Color(0xFF2F6DB5)
    val black = Color(0xFF232323); val white = Color(0xFFFAFAF7); val greyText = Color(0xFF1B1B1B)
    fun colorFor(v: String): Color = when (v) {
        "X", "10", "9" -> gold; "8", "7" -> red; "6", "5" -> blue; "4", "3" -> black; "2", "1" -> white; else -> Color.Transparent
    }
    fun textFor(v: String, ink: Color): Color = when (v) {
        "X", "10", "9", "2", "1" -> greyText; "8", "7", "6", "5", "4", "3" -> Color.White; else -> ink
    }
    fun forAvg(a: Double): Color = if (a >= 9) gold else if (a >= 7) red else if (a >= 5) blue else if (a >= 3) Color(0xFF555555) else Color(0xFFBFC5BD)
}

val LocalUv = staticCompositionLocalOf { LightUv }

@Composable
fun UvSightTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val uv = if (dark) DarkUv else LightUv
    val scheme = if (dark) darkColorScheme(
        primary = uv.ink, onPrimary = uv.paper, background = uv.paper, onBackground = uv.ink,
        surface = uv.surface, onSurface = uv.ink, surfaceVariant = uv.surface, onSurfaceVariant = uv.muted,
        outline = uv.line, error = uv.red, secondary = uv.gold, tertiary = uv.blue,
    ) else lightColorScheme(
        primary = uv.ink, onPrimary = uv.paper, background = uv.paper, onBackground = uv.ink,
        surface = uv.surface, onSurface = uv.ink, surfaceVariant = uv.surface, onSurfaceVariant = uv.muted,
        outline = uv.line, error = uv.red, secondary = uv.gold, tertiary = uv.blue,
    )
    CompositionLocalProvider(LocalUv provides uv) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
