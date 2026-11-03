package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.LinkArea
import tf.monochrome.android.domain.model.LinkShape
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.domain.model.MusicLinkFollower
import tf.monochrome.android.domain.model.VisualLink
import tf.monochrome.android.domain.model.VisualLinkTargets
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.ui.theme.reduceMotion
import tf.monochrome.android.visualizer.AmbientVisualizerSettings

/**
 * This frame's values of every visual setting that follows the music.
 *
 * The values are snapshot state written once a frame by
 * [rememberVisualLinkDriver] and meant to be read **only in draw and layer
 * blocks**, the way the bass pulse is: a draw-phase read redraws the layer
 * that made it and recomposes nothing. Read in composition, every reader
 * would recompose every frame, which is the cost the lyric renderer is built
 * to avoid. [linkedKeys] is the exception: it changes only when the links do,
 * so composition may read it to decide whether a clock has to run.
 *
 * The saved settings are never written. A link changes what is drawn, so a
 * preset, a theme pack and the Studio's sliders always hold the listener's own
 * values.
 *
 * A driver nested in another ([parent]) reads its parent's values for every
 * setting it does not drive itself: the player's driver runs the lyrics and
 * the player's controls, and the app's own (the UI panels and the CRT effect)
 * keeps moving the panels inside the player as everywhere else.
 */
@Stable
class VisualLinkLive(private val parent: VisualLinkLive? = null) {
    /** Each linked setting's value this frame, keyed like [tf.monochrome.android.domain.model.VisualLinkTarget.key]. */
    var values: Map<String, Float> by mutableStateOf(emptyMap())
        internal set

    /** Each link's band strength this frame, 0..1: the editor's meter. */
    var strengths: Map<String, Float> by mutableStateOf(emptyMap())
        internal set

    /** The settings being moved right now. Safe to read in composition. */
    var linkedKeys: Set<String> by mutableStateOf(emptySet())
        internal set

    private var mergedOwn: Map<String, Float>? = null
    private var mergedUp: Map<String, Float>? = null
    private var merged: Map<String, Float> = emptyMap()

    /**
     * This frame's values: this driver's own over its parent's. Snapshot
     * state like [values]: read it only in a draw or layer block.
     */
    val current: Map<String, Float>
        get() {
            val own = values
            val up = parent?.current ?: return own
            if (up.isEmpty()) return own
            if (own.isEmpty()) return up
            if (mergedOwn === own && mergedUp === up) return merged
            val out = HashMap<String, Float>(up.size + own.size)
            out.putAll(up)
            out.putAll(own)
            mergedOwn = own
            mergedUp = up
            merged = out
            return out
        }

    /** One copy per frame, shared by every layer that asks: the per-letter wave alone asks once a letter. */
    private class FrameCache<T> {
        var base: T? = null
        var values: Map<String, Float>? = null
        var out: T? = null

        inline fun get(base: T, values: Map<String, Float>, make: () -> T): T {
            val cached = out
            if (cached != null && this.base === base && this.values === values) return cached
            val made = make()
            this.base = base
            this.values = values
            out = made
            return made
        }
    }

    private val lyricsCache = FrameCache<LyricsFxSettings>()
    private val playerCache = FrameCache<PlayerGlassSettings>()
    private val panelsCache = FrameCache<PlayerGlassSettings>()
    private val tvCache = FrameCache<GlyphTvTuning>()
    private val ambientCache = FrameCache<AmbientVisualizerSettings>()

    /** [base] with this frame's linked values in it. Call it from a draw or layer block. */
    fun lyrics(base: LyricsFxSettings): LyricsFxSettings {
        val v = current
        if (v.isEmpty()) return base
        return lyricsCache.get(base, v) { VisualLinkTargets.applyLyrics(base, v) }
    }

    /**
     * [base] as [area]'s glass ([LinkArea.PLAYER] or [LinkArea.PANELS]) with
     * this frame's linked values in it. Call it from a draw or layer block.
     */
    fun glass(base: PlayerGlassSettings, area: LinkArea): PlayerGlassSettings {
        val v = current
        if (v.isEmpty()) return base
        val cache = if (area == LinkArea.PLAYER) playerCache else panelsCache
        return cache.get(base, v) { VisualLinkTargets.applyGlass(base, area, v) }
    }

    /** The CRT effect's [base] tuning with this frame's linked values in it. Call it from a layer block. */
    fun tv(base: GlyphTvTuning): GlyphTvTuning {
        val v = current
        if (v.isEmpty()) return base
        return tvCache.get(base, v) { VisualLinkTargets.applyTv(base, v) }
    }

    /** [base] with the ambient visualizer's linked settings at this frame's values. */
    fun ambient(base: AmbientVisualizerSettings): AmbientVisualizerSettings {
        val v = current
        if (v.isEmpty()) return base
        return ambientCache.get(base, v) { VisualLinkTargets.applyAmbient(base, v) }
    }

    internal fun clear() {
        if (values.isNotEmpty()) values = emptyMap()
        if (strengths.isNotEmpty()) strengths = emptyMap()
    }

    companion object {
        /** Nothing linked: what a renderer outside any driver sees. Never driven. */
        val None = VisualLinkLive()
    }
}

/** The links driving what is drawn here; [VisualLinkLive.None] where nothing drives them. */
val LocalVisualLinks = staticCompositionLocalOf { VisualLinkLive.None }

/**
 * Whose glass [LocalPlayerGlass] is here, for the links that move it: the
 * player's own controls, or the UI panels' glass every other pane is made of.
 * Provided beside every `LocalPlayerGlass provides`.
 */
val LocalGlassArea = staticCompositionLocalOf { LinkArea.PANELS }

/**
 * A short history of a band's level, so the visuals can wait for the sound to
 * reach the listener: the analyser hears the music before the speaker or the
 * Bluetooth headphones play it. Read from the frame clock only.
 */
internal class LevelDelay(capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val levels = FloatArray(capacity)
    private var head = -1
    private var count = 0

    fun push(timeNanos: Long, level: Float) {
        head = (head + 1) % times.size
        times[head] = timeNanos
        levels[head] = level
        if (count < times.size) count++
    }

    /** The newest level recorded at or before [timeNanos]; the oldest kept when all are newer. */
    fun at(timeNanos: Long): Float {
        if (count == 0) return -120f
        var i = head
        repeat(count) {
            if (times[i] <= timeNanos) return levels[i]
            i = (i - 1 + times.size) % times.size
        }
        return levels[(head - count + 1 + times.size) % times.size]
    }
}

/**
 * Runs the visual [links] against the analyser [tap] while [active], once a
 * frame, and returns their values. Holds a stake on the analyser only while the
 * screen is started, and rests — values cleared, no frames — when nothing is
 * linked, every link is paused, animations are off (Reduce motion), or every
 * link has gone quiet ("Pause when silent"); a quiet link wakes on the next
 * analysis loud enough to move it.
 *
 * [delayMs] is the lyrics' Bluetooth delay: the visuals wait that long behind
 * the analyser, so a flash lands with the kick the listener hears.
 */
@Composable
fun rememberVisualLinkDriver(
    tap: SpectrumAnalyzerTap?,
    links: List<VisualLink>,
    active: Boolean,
    delayMs: Float,
    /** A wider driver whose values show through for every setting this one does not drive. */
    parent: VisualLinkLive? = null,
): VisualLinkLive {
    val live = remember(parent) { VisualLinkLive(parent) }
    val running = remember(links) { links.filterNot { it.paused } }
    val on = active && tap != null && running.isNotEmpty() && !reduceMotion()
    val keys = remember(running, on) { if (on) running.map { it.target }.toSet() else emptySet() }
    SideEffect {
        if (live.linkedKeys != keys) live.linkedKeys = keys
        if (!on) live.clear()
    }
    if (!on || tap == null) return live

    // Held while the screen is started, not merely composed: in the
    // background, or with the phone locked, nothing is reading the values.
    androidx.lifecycle.compose.LifecycleStartEffect(tap) {
        tap.acquireLinks()
        onStopOrDispose {
            tap.releaseLinks()
            live.clear()
        }
    }
    val current = rememberUpdatedState(running)
    val delay = rememberUpdatedState(delayMs)
    LaunchedEffect(tap, keys) {
        val followers = HashMap<String, MusicLinkFollower>()
        val history = HashMap<String, LevelDelay>()
        var last = -1L
        while (true) {
            var asleep = false
            withFrameNanos { now ->
                val dt = if (last < 0) 0.016f else ((now - last) / 1_000_000_000f)
                last = now
                val spectrum = tap.linkPower.value
                val linksNow = current.value
                val values = HashMap<String, Float>(linksNow.size * 2)
                val strengths = HashMap<String, Float>(linksNow.size * 2)
                val lagNanos = (delay.value.coerceAtLeast(0f) * 1_000_000f).toLong()
                var allQuiet = true
                for (link in linksNow) {
                    val f = followers.getOrPut(link.target) { MusicLinkFollower(link.shape) }
                    if (f.shape != link.shape) f.shape = link.shape
                    val line = history.getOrPut(link.target) { LevelDelay() }
                    line.push(now, levelOf(spectrum, link.shape))
                    values[link.target] = f.step(line.at(now - lagNanos), dt)
                    strengths[link.target] = f.strength
                    if (!f.asleep) allQuiet = false
                }
                live.values = values
                live.strengths = strengths
                asleep = allQuiet
            }
            if (asleep) {
                // Nothing to draw until something is loud enough to move.
                tap.linkPower.first { spectrum -> current.value.any { levelOf(spectrum, it.shape) + it.shape.boostDb > LinkShape.SILENT_PAUSE_DB } }
                last = -1L
            }
        }
    }
    return live
}

/**
 * A link's band level: the energy of its own band alone, in dBFS, from the
 * links' own spectrum ([SpectrumAnalyzerTap.linkPower]), never the display's,
 * whose re-centring moved every band with the others.
 */
private fun levelOf(spectrum: SpectrumAnalyzerTap.LinkPower, shape: LinkShape): Float =
    tf.monochrome.android.audio.eq.LinkBandAnalysis.bandDb(
        spectrum.power, spectrum.sampleRate, shape.effectiveLoHz, shape.effectiveHiHz,
    )
