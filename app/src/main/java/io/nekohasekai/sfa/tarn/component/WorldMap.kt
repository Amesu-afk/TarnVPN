package io.nekohasekai.sfa.tarn.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.tarn.data.CountryGeo
import io.nekohasekai.sfa.tarn.data.GeoPoint
import io.nekohasekai.sfa.tarn.theme.TarnColors

/**
 * The dot-matrix world used on the home screen.
 *
 * Both maps are coarse equirectangular land masks: one cell per 7.5° of longitude in the
 * compact glyph, one per 5° in the full-width panel. The glyph keeps its hand-drawn shape
 * because at 104dp the extra rows of the denser mask only turn into noise; the panel is
 * generated from Natural Earth's 110m land polygons, where the finer grid is what makes the
 * continents read at a glance. Antarctica is left off both — it would only be a smudge along
 * the bottom edge, and no one connects through it.
 */
private val GLYPH_LAND_MASK = listOf(
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

/**
 * 72 × 30 cells between 90°N and 60°S, generated from `ne_110m_land.geojson` — edit the
 * shape there and re-sample rather than by hand; a single misplaced dot at this size is a
 * newly discovered island.
 *
 * Sampling a 2 × 2 grid inside every cell and keeping it when *any* corner is land is
 * deliberate. Centre sampling gave a cleaner silhouette but lost Iceland, Sri Lanka and the
 * Caribbean altogether, and those are exactly the places a pin is asked to point at. The
 * cost is a slightly fuller coastline, which at 4dp a dot reads as the same map.
 */
private val PANEL_LAND_MASK = listOf(
    "........................................................................",
    "..................################......................................",
    "...........#.########.##########......##........##.....####.............",
    "#...#......####.######...#######..............##.##################.....",
    "####################.###.######.#.....######.###########################",
    "...####################..###....#....###################################",
    "...###..###########.####...........#.############################..##...",
    "..........###############.........##############################...#....",
    "...........############.##.........#############################........",
    "...........###########............#############################.#.......",
    "...........##########.............####.#####################.#.##.......",
    "............#########.............###########################.#.........",
    ".............####..#.............############################...........",
    "..............###.###...........################.############...........",
    "...............####..##.........################..###..###..#...........",
    "..................##.##.........##############.....#...###..#...........",
    "...................######........#############......#..##....#..........",
    "....................######............########....#....##.##............",
    "....................#########.........#######...........#.###.###.......",
    "....................#########.........######.............#.###.###......",
    "....................#########.........######.#................#.........",
    ".....................#######..........######.#..............######.....#",
    "......................######..........#####.##.#..........########......",
    ".....................######............####...............#########.....",
    ".....................#####.............###.................########.....",
    ".....................####.......................................##.....#",
    ".....................##..........................................#....#.",
    ".....................##..............................................#..",
    ".....................##.#...............................................",
    "........................................................................",
)

/**
 * The compact glyph in the corner of the server card.
 *
 * @param highlighted when true the land brightens and a marker lands on [countryCode];
 *   the home screen turns this on once the tunnel is up.
 */
@Composable
fun WorldMapGlyph(
    highlighted: Boolean,
    countryCode: String?,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
) {
    val rows = GLYPH_LAND_MASK.size
    val columns = GLYPH_LAND_MASK.first().length
    val height = width * rows / columns
    val marker = CountryGeo.markerFor(countryCode)

    Canvas(modifier = modifier.size(width = width, height = height)) {
        val stepX = size.width / columns
        val stepY = size.height / rows
        val radius = (minOf(stepX, stepY) * 0.30f).coerceAtLeast(0.6f)

        drawLandMask(
            mask = GLYPH_LAND_MASK,
            color = if (highlighted) TarnColors.AccentDim else TarnColors.Border,
            radius = radius,
        )

        if (highlighted && marker != null) {
            val center = geoOffset(marker, size, columns, rows)
            // The glyph is too small for rings; the bright dot is the whole signal.
            drawCircle(color = TarnColors.Accent, radius = radius * 2.4f, center = center)
        }
    }
}

/**
 * The full-width map that covers the middle of the home screen while the tunnel is up.
 *
 * The map fits itself into whatever width and height the caller leaves: it keeps the 2.4:1
 * equirectangular ratio, so a short screen gets a smaller world rather than a cropped one.
 * [countryCode] only decides the pin — the map is still useful without it, so a server whose
 * country we could not work out gets the same panel, just unpinned.
 */
@Composable
fun WorldMapPanel(
    countryCode: String?,
    modifier: Modifier = Modifier,
) {
    val rows = PANEL_LAND_MASK.size
    val columns = PANEL_LAND_MASK.first().length
    val marker = CountryGeo.markerFor(countryCode)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val ratio = columns.toFloat() / rows
        // Without a bounded height this sits in a wrapping Column: width decides, and the
        // aspect ratio gives the height back. With one, the smaller of the two wins.
        val width = if (maxHeight.value.isFinite()) minOf(maxWidth, maxHeight * ratio) else maxWidth
        val height = width / ratio

        Box(modifier = Modifier.size(width = width, height = height)) {
            // Static layer: no animated state is read here, so Compose does not repaint
            // fifteen hundred dots sixty times a second for the pin's pulse.
            Canvas(modifier = Modifier.matchParentSize()) {
                drawGraticule(columns = columns, rows = rows)
                drawLandMask(
                    mask = PANEL_LAND_MASK,
                    color = TarnColors.AccentDim.copy(alpha = 0.85f),
                    radius = (minOf(size.width / columns, size.height / rows) * 0.30f)
                        .coerceAtLeast(0.6f),
                )
            }
            if (marker != null) {
                MapPin(
                    marker = marker,
                    columns = columns,
                    rows = rows,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * Meridians every 30° and parallels from 60°N down to 30°S, drawn under the land so they read
 * as cartographic paper rather than a second layer of dots.
 */
private fun DrawScope.drawGraticule(columns: Int, rows: Int) {
    val hairline = 1.dp.toPx()
    val color = TarnColors.BorderDim
    var longitude = -150
    while (longitude < 180) {
        val x = (longitude + 180f) / 360f * size.width
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = hairline)
        longitude += 30
    }
    var latitude = -30
    while (latitude <= 60) {
        val y = latitudeFraction(latitude.toFloat(), columns, rows) * size.height
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = hairline)
        latitude += 30
    }
}

private fun DrawScope.drawLandMask(mask: List<String>, color: Color, radius: Float) {
    val rows = mask.size
    val columns = mask.first().length
    val stepX = size.width / columns
    val stepY = size.height / rows
    mask.forEachIndexed { row, line ->
        line.forEachIndexed { column, cell ->
            if (cell != '#') return@forEachIndexed
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(column * stepX + stepX / 2f, row * stepY + stepY / 2f),
            )
        }
    }
}

/**
 * The pulsing pin: two rings on a half-period offset, a static ring, and a core dot. It reads
 * as a live link even when nothing on the map moves, which is the point — the panel is only
 * on screen while the tunnel is up.
 */
@Composable
private fun MapPin(
    marker: GeoPoint,
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "worldMapPin")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "worldMapPinPulse",
    )

    Canvas(modifier = modifier) {
        val center = geoOffset(marker, size, columns, rows)
        val hairline = 1.dp.toPx()

        // A halo wide enough to pull the eye to the pin without hiding the continent under it.
        drawCircle(TarnColors.Accent.copy(alpha = 0.05f), radius = 14.dp.toPx(), center = center)
        drawCircle(TarnColors.Accent.copy(alpha = 0.08f), radius = 9.dp.toPx(), center = center)

        for (offset in floatArrayOf(0f, 0.5f)) {
            val phase = (pulse + offset) % 1f
            drawCircle(
                color = TarnColors.Accent.copy(alpha = (1f - phase) * 0.38f),
                radius = (4f + phase * 10f).dp.toPx(),
                center = center,
                style = Stroke(width = hairline),
            )
        }

        drawCircle(
            color = TarnColors.Accent.copy(alpha = 0.55f),
            radius = 5.dp.toPx(),
            center = center,
            style = Stroke(width = hairline),
        )
        // A slice of the page under the core, so the pin does not melt into the land dots.
        drawCircle(
            color = TarnColors.Background.copy(alpha = 0.9f),
            radius = 4.dp.toPx(),
            center = center,
        )
        drawCircle(color = TarnColors.Accent, radius = 2.3.dp.toPx(), center = center)

        // Four ticks: a reticle rather than a dot floating on the dots.
        val tickStart = 7.dp.toPx()
        val tickEnd = 10.dp.toPx()
        val tickColor = TarnColors.Accent.copy(alpha = 0.75f)
        drawLine(tickColor, center + Offset(-tickEnd, 0f), center + Offset(-tickStart, 0f), hairline)
        drawLine(tickColor, center + Offset(tickStart, 0f), center + Offset(tickEnd, 0f), hairline)
        drawLine(tickColor, center + Offset(0f, -tickEnd), center + Offset(0f, -tickStart), hairline)
        drawLine(tickColor, center + Offset(0f, tickStart), center + Offset(0f, tickEnd), hairline)
    }
}

/** Horizontal position of a [longitude] in this mask, as a 0..1 fraction. */
private fun longitudeFraction(longitude: Double): Float =
    ((longitude + 180.0) / 360.0).toFloat().coerceIn(0f, 1f)

/** Vertical position of a [latitude], as a 0..1 fraction of the mask's top-to-bottom span. */
private fun latitudeFraction(latitude: Float, columns: Int, rows: Int): Float {
    val span = 360f * rows / columns
    return ((90f - latitude) / span).coerceIn(0f, 1f)
}

/** Where [point] lands inside a mask of [columns] × [rows] drawn at [size]. */
private fun geoOffset(point: GeoPoint, size: Size, columns: Int, rows: Int): Offset = Offset(
    x = longitudeFraction(point.longitude) * size.width,
    y = latitudeFraction(point.latitude.toFloat(), columns, rows) * size.height,
)
