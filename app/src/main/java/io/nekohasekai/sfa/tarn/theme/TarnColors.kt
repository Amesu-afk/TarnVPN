package io.nekohasekai.sfa.tarn.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * TarnVPN palette. Every screen reads these as plain object properties rather than through
 * a CompositionLocal, so each one is backed by [mutableStateOf]: reading `TarnColors.Accent`
 * inside a composable is a snapshot-state read like any other, and [TarnColors.apply] — called
 * once from [TarnTheme] whenever the light/dark choice changes — is enough to repaint the
 * whole shell without threading a theme parameter through every component.
 */
object TarnColors {
    var Background by mutableStateOf(DarkPalette.Background)
    var Surface by mutableStateOf(DarkPalette.Surface)
    var SurfaceRaised by mutableStateOf(DarkPalette.SurfaceRaised)

    var Border by mutableStateOf(DarkPalette.Border)
    var BorderDim by mutableStateOf(DarkPalette.BorderDim)

    var Accent by mutableStateOf(DarkPalette.Accent)
    var AccentDim by mutableStateOf(DarkPalette.AccentDim)
    var AccentMuted by mutableStateOf(DarkPalette.AccentMuted)

    var TextPrimary by mutableStateOf(DarkPalette.TextPrimary)
    var TextSecondary by mutableStateOf(DarkPalette.TextSecondary)
    var TextDim by mutableStateOf(DarkPalette.TextDim)

    var Offline by mutableStateOf(DarkPalette.Offline)
    var Danger by mutableStateOf(DarkPalette.Danger)

    var GridMark by mutableStateOf(DarkPalette.GridMark)

    fun apply(palette: TarnPalette) {
        Background = palette.Background
        Surface = palette.Surface
        SurfaceRaised = palette.SurfaceRaised
        Border = palette.Border
        BorderDim = palette.BorderDim
        Accent = palette.Accent
        AccentDim = palette.AccentDim
        AccentMuted = palette.AccentMuted
        TextPrimary = palette.TextPrimary
        TextSecondary = palette.TextSecondary
        TextDim = palette.TextDim
        Offline = palette.Offline
        Danger = palette.Danger
        GridMark = palette.GridMark
    }
}

/** One set of values for [TarnColors.apply] — see [DarkPalette] and [LightPalette]. */
interface TarnPalette {
    val Background: Color
    val Surface: Color
    val SurfaceRaised: Color
    val Border: Color
    val BorderDim: Color
    val Accent: Color
    val AccentDim: Color
    val AccentMuted: Color
    val TextPrimary: Color
    val TextSecondary: Color
    val TextDim: Color
    val Offline: Color
    val Danger: Color
    val GridMark: Color
}

/** Near-black canvas, the lime at full brightness — the original, unchanged look. */
object DarkPalette : TarnPalette {
    override val Background = Color(0xFF070707)
    override val Surface = Color(0xFF0F0F10)
    override val SurfaceRaised = Color(0xFF141416)
    override val Border = Color(0xFF232326)
    override val BorderDim = Color(0xFF19191B)
    override val Accent = Color(0xFFA6E22E)
    override val AccentDim = Color(0xFF6E9620)
    override val AccentMuted = Color(0x1AA6E22E)
    override val TextPrimary = Color(0xFFE8E8E8)
    override val TextSecondary = Color(0xFF8C8C92)
    override val TextDim = Color(0xFF5A5A5F)
    override val Offline = Color(0xFF5A5A5F)
    override val Danger = Color(0xFFE2564D)
    override val GridMark = Color(0x0FFFFFFF)
}

/**
 * Off-white canvas, same hue for the accent but pulled down in lightness — the source lime
 * (#A6E22E) has a WCAG contrast of only 1.45:1 against a white background, unreadable as
 * text or a hairline. #456B12 keeps the same hue at ~5.8:1, past the AA text threshold.
 */
object LightPalette : TarnPalette {
    override val Background = Color(0xFFF7F7F5)
    override val Surface = Color(0xFFFFFFFF)
    override val SurfaceRaised = Color(0xFFECECE8)
    override val Border = Color(0xFFDCDCD7)
    override val BorderDim = Color(0xFFE9E9E5)
    override val Accent = Color(0xFF456B12)
    override val AccentDim = Color(0xFF2E4A0C)
    override val AccentMuted = Color(0x1A456B12)
    override val TextPrimary = Color(0xFF161614)
    override val TextSecondary = Color(0xFF5C5C60)
    override val TextDim = Color(0xFF7E7E82)
    override val Offline = Color(0xFF7E7E82)
    override val Danger = Color(0xFFC53A30)
    override val GridMark = Color(0x0D000000)
}
