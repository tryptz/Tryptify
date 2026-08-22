// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.net.Uri
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.training.GlyphCountIn

/**
 * Everything a Glyph screen can ask for.
 *
 * One sealed type rather than a bag of lambdas so the set of things the UI can
 * do is enumerable and the ViewModel is the only place any of them are
 * implemented. A composable that wants a new behaviour has to add a case here,
 * which is the point.
 */
sealed interface GlyphEvent {

    // ── song selection ──────────────────────────────────────────────────
    data class Search(val query: String) : GlyphEvent
    data class SelectSong(val trackId: String) : GlyphEvent
    data class SelectDifficulty(val difficulty: StepManiaDifficulty) : GlyphEvent

    /** Generate a chart for the selected song from the file already on disk. */
    data object GenerateChart : GlyphEvent

    /** Generate from a file the player picked, for audio not in the library. */
    data class GenerateChartFrom(val uri: Uri, val displayName: String) : GlyphEvent
    data object CancelGeneration : GlyphEvent
    data object DismissError : GlyphEvent

    // ── transport ───────────────────────────────────────────────────────
    data object StartPlay : GlyphEvent
    data object StartTraining : GlyphEvent
    data object TogglePause : GlyphEvent
    data object Restart : GlyphEvent
    data object Quit : GlyphEvent

    // ── input ───────────────────────────────────────────────────────────
    data class LanePressed(val lane: GlyphLane) : GlyphEvent
    data class LaneReleased(val lane: GlyphLane) : GlyphEvent

    // ── modifiers ───────────────────────────────────────────────────────
    data class SetSpeed(val speed: Float) : GlyphEvent
    data class SetPitchLinked(val linked: Boolean) : GlyphEvent
    data object ToggleMirror : GlyphEvent
    data object ToggleShuffle : GlyphEvent
    data object ToggleMetronome : GlyphEvent
    data class SetTimingWindowScale(val scale: Float) : GlyphEvent
    data class SetHitboxScale(val scale: Float) : GlyphEvent

    // ── training ────────────────────────────────────────────────────────
    data class SetLoop(val startSeconds: Float, val endSeconds: Float) : GlyphEvent
    data object ClearLoop : GlyphEvent
    data class SetCountIn(val countIn: GlyphCountIn) : GlyphEvent
    data class SetGhostEnabled(val enabled: Boolean) : GlyphEvent
    data class StartGauntlet(val id: String) : GlyphEvent

    // ── results ─────────────────────────────────────────────────────────
    data class SelectSection(val index: Int) : GlyphEvent

    /** Open the chosen weak section as a Training Ground loop, in one step. */
    data class PractiseSection(val index: Int) : GlyphEvent
    data object BackToHome : GlyphEvent
}
