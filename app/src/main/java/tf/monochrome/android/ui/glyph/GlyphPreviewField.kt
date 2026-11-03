// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.engine.GlyphAutoplay
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.withTurn

/**
 * A playfield that plays itself: the options screen's preview and the start
 * menu's backdrop.
 *
 * It is the game's own playfield, drawing a real engine that [GlyphAutoplay]
 * plays perfectly, so what it shows is exactly what a run looks like with
 * these options: the speed, the turn, the appearance, the receptors, the
 * notes, the explosions and the rays. It loops one stretch of the chart, and
 * when [musicPosition] says where the song preview is, it follows the music
 * rather than its own clock, so the arrows land on the beat being heard.
 */
@Composable
fun GlyphPreviewField(
    chart: GlyphChart,
    timing: GlyphTiming,
    turn: GlyphTurn,
    scrollMode: GlyphScrollMode,
    skin: GlyphSkin,
    look: GlyphFieldLook,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    /** The stretch looped when no music says otherwise: start and length, song seconds. */
    loop: Pair<Float, Float>,
    receptorInset: Dp,
    modifier: Modifier = Modifier,
    noteScale: Float = 1f,
    /** Scroll at the game's scale on this screen (see [GlyphPlayfield]'s `gameScale`). */
    gameScale: Boolean = false,
    reducedMotion: Boolean = false,
    /** Fade at the loop's ends; off for a loop written to wrap seamlessly. */
    fadeLoop: Boolean = true,
    /** Where the music playing with the field is, in song seconds, or null. */
    musicPosition: () -> Float? = { null },
) {
    val engine = remember(chart, turn, timing) {
        GlyphGameplayEngine(chart = chart.withTurn(turn), timing = timing)
    }
    DisposableEffect(engine) { onDispose { engine.close() } }
    val driver = remember(engine) { PreviewDriver(engine) }
    val startedAt = remember { System.nanoTime() }
    val (loopStart, loopLength) = loop

    // The field fades out into the end of the stretch and in from its start,
    // as the preview's music does, so the wrap is never a cut.
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reducedMotion, fadeLoop) {
        if (reducedMotion || !fadeLoop) return@LaunchedEffect
        while (true) withFrameNanos { frame.longValue = it }
    }

    val position: () -> Float = remember(driver, loopStart, loopLength, reducedMotion) {
        {
            val music = musicPosition()
            val at = when {
                reducedMotion -> loopStart + loopLength / 2f
                music != null -> music
                else -> loopStart + ((System.nanoTime() - startedAt) / 1e9f) % loopLength.coerceAtLeast(1f)
            }
            driver.advance(at)
        }
    }

    // MMod is solved against the chart's fastest tempo, which the options
    // only know once a run starts.
    val mode = (scrollMode as? GlyphScrollMode.MMod)?.let {
        GlyphScrollMode.MMod(it.targetBpm, timing.bpmRange.endInclusive)
    } ?: scrollMode

    GlyphPlayfield(
        engine = engine,
        assets = assets,
        palette = palette,
        positionProvider = position,
        heldLanes = emptySet(),
        scrollMode = mode,
        timing = timing,
        reducedMotion = reducedMotion,
        skin = skin,
        explosionProvider = driver::flashes,
        comboProvider = driver::combo,
        look = look,
        preview = true,
        receptorInset = receptorInset,
        noteScale = noteScale,
        gameScale = gameScale,
        modifier = modifier.graphicsLayer {
            @Suppress("UNUSED_EXPRESSION") frame.longValue
            if (reducedMotion || !fadeLoop) return@graphicsLayer
            val inClip = driver.lastPosition - loopStart
            alpha = GlyphPreviewPlayer.fadeAt((inClip * 1000f).toLong(), (loopLength * 1000f).toLong())
                .coerceAtLeast(MIN_ALPHA)
        },
    )
}

/**
 * Plays the engine to each frame's position and keeps what the field reads
 * while drawing: the flashes and the combo.
 */
private class PreviewDriver(private val engine: GlyphGameplayEngine) {
    private val autoplay = GlyphAutoplay()
    private val flashMap = LaneFlashes()
    private var liveCombo = 0

    var lastPosition = Float.NaN
        private set

    fun flashes(): LaneFlashes = flashMap

    fun combo(): Int = liveCombo

    /** Play to [position] and return it, held still against a reading a hair behind the last. */
    fun advance(position: Float): Float {
        val last = lastPosition
        var at = position
        if (!last.isNaN() && at < last) {
            // A music clock can step back a few milliseconds between reads;
            // only a real jump back is the loop wrapping.
            if (last - at < WRAP_SECONDS) at = last else flashMap.clear()
        }
        if (at == last) return at
        // Joined partway in: what is behind the first position is set aside
        // rather than played all at once in one frame.
        if (last.isNaN()) autoplay.rewind(engine, at)
        lastPosition = at
        for (event in autoplay.advance(engine, at)) {
            if (!event.isTail) flashMap.set(event.lane, System.nanoTime(), event.judgement.isHit)
        }
        liveCombo = engine.combo
        return at
    }

    private companion object {
        const val WRAP_SECONDS = 0.25f
    }
}

/** Faded at a loop's edges, never quite out, so the field is never blank. */
private const val MIN_ALPHA = 0.15f
