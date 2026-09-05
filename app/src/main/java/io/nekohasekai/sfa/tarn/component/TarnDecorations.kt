package io.nekohasekai.sfa.tarn.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.tarn.theme.TarnColors

/**
 * Draws the four corner ticks that frame the primary action button and the selected
 * server row. The ticks sit *inside* the bounds so they never clip against a parent.
 */
fun Modifier.cornerBrackets(
    color: Color = TarnColors.Accent,
    length: Dp = 10.dp,
    thickness: Dp = 1.dp,
    inset: Dp = 0.dp,
): Modifier = drawBehind {
    val len = length.toPx()
    val stroke = thickness.toPx()
    val pad = inset.toPx()
    val left = pad
    val top = pad
    val right = size.width - pad
    val bottom = size.height - pad

    fun line(from: Offset, to: Offset) = drawLine(color, from, to, strokeWidth = stroke)

    // top-left
    line(Offset(left, top), Offset(left + len, top))
    line(Offset(left, top), Offset(left, top + len))
    // top-right
    line(Offset(right - len, top), Offset(right, top))
    line(Offset(right, top), Offset(right, top + len))
    // bottom-left
    line(Offset(left, bottom - len), Offset(left, bottom))
    line(Offset(left, bottom), Offset(left + len, bottom))
    // bottom-right
    line(Offset(right - len, bottom), Offset(right, bottom))
    line(Offset(right, bottom - len), Offset(right, bottom))
}

/**
 * A hairline border, optionally dashed — used for the "СЕРВЕР" card, which in the
 * mockup reads as a plotted rectangle rather than a filled surface.
 */
fun Modifier.hairlineBorder(
    color: Color = TarnColors.Border,
    thickness: Dp = 1.dp,
    dashed: Boolean = false,
): Modifier = drawBehind {
    val stroke = thickness.toPx()
    val effect = if (dashed) {
        PathEffect.dashPathEffect(floatArrayOf(3f.dp.toPx(), 3f.dp.toPx()), 0f)
    } else {
        null
    }
    drawRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(width = stroke, pathEffect = effect),
    )
}

private val GRID_SPACING = 72.dp
private val GRID_MARK_SIZE = 5.dp

/**
 * The faint plus-mark lattice behind the home screen. Marks are spaced on a fixed grid
 * so the pattern stays put while content above it animates.
 */
@Composable
fun GridBackdrop(
    modifier: Modifier = Modifier,
    color: Color = TarnColors.GridMark,
) {
    Canvas(modifier = modifier) {
        drawPlusLattice(GRID_SPACING.toPx(), GRID_MARK_SIZE.toPx(), color)
    }
}

private fun DrawScope.drawPlusLattice(spacing: Float, mark: Float, color: Color) {
    if (spacing <= 0f) return
    val half = mark / 2f
    var y = spacing
    while (y < size.height) {
        var x = spacing
        while (x < size.width) {
            drawLine(color, Offset(x - half, y), Offset(x + half, y), strokeWidth = 1f)
            drawLine(color, Offset(x, y - half), Offset(x, y + half), strokeWidth = 1f)
            x += spacing
        }
        y += spacing
    }
}
