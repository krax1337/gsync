package io.github.krax1337.gsync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.krax1337.gsync.R

/**
 * The colours that are not scheme roles: the LCD panel has to look like the watch display and
 * "connected" has to read as green, whatever the wallpaper is.
 */
@Immutable
internal class WatchColors(
    val lcdPanel: Color,
    val lcdEdge: Color,
    /** Lit segments, and the lettering printed next to them. */
    val lcdSegment: Color,
    /** Unlit segments showing through behind the digits. */
    val lcdGhost: Color,
    val lcdLabel: Color,
    /** The memory-in-pixel display of the sports models: one ink on one ground, nothing in between. */
    val mipPanel: Color,
    val mipEdge: Color,
    val mipInk: Color,
    val connected: Color,
)

/** Positive display: dark segments on a pale grey-green panel. */
private val LightWatchColors = WatchColors(
    lcdPanel = Color(0xFFC6CFC0),
    lcdEdge = Color(0xFFB3BDAD),
    lcdSegment = Color(0xFF171B16),
    lcdGhost = Color(0x12171B16),
    lcdLabel = Color(0xB8171B16),
    mipPanel = Color(0xFFDDE0DC),
    mipEdge = Color(0xFFC4C8C3),
    mipInk = Color(0xFF0B0D0C),
    connected = Color(0xFF1E8E3E),
)

/** Negative display: light segments on near-black. */
private val DarkWatchColors = WatchColors(
    lcdPanel = Color(0xFF090B0A),
    lcdEdge = Color(0xFF262B27),
    lcdSegment = Color(0xFFDFE6DC),
    lcdGhost = Color(0x12DFE6DC),
    lcdLabel = Color(0xA3DFE6DC),
    mipPanel = Color(0xFF050606),
    mipEdge = Color(0xFF262928),
    mipInk = Color(0xFFF1F3F0),
    connected = Color(0xFF6DD58C),
)

internal val LocalWatchColors = staticCompositionLocalOf { LightWatchColors }

/** Seven-segment digits for the clock panel. It has digits, `:`, `.`, `-` and a blank-width `!`. */
internal val LcdFont = FontFamily(Font(R.font.dseg7_classic_bold))

private val TextStyle.tabular: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

/** The Material type scale with fixed-width digits, so no number shifts as it changes. */
private val GsyncTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.tabular,
        displayMedium = displayMedium.tabular,
        displaySmall = displaySmall.tabular,
        headlineLarge = headlineLarge.tabular,
        headlineMedium = headlineMedium.tabular,
        headlineSmall = headlineSmall.tabular,
        titleLarge = titleLarge.tabular,
        titleMedium = titleMedium.tabular,
        titleSmall = titleSmall.tabular,
        bodyLarge = bodyLarge.tabular,
        bodyMedium = bodyMedium.tabular,
        bodySmall = bodySmall.tabular,
        labelLarge = labelLarge.tabular,
        labelMedium = labelMedium.tabular,
        labelSmall = labelSmall.tabular,
    )
}

/** Wallpaper-based colours, following the system dark mode unless [darkOverride] is set. */
@Composable
fun GsyncTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val context = LocalContext.current
    CompositionLocalProvider(LocalWatchColors provides if (dark) DarkWatchColors else LightWatchColors) {
        MaterialTheme(
            colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
            typography = GsyncTypography,
            content = content,
        )
    }
}
