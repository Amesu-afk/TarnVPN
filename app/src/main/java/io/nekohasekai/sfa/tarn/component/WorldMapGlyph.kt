package io.nekohasekai.sfa.tarn.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.tarn.theme.TarnColors

/**
 * The dot-matrix world sitting in the corner of the server card.
 *
 * A coarse equirectangular land mask: 48 columns of 7.5° longitude by 20 rows of 7.5°
 * latitude, from +90° down to -60°. Antarctica is left off — at this size it would only
 * read as a smudge along the bottom edge.
 */
private val LAND_MASK = listOf(
    ".................####...........................",
    "...##########....#####...........##########.....",
    "..#############..#####..######################..",
    "################..###..########################.",
    ".###############.....##########################.",
    "..##############.....##########################.",
    "...#############.....##########################.",
    "...############......#########################..",
    ".....########........#########################..",
    "......######.........############..####.#####...",
    "........######.......#############.####.#####...",
    "..............#####...###########......#######..",
    ".............#######...#########.......#######..",
    ".............#######....########........######..",
    ".............#######....#######.......########..",
    "..............######.....######.......########..",
    "...............####.......####.........######...",
    "...............###............................##",
    "...............###..............................",
    "................##..............................",
)

// Where the pulsing marker sits when highlighted — Berlin, roughly, matching the mockup.
private const val MARKER_COLUMN = 25
private const val MARKER_ROW = 4

/**
 * @param highlighted when true a marker pulses at the fixed marker position; the home
 *   screen turns this on once the tunnel is up.
 */
@Composable
fun WorldMapGlyph(
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
) {
    val rows = LAND_MASK.size
    val columns = LAND_MASK.first().length
    val height = width * rows / columns

    Canvas(modifier = modifier.size(width = width, height = height)) {
        val stepX = size.width / columns
        val stepY = size.height / rows
        val radius = (minOf(stepX, stepY) * 0.30f).coerceAtLeast(0.6f)

        LAND_MASK.forEachIndexed { row, line ->
            line.forEachIndexed { column, cell ->
                if (cell != '#') return@forEachIndexed
                drawCircle(
                    color = if (highlighted) TarnColors.AccentDim else TarnColors.Border,
                    radius = radius,
                    center = Offset(column * stepX + stepX / 2f, row * stepY + stepY / 2f),
                )
            }
        }

        if (highlighted) {
            drawCircle(
                color = TarnColors.Accent,
                radius = radius * 2.4f,
                center = Offset(
                    MARKER_COLUMN * stepX + stepX / 2f,
                    MARKER_ROW * stepY + stepY / 2f,
                ),
            )
        }
    }
}
