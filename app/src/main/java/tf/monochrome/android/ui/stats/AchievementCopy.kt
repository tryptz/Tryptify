package tf.monochrome.android.ui.stats

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import java.util.Locale
import tf.monochrome.android.R
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.ui.settings.settingsTabLabelRes

/** Where an achievement's button goes. */
internal enum class AchievementTarget { DISCOVER, RADIO, SETTINGS_MISSING_TAB, SETTINGS_AUDIO, MIXER, LYRICS_STUDIO, EQUALIZER }

/**
 * The words around an achievement. The path is the app's own labels for the
 * buttons on the way, so it reads exactly as what is on screen.
 */
internal data class AchievementCopy(
    @StringRes val title: Int,
    /** May take the goal as %1$d. */
    @StringRes val goal: Int,
    @StringRes val what: Int,
    val path: List<Int>,
    val icon: ImageVector,
    /** The button's destination name, for "Open %1$s", and where it goes. */
    @StringRes val place: Int? = null,
    val target: AchievementTarget? = null,
)

internal fun copyFor(a: Achievement): AchievementCopy = when (a) {
    Achievement.EXPLORER -> AchievementCopy(
        R.string.stats_ach_explorer, R.string.stats_ach_explorer_goal, R.string.stats_ach_explorer_what,
        listOf(R.string.discover, R.string.discover_today_title), Icons.Default.Explore, R.string.discover, AchievementTarget.DISCOVER,
    )
    Achievement.EVERY_CORNER -> AchievementCopy(
        R.string.stats_ach_corner, R.string.stats_ach_corner_goal, R.string.stats_ach_corner_what,
        listOf(R.string.settings), Icons.Default.Settings, R.string.settings, AchievementTarget.SETTINGS_MISSING_TAB,
    )
    Achievement.PLUGIN_COLLECTOR -> AchievementCopy(
        R.string.stats_ach_plugins, R.string.stats_ach_plugins_goal, R.string.stats_ach_plugins_what,
        listOf(R.string.settings_now_playing, R.string.dock_mixer_fx, R.string.mixer_add_effect, R.string.mixer_add_plugin),
        Icons.Default.Tune, R.string.mixer_title, AchievementTarget.MIXER,
    )
    Achievement.GLOBETROTTER -> AchievementCopy(
        R.string.stats_ach_globe, R.string.stats_ach_globe_goal, R.string.stats_ach_globe_what,
        listOf(R.string.world_radio, R.string.stats_path_tap_city), Icons.Default.Public, R.string.world_radio, AchievementTarget.RADIO,
    )
    Achievement.LYRIC_STYLIST -> AchievementCopy(
        R.string.stats_ach_lyrics, R.string.stats_ach_lyrics_goal, R.string.stats_ach_lyrics_what,
        listOf(R.string.settings_now_playing, R.string.action_more, R.string.player_visuals_studio, R.string.mode_lyrics, R.string.visualizer_presets),
        Icons.Default.TextFields, R.string.player_visuals_studio, AchievementTarget.LYRICS_STUDIO,
    )
    Achievement.STREAK_30 -> AchievementCopy(
        R.string.stats_ach_streak, R.string.stats_ach_streak_goal, R.string.stats_ach_streak_what,
        emptyList(), Icons.Default.LocalFireDepartment,
    )
    Achievement.GOLDEN_EARS -> AchievementCopy(
        R.string.stats_ach_golden, R.string.stats_ach_golden_goal, R.string.stats_ach_golden_what,
        listOf(R.string.settings, R.string.settings_tab_audio), Icons.Default.Hearing, R.string.settings, AchievementTarget.SETTINGS_AUDIO,
    )
    Achievement.NIGHT_OWL -> AchievementCopy(
        R.string.stats_ach_night, R.string.stats_ach_night_goal, R.string.stats_ach_night_what,
        emptyList(), Icons.Default.Bedtime,
    )
    Achievement.SIGNAL_CHAIN -> AchievementCopy(
        R.string.stats_ach_chain, R.string.stats_ach_chain_goal, R.string.stats_ach_chain_what,
        listOf(R.string.settings_now_playing, R.string.dock_mixer_fx, R.string.mixer_add_effect),
        Icons.Default.Link, R.string.mixer_title, AchievementTarget.MIXER,
    )
    Achievement.ROOM_TUNED -> AchievementCopy(
        R.string.stats_ach_room, R.string.stats_ach_room_goal, R.string.stats_ach_room_what,
        listOf(R.string.settings_now_playing, R.string.status_sound, R.string.eq_select_headphone, R.string.stats_path_autoeq),
        Icons.Default.GraphicEq, R.string.settings_equalizer, AchievementTarget.EQUALIZER,
    )
}

/** The goal line, with the goal's number where the sentence has one. */
@Composable
internal fun goalText(state: AchievementState): String = stringResource(copyFor(state.achievement).goal, state.total)

/** The list under "Still to do" or "So far", and its heading; null when there is none. */
@Composable
internal fun todoFor(state: AchievementState, locale: Locale): Pair<String, List<String>>? = when (state.achievement) {
    Achievement.EVERY_CORNER -> state.remaining.takeIf { it.isNotEmpty() }?.let { ids ->
        stringResource(R.string.stats_still_to_do) to ids.map { id -> runCatching { settingsTabLabelRes(id) }.getOrNull()?.let { stringResource(it) } ?: id }
    }
    Achievement.PLUGIN_COLLECTOR -> state.remaining.takeIf { it.isNotEmpty() }?.let { names ->
        stringResource(R.string.stats_still_to_do) to names.map { n -> runCatching { SnapinType.valueOf(n).displayName }.getOrDefault(n) }
    }
    Achievement.GLOBETROTTER -> state.reached.takeIf { it.isNotEmpty() }?.let { codes ->
        stringResource(R.string.stats_heard_so_far) to codes.map { Locale("", it).getDisplayCountry(locale).ifBlank { it } }
    }
    Achievement.LYRIC_STYLIST -> state.reached.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.stats_tried_so_far) to it }
    Achievement.EXPLORER -> if (state.unlocked) null else stringResource(R.string.stats_good_places) to listOf(
        stringResource(R.string.deck_title), stringResource(R.string.discover_spotlight_title), stringResource(R.string.discover_galaxy_title),
    )
    else -> null
}
