// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.net.Uri
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
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

    // ── the start menu ──────────────────────────────────────────────────
    /** From the menu to the song list, to play or to pick a chart to edit. */
    data class OpenSongs(val mode: GlyphSongListMode) : GlyphEvent
    /** From the song list back to the menu. */
    data object BackToMenu : GlyphEvent
    data object OpenSettings : GlyphEvent
    data object CloseSettings : GlyphEvent
    /** The route has left the mode as [GlyphUiState.leaveRequested] asked. */
    data object LeaveHandled : GlyphEvent

    // ── settings ────────────────────────────────────────────────────────
    /** [code] (an Android key code) on [lane]'s [slot]; wherever else it was, it goes. */
    data class SetKey(val lane: GlyphLane, val slot: Int, val code: Int) : GlyphEvent
    data object ResetKeys : GlyphEvent
    data class SetAudioOffset(val ms: Int) : GlyphEvent
    /** Move the offset by how late the recent runs were hit. */
    data object ApplyRecentTiming : GlyphEvent
    /** Play the click track and start reading taps against it. */
    data object StartTapSync : GlyphEvent
    /** A tap on the sync pad, stamped with the pointer's own uptime. */
    data class SyncTap(val atUptimeMillis: Long) : GlyphEvent
    /** Stop the click track; a reading already made is kept to apply. */
    data object StopTapSync : GlyphEvent
    /** Set the offset to what the taps said, and close the reading. */
    data object ApplyTapSync : GlyphEvent

    // ── map editor ──────────────────────────────────────────────────────
    /** Open the selected song's [difficulty] in the editor, a new chart if it has none. */
    data class OpenEditor(val difficulty: StepManiaDifficulty) : GlyphEvent
    data object CloseEditor : GlyphEvent
    /** A tap on the grid at [beat] (unsnapped) in lane [lane]. */
    data class EditorTap(val beat: Float, val lane: Int) : GlyphEvent
    data class SetSnap(val snap: tf.monochrome.android.glyph.editor.GlyphSnap) : GlyphEvent
    data class SetTool(val tool: tf.monochrome.android.glyph.editor.GlyphEditTool) : GlyphEvent
    data object EditorUndo : GlyphEvent
    data object EditorSave : GlyphEvent
    /** Play or stop the song from [fromBeat], the grid scrolling with it. */
    data class EditorTogglePlay(val fromBeat: Float) : GlyphEvent
    /** Save, then play the chart from [fromBeat], coming back here after. */
    data class EditorTestPlay(val fromBeat: Float) : GlyphEvent
    data class SetEditorTempo(val bpm: Float, val beatZeroSeconds: Float) : GlyphEvent
    data object DismissEditorMessage : GlyphEvent

    // ── song selection ──────────────────────────────────────────────────
    data class Search(val query: String) : GlyphEvent
    /** Sort by [key]; the key already chosen turns round. */
    data class SortSongs(val key: tf.monochrome.android.glyph.data.GlyphSortKey) : GlyphEvent
    data object OpenLeaderboard : GlyphEvent
    data object CloseLeaderboard : GlyphEvent
    data class SelectSong(val trackId: String) : GlyphEvent

    // ── the music wheel ─────────────────────────────────────────────────
    /** The wheel settled on [key]; a song there is chosen after StepMania's sample delay. */
    data class WheelFocus(val key: String) : GlyphEvent
    /** Open the folder [name], or close it when it is the open one. */
    data class ToggleFolder(val name: String) : GlyphEvent
    /** Close the open folder, as StepMania's Up-Down does. */
    data class CloseFolder(val name: String) : GlyphEvent
    data class SetWheelSound(val on: Boolean) : GlyphEvent
    data class SelectDifficulty(val difficulty: StepManiaDifficulty) : GlyphEvent

    /** Generate a chart for the selected song from the file already on disk. */
    data object GenerateChart : GlyphEvent

    /** Generate from a file the player picked, for audio not in the library. */
    data class GenerateChartFrom(val uri: Uri) : GlyphEvent
    data object CancelGeneration : GlyphEvent
    data object DismissError : GlyphEvent

    // ── batches and modes ───────────────────────────────────────────────
    /** Offer to chart every song in the wheel folder [name] that has no chart. */
    data class ChartFolder(val name: String) : GlyphEvent
    /** Offer to chart every song that has no chart. */
    data object ChartEverything : GlyphEvent
    /** The offer's mode, changed in its dialog. */
    data class ChooseBatchMode(val mode: tf.monochrome.android.audio.stepmania.ChartMode) : GlyphEvent
    data object ConfirmBatch : GlyphEvent
    data object DismissBatch : GlyphEvent
    /** Fast (GPU) or Slow (CPU), for every chart from now on. */
    data class SetChartMode(val mode: tf.monochrome.android.audio.stepmania.ChartMode) : GlyphEvent

    // ── multiplayer ─────────────────────────────────────────────────────
    data object OpenMultiplayer : GlyphEvent
    data object CloseMultiplayer : GlyphEvent
    /** Open the socket again after a failure. */
    data object RoomReconnect : GlyphEvent
    data object RefreshRooms : GlyphEvent
    data class CreateRoom(val name: String) : GlyphEvent
    data class JoinRoom(val code: String) : GlyphEvent
    data object LeaveRoom : GlyphEvent
    /** The selected song and difficulty, as the room's chart (the host, from the song list). */
    data object PickForRoom : GlyphEvent
    data class SetRoomReady(val ready: Boolean) : GlyphEvent
    data object StartRoom : GlyphEvent
    data class KickPlayer(val id: String) : GlyphEvent
    data class RoomChat(val text: String) : GlyphEvent
    /** From a room's results back to the room. */
    data object BackToRoom : GlyphEvent
    data object DismissRoomError : GlyphEvent
    /** Host: the room's song, shared over the rooms server for the players without it. */
    data object ShareRoomSong : GlyphEvent
    /** A player without the song: the one the host shared, fetched and readied with. */
    data object FetchRoomSong : GlyphEvent

    // ── charting by the minute ──────────────────────────────────────────
    data object OpenCharting : GlyphEvent
    data object CloseCharting : GlyphEvent
    /** The screen is back in front (after a checkout in the browser): read the account again. */
    data object RefreshCharting : GlyphEvent
    /** The checkout link was handed to the browser. */
    data object ChartingUrlOpened : GlyphEvent
    /** Buy a pack of minutes, by its [tf.monochrome.android.audio.stepmania.ChartingPack.id]. */
    data class BuyMinutes(val packId: String) : GlyphEvent
    data object DismissChartingNotice : GlyphEvent

    // ── packs ───────────────────────────────────────────────────────────
    data object OpenPacks : GlyphEvent
    data object ClosePacks : GlyphEvent
    /** Download and import a pack ZIP from [url] (StepMania Online's download link). */
    data class ImportPackUrl(val url: String, val name: String) : GlyphEvent
    /** Import a pack ZIP the player picked. */
    data class ImportPackFile(val uri: Uri) : GlyphEvent
    data class ImportPackFolder(val tree: Uri) : GlyphEvent
    data object CancelPackImports : GlyphEvent
    data class RemovePack(val name: String) : GlyphEvent
    /** The folder the player's own pack is saved in; null answers "not now". */
    data class ChoosePackFolder(val tree: Uri?) : GlyphEvent
    data object ForgetPackFolder : GlyphEvent
    /** Write every chart made in the app into the own pack's folder again. */
    data object SaveOwnPack : GlyphEvent
    /** Write every chart made in the app as one pack ZIP at [destination]. */
    data class ZipOwnPack(val destination: Uri) : GlyphEvent
    data object DismissPacksMessage : GlyphEvent

    // ── player options ──────────────────────────────────────────────────
    /** From the song menu to StepMania's Player Options, before the song starts. */
    data object OpenOptions : GlyphEvent
    data object CloseOptions : GlyphEvent
    data class SetTurn(val turn: tf.monochrome.android.glyph.engine.GlyphTurn) : GlyphEvent
    data class SetAppearance(val appearance: tf.monochrome.android.glyph.engine.GlyphAppearance) : GlyphEvent
    data class SetReverse(val reverse: Boolean) : GlyphEvent
    data class SetDark(val dark: Boolean) : GlyphEvent
    data class SetMini(val mini: Float) : GlyphEvent
    data class SetFailType(val failType: tf.monochrome.android.glyph.engine.GlyphFailType) : GlyphEvent
    data class SetPlayerName(val name: String) : GlyphEvent
    data class SetPreviews(val on: Boolean) : GlyphEvent
    data class SetGraphics(val graphics: tf.monochrome.android.glyph.engine.GlyphGraphics) : GlyphEvent

    // ── transport ───────────────────────────────────────────────────────
    data object StartPlay : GlyphEvent
    data object StartTraining : GlyphEvent
    data object TogglePause : GlyphEvent
    data object Restart : GlyphEvent
    data object Quit : GlyphEvent

    /** The playfield's artwork; kept between sessions. */
    data class SetSkin(val skin: tf.monochrome.android.glyph.asset.GlyphSkin) : GlyphEvent

    // ── input ───────────────────────────────────────────────────────────
    /**
     * [atUptimeMillis] is when the finger moved (the pointer change's own
     * SystemClock.uptimeMillis stamp), so the press is judged at the touch
     * rather than at whenever input was dispatched. Zero means "now".
     */
    data class LanePressed(val lane: GlyphLane, val atUptimeMillis: Long = 0L) : GlyphEvent
    data class LaneReleased(val lane: GlyphLane, val atUptimeMillis: Long = 0L) : GlyphEvent

    // ── modifiers ───────────────────────────────────────────────────────
    data class SetSpeed(val speed: Float) : GlyphEvent
    data class SetPitchLinked(val linked: Boolean) : GlyphEvent
    data object ToggleMirror : GlyphEvent
    data object ToggleShuffle : GlyphEvent
    data object ToggleMetronome : GlyphEvent
    data class SetTimingWindowScale(val scale: Float) : GlyphEvent

    /** Switch scroll family, keeping a sensible value for the new one. */
    data class SetScrollFamily(val family: GlyphScrollFamily) : GlyphEvent

    /** Set the value within the current family: a multiplier, or a target BPM. */
    data class SetScrollValue(val value: Float) : GlyphEvent
    data class SetHitboxScale(val scale: Float) : GlyphEvent
    /** The share of the width the lanes take in landscape. */
    data class SetLandscapeFieldWidth(val fraction: Float) : GlyphEvent

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
