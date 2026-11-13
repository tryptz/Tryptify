package tf.monochrome.android.audio.eq

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject

/**
 * The Quick Settings tile for system-wide AutoEQ: the "System-wide" switch
 * under AutoEQ (the player's audio tools, Settings › Equalizer), reachable
 * from the notification shade over any app.
 *
 * System-wide is a part of the equalizer: it publishes the same correction to
 * the device's whole output, and means nothing with the equalizer off. So
 * turned on here it turns the equalizer on too, and turned off it leaves the
 * equalizer as it is, correcting the app's own music. The tile shows on only
 * while both are on, as the switch it stands for does.
 * [SystemAudioEqController] watches the setting and attaches the effect.
 */
@AndroidEntryPoint
class SystemEqQuickTileService : TileService() {

    @Inject lateinit var preferences: PreferencesManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    /** A tap in flight: a second one waits, so two taps are two changes, not a race. */
    private var change: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(
                preferences.eqEnabled,
                preferences.systemWideAutoEqEnabled,
                preferences.eqBandsJson,
                preferences.eqSelectedHeadphoneName,
            ) { eq, systemWide, bands, headphone -> SystemEqTileLook.of(eq, systemWide, bands, headphone) }
                .collect(::show)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val before = change
        change = scope.launch {
            before?.join()
            val on = preferences.eqEnabled.first() && preferences.systemWideAutoEqEnabled.first()
            if (on) {
                preferences.setSystemWideAutoEqEnabled(false)
            } else {
                preferences.setEqEnabled(true)
                preferences.setSystemWideAutoEqEnabled(true)
            }
        }
    }

    override fun onDestroy() {
        // A change in flight is two writes; it finishes, and only the watching stops.
        listening?.cancel()
        val pending = change
        if (pending == null || pending.isCompleted) scope.cancel()
        else pending.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    private fun show(look: SystemEqTileLook) {
        val tile = qsTile ?: return
        tile.state = if (look.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val detail = when (look.detail) {
            SystemEqTileLook.Detail.OFF -> getString(R.string.tile_system_eq_off)
            SystemEqTileLook.Detail.NO_CORRECTION -> getString(R.string.tile_system_eq_no_correction)
            SystemEqTileLook.Detail.ON -> look.headphone ?: getString(R.string.tile_system_eq_on)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = detail
        tile.contentDescription = getString(R.string.settings_system_wide_autoeq) + ", " + detail
        tile.updateTile()
    }
}

/**
 * What the system-wide AutoEQ tile shows, worked out apart from Android so it
 * has a plain test ([SystemEqQuickTileTest]).
 */
internal data class SystemEqTileLook(val on: Boolean, val detail: Detail, val headphone: String?) {
    enum class Detail {
        OFF,

        /** On, with no correction saved: the curve is flat, so the effect stays off and nothing changes. */
        NO_CORRECTION,

        /** On, correcting for [headphone] when one is chosen. */
        ON,
    }

    companion object {
        fun of(eqEnabled: Boolean, systemWide: Boolean, bandsJson: String?, headphone: String?): SystemEqTileLook = when {
            !eqEnabled || !systemWide -> SystemEqTileLook(false, Detail.OFF, null)
            !hasCorrection(bandsJson) -> SystemEqTileLook(true, Detail.NO_CORRECTION, null)
            else -> SystemEqTileLook(true, Detail.ON, headphone?.trim()?.takeIf { it.isNotEmpty() })
        }

        /** Whether any correction band is saved: null, blank and an empty list are none. */
        fun hasCorrection(bandsJson: String?): Boolean {
            val text = bandsJson?.trim().orEmpty()
            return text.isNotEmpty() && text.replace(" ", "") != "[]"
        }
    }
}
