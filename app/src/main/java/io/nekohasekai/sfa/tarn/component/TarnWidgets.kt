package io.nekohasekai.sfa.tarn.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.theme.TarnActionStyle
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

/** Small caps section heading: "СТАТУС", "ОСНОВНЫЕ НАСТРОЙКИ", "ПРОТОКОЛ". */
@Composable
fun TarnSectionLabel(text: String, modifier: Modifier = Modifier, color: Color = TarnColors.TextDim) {
    Text(
        text = text.uppercase(),
        style = TarnLabelStyle,
        color = color,
        modifier = modifier,
    )
}

/** The pill that reads OFFLINE / CONNECTED next to the wordmark. */
@Composable
fun TarnStatusPill(text: String, active: Boolean, modifier: Modifier = Modifier) {
    val dotColor by animateColorAsState(
        targetValue = if (active) TarnColors.Accent else TarnColors.Offline,
        animationSpec = tween(220),
        label = "statusDot",
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = TarnLabelStyle,
            color = if (active) TarnColors.Accent else TarnColors.TextDim,
        )
    }
}

/** "TarnVPN_" — the trailing underscore is part of the mark, not a cursor. */
@Composable
fun TarnWordmark(modifier: Modifier = Modifier, color: Color = TarnColors.Accent) {
    Text(
        text = "TarnVPN_",
        style = TarnActionStyle.copy(letterSpacing = 0.5.sp),
        color = color,
        modifier = modifier,
    )
}

/**
 * The primary CONNECT / DISCONNECT control: a bordered rectangle with corner ticks,
 * green while idle and dimmed while the service is mid-transition.
 */
@Composable
fun TarnActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = TarnColors.Accent,
    trailingIcon: ImageVector? = null,
) {
    val tint by animateColorAsState(
        targetValue = if (enabled) accent else TarnColors.TextDim,
        animationSpec = tween(200),
        label = "actionTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .hairlineBorder(color = tint.copy(alpha = 0.55f))
            .cornerBrackets(color = tint, length = 12.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text.uppercase(), style = TarnActionStyle, color = tint)
        // The arrow sits against the right edge rather than beside the caption, so the
        // caption stays optically centred in the frame.
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .size(16.dp),
            )
        }
    }
}

/** Underlined text tab used for "РЕКОМЕНДОВАННЫЕ" / "ВСЕ СТРАНЫ". */
@Composable
fun TarnLabelTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) TarnColors.Accent else TarnColors.TextDim,
        animationSpec = tween(180),
        label = "tabTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        // IntrinsicSize.Min is load-bearing: without it this Column sits in an
        // unweighted Row, which hands every child the Row's full width to measure
        // against. The underline's fillMaxWidth() below would then resolve to that
        // (almost the whole row) instead of the label's own width, and two such
        // full-width tabs side by side overflow the row — "Все страны" was landing
        // half off-screen. Min intrinsic width pins the Column to the label's actual
        // width first, so fillMaxWidth() has something correct to fill.
        modifier = modifier
            .width(IntrinsicSize.Min)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text.uppercase(), style = TarnLabelStyle, color = tint)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (selected) TarnColors.Accent else Color.Transparent),
        )
    }
}

/** Squared-off toggle matching the mockup's pill switches. */
@Composable
fun TarnSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> TarnColors.BorderDim
            checked -> TarnColors.Accent
            else -> TarnColors.SurfaceRaised
        },
        animationSpec = tween(200),
        label = "switchTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(200),
        label = "switchKnob",
    )
    // The knob stays light in both positions — in the mockup it reads as a white cap on
    // either a green or a charcoal track.
    val knobColor by animateColorAsState(
        targetValue = if (enabled) TarnColors.TextPrimary else TarnColors.TextDim,
        animationSpec = tween(200),
        label = "switchKnobColor",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(44.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(track)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset)
                .align(Alignment.CenterStart)
                .size(20.dp)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}

/** A settings row carrying a title, an explanatory line and a trailing toggle. */
@Composable
fun TarnToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TarnMetaStyle.copy(fontSize = 14.sp),
                color = if (enabled) TarnColors.TextPrimary else TarnColors.TextDim,
            )
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = description, style = TarnMetaStyle, color = TarnColors.TextDim)
            }
        }
        Spacer(Modifier.width(16.dp))
        TarnSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A settings row that navigates onward, with a chevron and an optional value line. */
@Composable
fun TarnNavRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chevron: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TarnMetaStyle.copy(fontSize = 14.sp),
                color = if (enabled) TarnColors.TextPrimary else TarnColors.TextDim,
            )
            if (value != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = value, style = TarnMetaStyle, color = TarnColors.TextDim)
            }
        }
        if (chevron != null) {
            Icon(
                imageVector = chevron,
                contentDescription = null,
                tint = TarnColors.TextDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Two mutually exclusive choices side by side, used for the split-tunnelling mode. Reads as
 * one framed strip so it does not compete with the toggle rows above it.
 */
@Composable
fun TarnSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val tint by animateColorAsState(
                targetValue = when {
                    !enabled -> TarnColors.TextDim
                    selected -> TarnColors.Accent
                    else -> TarnColors.TextSecondary
                },
                animationSpec = tween(180),
                label = "segmentTint",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .hairlineBorder(color = if (selected) tint else TarnColors.BorderDim)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = option.uppercase(), style = TarnLabelStyle, color = tint)
            }
        }
    }
}

/**
 * Renders a [Latency] reading consistently wherever one is shown — the servers list and
 * the DNS picker both use this, so a ping under 80ms reads as fast in the same color in
 * both places.
 */
@Composable
fun TarnLatencyReadout(latency: Latency, modifier: Modifier = Modifier) {
    val (text, color) = when (latency) {
        is Latency.Ok -> stringResource(R.string.tarn_latency_ms, latency.millis) to
            when {
                latency.millis < 80 -> TarnColors.Accent
                latency.millis < 200 -> TarnColors.TextSecondary
                else -> TarnColors.Danger
            }

        Latency.Probing -> "···" to TarnColors.TextDim
        Latency.Unreachable -> stringResource(R.string.tarn_latency_unreachable) to TarnColors.Danger
        Latency.Unknown -> "—" to TarnColors.TextDim
    }
    Text(text = text, style = TarnLabelStyle, color = color, modifier = modifier)
}

/** Grouped container with the hairline outline used across the settings screen. */
@Composable
fun TarnPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TarnColors.Surface)
            .hairlineBorder(color = TarnColors.Border),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

/** Hairline divider used between rows inside a [TarnPanel]. */
@Composable
fun TarnRowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(TarnColors.BorderDim),
    )
}
