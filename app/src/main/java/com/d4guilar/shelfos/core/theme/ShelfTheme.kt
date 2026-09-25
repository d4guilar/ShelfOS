// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ShelfColors(
    val canvas: Color, val surface: Color, val ink: Color, val secondary: Color,
    val divider: Color, val muted: Color,
)
data class ShelfSpacing(val small: Dp = 8.dp, val medium: Dp = 16.dp, val large: Dp = 24.dp)
data class ShelfFocus(val width: Dp = 2.dp, val scale: Float = 1.01f)
data class ShelfMotion(val focusMillis: Int = 120)
data class ShelfSurfaces(val elevation: Dp = 0.dp, val border: Dp = 1.dp)
data class ShelfIcons(val size: Dp = 22.dp, val stroke: Float = 1.7f)
data class ShelfTokens(
    val colors: ShelfColors,
    val dark: Boolean,
    val spacing: ShelfSpacing = ShelfSpacing(),
    val focus: ShelfFocus = ShelfFocus(),
    val motion: ShelfMotion = ShelfMotion(),
    val surfaces: ShelfSurfaces = ShelfSurfaces(),
    val icons: ShelfIcons = ShelfIcons(),
    val shapes: Shapes = Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    ),
    val typography: Typography = Typography(
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    ),
)

private val classic = ShelfTokens(ShelfColors(
    Color(0xFFF7F7F5), Color.White, Color(0xFF111111), Color(0xFF666663),
    Color(0xFFE4E4E0), Color(0xFFEFEFEC),
), dark = false)
private val dark = classic.copy(colors = ShelfColors(
    Color(0xFF0B0B0B), Color(0xFF161616), Color(0xFFF3F3EF), Color(0xFFA4A4A1),
    Color(0xFF343434), Color(0xFF202020),
), dark = true)

// Placeholder registrations never pretend to be implemented themes.
private val presentations = mapOf(ThemeId.CLASSIC to classic, ThemeId.DARK to dark)
val LocalShelfTokens = staticCompositionLocalOf { classic }

@Composable
fun ShelfTheme(id: ThemeId, content: @Composable () -> Unit) {
    val tokens = presentations.getValue(ThemeRegistry.resolve(id.storageKey))
    val c = tokens.colors
    val base = if (tokens.dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = c.ink, onPrimary = c.canvas, primaryContainer = c.muted, onPrimaryContainer = c.ink,
        secondary = c.ink, onSecondary = c.canvas, secondaryContainer = c.muted, onSecondaryContainer = c.ink,
        tertiary = c.ink, onTertiary = c.canvas, tertiaryContainer = c.muted, onTertiaryContainer = c.ink,
        background = c.canvas, onBackground = c.ink, surface = c.canvas, onSurface = c.ink,
        surfaceVariant = c.muted, onSurfaceVariant = c.secondary, surfaceTint = Color.Transparent,
        outline = c.secondary, outlineVariant = c.divider,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface,
        surfaceContainerHigh = c.muted, surfaceContainerHighest = c.muted,
        surfaceBright = c.surface, surfaceDim = c.canvas, scrim = Color.Black,
        surfaceContainerLowest = c.canvas, inverseSurface = c.ink, inverseOnSurface = c.canvas,
        inversePrimary = c.canvas, error = c.ink, onError = c.canvas,
        errorContainer = c.muted, onErrorContainer = c.ink,
    )
    CompositionLocalProvider(LocalShelfTokens provides tokens) {
        MaterialTheme(colorScheme = colors, typography = tokens.typography, shapes = tokens.shapes, content = content)
    }
}
