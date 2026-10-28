// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.glyph.chart.GlyphChartStats
import tf.monochrome.android.glyph.chart.GlyphDisplayBpm
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.chart.bpmRange
import tf.monochrome.android.glyph.data.GlyphWheel
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.LocalGlassGround
import tf.monochrome.android.ui.components.glassPunches
import tf.monochrome.android.ui.components.GlassPanel

/**
 * What the music wheel's centre item is, above the wheel: its art and
 * names, DDR's Groove Radar for the chosen chart, the BPM meter, the meter
 * in feet and the density graph — every number moving to the next one as
 * the wheel turns or the difficulty changes, never jumping.
 *
 * A folder shows its own summary, and the radar and graph fall flat.
 *
 * [previewPosition] is the song preview's place in the song, for the beat
 * light; null while nothing plays. [compact] leaves out the radar and the
 * graph, for a screen without the height for them.
 */
@Composable
internal fun GlyphSongInfoCard(
    item: GlyphWheel.Item?,
    state: GlyphUiState,
    reducedMotion: Boolean,
    /** The wheel's haze source: the card floats over the wheel and frosts it. */
    haze: HazeState?,
    glass: PlayerGlassSettings,
    modifier: Modifier = Modifier,
    previewPosition: () -> Float? = { null },
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val song = (item as? GlyphWheel.Item.Song)?.song
    // The chart is only read once the wheel has rested on the song (the
    // sample delay); until then the card shows the song's own facts.
    val loaded = song != null && state.selectedSong?.trackId == song.trackId
    val chart = if (loaded) state.chart else null
    val simfile = if (loaded) state.simfile else null
    // Over the music's length, as StepMania's radar is.
    val songSeconds = song?.durationSeconds?.toFloat() ?: 0f
    val stats = remember(chart, simfile, songSeconds) {
        if (chart != null && simfile != null) GlyphChartStats.of(chart, simfile.timing, songSeconds) else GlyphChartStats.EMPTY
    }
    // The tempo the song says it is (#DISPLAYBPM) before the one its timing
    // reaches: a gimmick that jumps to 1500 BPM for a beat reads as the 150 it
    // is. A hidden one (`*`) reads as StepMania's "???".
    val display = simfile?.displayBpm
    val hidden = display is GlyphDisplayBpm.Hidden
    val range = (display as? GlyphDisplayBpm.Shown)?.let { it.low..it.high } ?: simfile?.timing?.bpmRange()
    val bpm = range?.endInclusive ?: song?.bpm ?: (item as? GlyphWheel.Item.Folder)?.section?.songs?.mapNotNull { it.bpm }?.maxOrNull() ?: 0f
    val colour = chart?.difficulty?.let(::difficultyColour) ?: scheme.onSurfaceVariant
    val title = when (item) {
        is GlyphWheel.Item.Song -> item.song.title
        is GlyphWheel.Item.Folder -> item.section.name
        null -> ""
    }
    val subtitle = when (item) {
        // A song with no chart says so here, where the panel's pills cannot.
        is GlyphWheel.Item.Song -> listOfNotNull(
            item.song.artist.ifBlank { null },
            item.section,
            item.song.chartState.label.takeIf { !item.song.hasChart },
        ).joinToString(" · ")
        is GlyphWheel.Item.Folder -> "${item.section.songs.size} songs · ${item.section.charted} ready"
        null -> ""
    }

    // The app's glass with the player sheet's frost: the wheel's rows and the
    // song's picture pass under it as soft colour, not as text bent at the rim.
    // One pane holds the graph too. The meter's stars are holes cut through
    // it, glass and frost, so the picture behind shows through them sharp.
    val carved = glassPunches(glass)
    val holes = remember { StarHoles() }
    val punch: DrawScope.() -> Unit = remember(holes) { { holes.cutInto(this) } }
    GlassPanel(
        hazeState = haze,
        glass = glass,
        modifier = modifier,
        avoidNavigationBar = false,
        frosted = true,
        punch = if (carved) punch else null,
        punchThrough = true,
    ) {
        Column(
            modifier = Modifier
                .onGloballyPositioned { holes.place(card = it) }
                .padding(MonoDimens.spacingMd),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd)) {
                if (!compact) {
                    // The radar sunk into the card in a well of its own, drawn
                    // as one with it: no second pane over the card. A rounded
                    // square rather than a disc, which would cut off the axis
                    // names in its corners.
                    GlyphGrooveRadar(
                        values = stats.radar,
                        colour = colour,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.size(140.dp).glassWell(RADAR_WELL_CORNER),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val art = song?.artworkUri ?: (item as? GlyphWheel.Item.Folder)?.section?.songs?.firstNotNullOfOrNull { it.artworkUri }
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(sectionColour(item?.let { sectionOf(it) } ?: ""))) {
                            if (art != null) CoverImage(url = art, contentDescription = null, size = 44.dp, cornerRadius = 10.dp)
                        }
                        Spacer(Modifier.width(MonoDimens.spacingSm))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    GlyphBpmMeter(
                        bpm = bpm,
                        low = range?.start?.takeIf { it < (range.endInclusive - 0.5f) },
                        hidden = hidden,
                        colour = colour,
                        reducedMotion = reducedMotion,
                        timing = simfile?.timing,
                        previewPosition = if (loaded) previewPosition else NO_POSITION,
                        previewPlaying = loaded && state.previewPlaying,
                    )
                    GlyphStars(
                        difficulty = chart?.difficulty,
                        meter = chart?.meter ?: 0,
                        colour = colour,
                        reducedMotion = reducedMotion,
                        carved = carved,
                        holes = holes,
                    )
                    // This phone's best on the chosen chart, as StepMania's wheel
                    // shows the grade beside it; only when there is one.
                    chart?.difficulty?.let { state.bestGrades[it] }?.let { best -> PersonalBest(best) }
                }
            }
            if (!compact) {
                // In a shallow well of its own, as the radar is.
                GlyphDensityGraph(
                    stats = stats,
                    colour = colour,
                    reducedMotion = reducedMotion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MonoDimens.spacingSm)
                        .glassWell(GRAPH_WELL_CORNER)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .height(44.dp),
                )
            }
        }
    }
}

private val NO_POSITION: () -> Float? = { null }

/** The radar's well: rounded, the axis names inside it. */
private val RADAR_WELL_CORNER = 22.dp
private val GRAPH_WELL_CORNER = 14.dp

/** Whether the card's glass lies over a dark page: its wells and holes are lit to suit. */
@Composable
private fun overDark(): Boolean =
    (LocalGlassGround.current ?: MaterialTheme.colorScheme.background).luminance() <= 0.5f

/**
 * A well sunk into the card: darker than the glass round it, the shadow of
 * its far wall inside its top, and its near lip catching the light.
 */
@Composable
private fun Modifier.glassWell(corner: Dp): Modifier {
    val dark = overDark()
    return drawBehind {
        val r = corner.toPx()
        val well = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
        drawPath(well, Color.Black.copy(alpha = if (dark) 0.4f else 0.07f))
        clipPath(well) {
            val wall = 9.dp.toPx()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = if (dark) 0.5f else 0.14f),
                    1f to Color.Transparent,
                    endY = wall,
                ),
                size = Size(size.width, wall),
            )
            val lip = 1.dp.toPx()
            drawRect(
                Color.White.copy(alpha = if (dark) 0.16f else 0.55f),
                topLeft = Offset(0f, size.height - lip),
                size = Size(size.width, lip),
            )
        }
    }
}

private fun sectionOf(item: GlyphWheel.Item): String = when (item) {
    is GlyphWheel.Item.Song -> item.section
    is GlyphWheel.Item.Folder -> item.section.name
}

/** Every value eased to its next one, or set at once with reduced motion. */
@Composable
private fun animated(target: Float, reducedMotion: Boolean, label: String): State<Float> =
    animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) snap() else tween(MORPH_MILLIS, easing = FastOutSlowInEasing),
        label = label,
    )

/**
 * DDR's Groove Radar: Stream at the top, then Chaos, Freeze, Air and Voltage
 * clockwise, each 0 to 1 from the centre. The shape morphs from one chart's
 * to the next.
 */
@Composable
internal fun GlyphGrooveRadar(
    values: List<Float>,
    colour: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val a0 by animated(values.getOrElse(0) { 0f }, reducedMotion, "radar0")
    val a1 by animated(values.getOrElse(1) { 0f }, reducedMotion, "radar1")
    val a2 by animated(values.getOrElse(2) { 0f }, reducedMotion, "radar2")
    val a3 by animated(values.getOrElse(3) { 0f }, reducedMotion, "radar3")
    val a4 by animated(values.getOrElse(4) { 0f }, reducedMotion, "radar4")
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = muted)
    val description = GlyphChartStats.AXES.zip(values).joinToString(", ") { (axis, v) -> "${axis.lowercase()} ${(v * 100).toInt()}" }

    Canvas(modifier.semantics { contentDescription = "Groove radar: $description" }) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - 16.dp.toPx()
        fun point(axis: Int, value: Float): Offset {
            val angle = Math.toRadians((-90.0 + axis * 72.0))
            return Offset(centre.x + (cos(angle) * radius * value).toFloat(), centre.y + (sin(angle) * radius * value).toFloat())
        }
        fun pentagon(scale: Float) = Path().apply {
            for (axis in 0 until 5) {
                val p = point(axis, scale)
                if (axis == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        for (ring in listOf(1f / 3f, 2f / 3f, 1f)) drawPath(pentagon(ring), ink.copy(alpha = 0.16f), style = Stroke(1.dp.toPx()))
        for (axis in 0 until 5) drawLine(ink.copy(alpha = 0.12f), centre, point(axis, 1f), 1.dp.toPx())

        val shape = Path().apply {
            listOf(a0, a1, a2, a3, a4).forEachIndexed { axis, v ->
                val p = point(axis, v.coerceAtLeast(0.04f))
                if (axis == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(
            shape,
            Brush.radialGradient(
                0f to lerp(colour, Color.White, 0.6f).copy(alpha = 0.55f),
                1f to colour.copy(alpha = 0.22f),
                center = centre,
                radius = radius,
            ),
        )
        // Its edge glows: two soft strokes under the line itself.
        drawPath(shape, colour.copy(alpha = 0.16f), style = Stroke(8.dp.toPx(), join = StrokeJoin.Round))
        drawPath(shape, colour.copy(alpha = 0.3f), style = Stroke(4.5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(shape, colour, style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))

        GlyphChartStats.AXES.forEachIndexed { axis, label ->
            val layout = measurer.measure(label, labelStyle)
            val at = point(axis, 1.18f)
            // Inside the canvas whatever the font scale, never under the card's rim.
            val x = (at.x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            val y = (at.y - layout.size.height / 2f).coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(x, y))
        }
    }
}

/**
 * The BPM meter: the tempo rolling to the next song's, a gauge to 300, and a
 * light that flashes on the preview's beats. A song whose tempo changes shows
 * its range.
 */
@Composable
private fun GlyphBpmMeter(
    bpm: Float,
    low: Float?,
    hidden: Boolean,
    colour: Color,
    reducedMotion: Boolean,
    timing: GlyphTiming?,
    previewPosition: () -> Float?,
    previewPlaying: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val shown by animated(bpm, reducedMotion, "bpm")
    val gauge by animated(if (hidden) 0f else (bpm / MAX_GAUGE_BPM).coerceIn(0f, 1f), reducedMotion, "bpmGauge")
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("SPEED", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, letterSpacing = 1.4.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    hidden -> "???"
                    low != null -> "${low.toInt()}–${shown.toInt()}"
                    else -> shown.toInt().toString()
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    contentDescription = if (hidden) {
                        "Tempo hidden"
                    } else if (low != null) {
                        "${low.toInt()} to ${bpm.toInt()} beats a minute"
                    } else {
                        "${bpm.toInt()} beats a minute"
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            Text("BPM", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            BeatLight(
                timing = timing,
                previewPosition = previewPosition,
                previewPlaying = previewPlaying,
                colour = colour,
                reducedMotion = reducedMotion,
            )
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(scheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(Modifier.fillMaxWidth(gauge).fillMaxHeight().clip(CircleShape).background(colour))
        }
    }
}

/**
 * A light that flashes on each beat of the preview as it plays: the preview's
 * place turned into a beat by the song's own timing, so it is on the music's
 * beat through every tempo change. Still while nothing plays, and with reduced
 * motion.
 */
@Composable
private fun BeatLight(
    timing: GlyphTiming?,
    previewPosition: () -> Float?,
    /** A preview has been started and not stopped ([GlyphUiState.previewPlaying]). */
    previewPlaying: Boolean,
    colour: Color,
    reducedMotion: Boolean,
) {
    if (reducedMotion || timing == null) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colour.copy(alpha = 0.5f)))
        return
    }
    // A frame clock read in the layer: each frame redraws the light and
    // recomposes nothing. It runs while a preview plays, and on until its
    // sound has stopped (a stopped preview fades out first), so its last
    // frame is the still light; with nothing playing it runs no frames at
    // all, where it used to run every frame the wheel was open.
    val position by rememberUpdatedState(previewPosition)
    val frame = produceState(0L, previewPlaying) {
        while (true) {
            withFrameNanos { value = it }
            if (!previewPlaying && position() == null) break
        }
    }
    Box(
        Modifier
            .size(9.dp)
            .graphicsLayer {
                @Suppress("UNUSED_EXPRESSION") frame.value
                val position = previewPosition()
                val pulse = if (position == null) {
                    0.5f
                } else {
                    val beat = timing.secondsToBeat(position)
                    1f - (beat - floor(beat)) * 0.7f
                }
                alpha = pulse
                val scale = 1f + (pulse - 0.3f) * 0.5f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(colour),
    )
}

/**
 * The meter as stars, one lit per level to [STARS]; past that, the number
 * says it. Each star is a hole through the card: [carved] when the glass
 * cuts them out ([StarHoles]), so the picture behind shows through the
 * unlit ones; drawn as holes where it cannot. A lit one is light coming up
 * through its hole, white at its heart, and blooming onto the glass round
 * it. They light one after another as the meter changes.
 */
@Composable
private fun GlyphStars(
    difficulty: StepManiaDifficulty?,
    meter: Int,
    colour: Color,
    reducedMotion: Boolean,
    carved: Boolean,
    holes: StarHoles,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = overDark()
    val lit by animated(meter.toFloat(), reducedMotion, "stars")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = difficulty?.sscName?.uppercase() ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (difficulty != null) meter.toString() else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                // Text, so the accent is moved until it reads on the glass;
                // the stars below are light and keep it as it is.
                color = menuAccent(colour),
            )
        }
        Canvas(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(STAR_ROW)
                .onGloballyPositioned { holes.place(row = it) },
        ) {
            val spots = starSpots(size, STAR_GAP.toPx())
            if (!carved) {
                // No glass to cut: the hole drawn, its near lip lit.
                val lip = 1.dp.toPx()
                for (spot in spots) {
                    val star = starPath(spot.centre, spot.diameter)
                    translate(0f, lip) { drawPath(star, Color.White.copy(alpha = if (dark) 0.2f else 0.6f)) }
                    drawPath(star, Color.Black.copy(alpha = if (dark) 0.55f else 0.18f))
                }
            }
            // The blooms first, so none lies over a neighbour's light.
            spots.forEachIndexed { i, spot ->
                val light = (lit - i).coerceIn(0f, 1f)
                if (light <= 0f) return@forEachIndexed
                drawCircle(
                    Brush.radialGradient(
                        0f to colour.copy(alpha = 0.55f * light),
                        1f to colour.copy(alpha = 0f),
                        center = spot.centre,
                        radius = spot.diameter * STAR_BLOOM,
                    ),
                    radius = spot.diameter * STAR_BLOOM,
                    center = spot.centre,
                )
            }
            spots.forEachIndexed { i, spot ->
                val light = (lit - i).coerceIn(0f, 1f)
                if (light <= 0f) return@forEachIndexed
                drawPath(
                    starPath(spot.centre, spot.diameter),
                    Brush.radialGradient(
                        0f to lerp(colour, Color.White, 0.88f),
                        0.45f to lerp(colour, Color.White, 0.45f),
                        1f to colour,
                        center = spot.centre,
                        radius = spot.diameter / 2f,
                    ),
                    alpha = light,
                )
            }
        }
    }
}

/**
 * Where the meter's stars are on the card, so the glass can cut them out:
 * the row's place and size in the card, kept as state the slab reads when it
 * draws, so it cuts again only when the row really moves on the card.
 */
private class StarHoles {
    private var card: LayoutCoordinates? = null
    private var row: LayoutCoordinates? = null
    private var spot by mutableStateOf<Rect?>(null)

    fun place(card: LayoutCoordinates? = null, row: LayoutCoordinates? = null) {
        if (card != null) this.card = card
        if (row != null) this.row = row
        val c = this.card?.takeIf { it.isAttached } ?: return
        val r = this.row?.takeIf { it.isAttached } ?: return
        val now = Rect(c.localPositionOf(r, Offset.Zero), r.size.toSize())
        if (now != spot) spot = now
    }

    /** The holes, in the card's own coordinates, which are the glass's. */
    fun cutInto(scope: DrawScope) = with(scope) {
        val at = spot ?: return@with
        translate(at.left, at.top) {
            for (star in starSpots(at.size, STAR_GAP.toPx())) drawPath(starPath(star.centre, star.diameter), Color.Black)
        }
    }
}

/** One star of the meter: its centre and its width. */
internal data class StarSpot(val centre: Offset, val diameter: Float)

/** [STARS] stars spread evenly across a row of [size], [gap] apart at least, as wide as the row is high at most. */
internal fun starSpots(size: Size, gap: Float): List<StarSpot> {
    if (size.width <= 0f || size.height <= 0f) return emptyList()
    val pitch = size.width / STARS
    val diameter = minOf(size.height, pitch - gap).coerceAtLeast(0f)
    return List(STARS) { i -> StarSpot(Offset(pitch * (i + 0.5f), size.height / 2f), diameter) }
}

/** A five-pointed star [diameter] across, point up, its outline centred on [centre]. */
internal fun starPath(centre: Offset, diameter: Float): Path {
    val outer = diameter / 2f
    val inner = outer * STAR_INNER
    // A star's points reach lower than its top by less: lowered by half the
    // difference, its outline is centred and not its points' circle.
    val drop = outer * (1f - cos(Math.toRadians(36.0)).toFloat()) / 2f
    return Path().apply {
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + k * 36.0)
            val x = centre.x + (cos(angle) * r).toFloat()
            val y = centre.y + drop + (sin(angle) * r).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * Rows a second across the chart, as ITGmania's themes draw it: a filled
 * area to a fixed 12 a second, so two charts compare at a glance. Each slice
 * eases to the next chart's.
 */
@Composable
internal fun GlyphDensityGraph(stats: GlyphChartStats, colour: Color, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val bins = List(GlyphChartStats.BINS) { i ->
        animated(stats.density.getOrElse(i) { 0f }, reducedMotion, "density$i")
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (stats.notes > 0) {
        "Density: ${"%.1f".format(stats.averageNps)} notes a second on average, ${"%.1f".format(stats.peakNps)} at the peak"
    } else {
        "No chart"
    }
    Column(modifier.semantics { contentDescription = label }) {
        Row {
            Text("DENSITY", style = MaterialTheme.typography.labelSmall, color = muted, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (stats.notes > 0) "PEAK ${"%.1f".format(stats.peakNps)} NPS" else "",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
            if (bins.isEmpty()) return@Canvas
            val step = size.width / (bins.size - 1)
            fun y(v: Float) = size.height - (v / DENSITY_SCALE).coerceIn(0f, 1f) * (size.height - 2.dp.toPx())
            val line = Path().apply {
                bins.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v.value)) else lineTo(i * step, y(v.value)) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, colour.copy(alpha = 0.28f))
            drawPath(line, colour, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Each difficulty's colour, apart in lightness as well as hue. */
internal fun difficultyColour(difficulty: StepManiaDifficulty): Color = when (difficulty) {
    StepManiaDifficulty.BEGINNER -> Color(0xFF5AC8FA)
    StepManiaDifficulty.EASY -> Color(0xFF7BD88F)
    StepManiaDifficulty.MEDIUM -> Color(0xFFF5C542)
    StepManiaDifficulty.HARD -> Color(0xFFFF6B6B)
    StepManiaDifficulty.CHALLENGE -> Color(0xFFB38CFF)
}

private const val MORPH_MILLIS = 520
private const val MAX_GAUGE_BPM = 300f
/** The meter's stars, and how they sit in their row. */
internal const val STARS = 12
private val STAR_ROW = 16.dp
private val STAR_GAP = 2.dp
private const val STAR_INNER = 0.46f
/** How far a lit star's light spreads onto the glass, as a share of its width. */
private const val STAR_BLOOM = 1.05f
/** Notes a second at the top of the density graph. */
private const val DENSITY_SCALE = 12f
