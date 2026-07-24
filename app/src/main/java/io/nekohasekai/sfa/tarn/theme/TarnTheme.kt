package io.nekohasekai.sfa.tarn.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Corners stay nearly square: the design reads as drawn-with-a-ruler, not as Material cards.
 */
private val TarnShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(1.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(3.dp),
)

// Built from the palette constants directly, not from the TarnColors singleton: M3's
// scheme is only rebuilt when darkTheme flips, so it must not depend on execution order
// against the TarnColors.apply() call below. Named arguments only — darkColorScheme() /
// lightColorScheme() take ~30 positional parameters, too easy to silently swap two.
private fun colorSchemeFor(dark: Boolean): ColorScheme {
    val p: TarnPalette = if (dark) DarkPalette else LightPalette
    // onPrimary flips with the theme: dark mode's accent is a bright lime that needs dark
    // text on top of it, light mode's is a deliberately darkened green (see LightPalette)
    // that needs light text instead — same reasoning as onSecondary/onError.
    val onAccent = if (dark) p.Background else p.Surface
    return if (dark) {
        darkColorScheme(
            primary = p.Accent,
            onPrimary = onAccent,
            primaryContainer = p.AccentMuted,
            onPrimaryContainer = p.Accent,
            secondary = p.AccentDim,
            onSecondary = onAccent,
            background = p.Background,
            onBackground = p.TextPrimary,
            surface = p.Surface,
            onSurface = p.TextPrimary,
            surfaceVariant = p.SurfaceRaised,
            onSurfaceVariant = p.TextSecondary,
            outline = p.Border,
            outlineVariant = p.BorderDim,
            error = p.Danger,
            onError = onAccent,
        )
    } else {
        lightColorScheme(
            primary = p.Accent,
            onPrimary = onAccent,
            primaryContainer = p.AccentMuted,
            onPrimaryContainer = p.Accent,
            secondary = p.AccentDim,
            onSecondary = onAccent,
            background = p.Background,
            onBackground = p.TextPrimary,
            surface = p.Surface,
            onSurface = p.TextPrimary,
            surfaceVariant = p.SurfaceRaised,
            onSurfaceVariant = p.TextSecondary,
            outline = p.Border,
            outlineVariant = p.BorderDim,
            error = p.Danger,
            onError = onAccent,
        )
    }
}

/**
 * The caller resolves "system" vs. a pinned choice into a plain boolean — see
 * `resolveDarkTheme` in [io.nekohasekai.sfa.compose.MainActivity] — so this only has to
 * repaint when it changes.
 */
@Composable
fun TarnTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    // Runs during composition, ahead of everything below that reads TarnColors — including
    // the content() this composable wraps — so the whole shell repaints in one pass instead
    // of flashing the old palette for a frame.
    remember(darkTheme) { TarnColors.apply(palette) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = palette.Background.toArgb()
            window.navigationBarColor = palette.Background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorSchemeFor(darkTheme),
        typography = TarnTypography,
        shapes = TarnShapes,
        content = content,
    )
}
