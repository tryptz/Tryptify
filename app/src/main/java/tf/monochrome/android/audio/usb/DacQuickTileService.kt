package tf.monochrome.android.audio.usb

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
 * The Quick Settings tile for the exclusive USB DAC (issue #157).
 *
 * In exclusive mode the app keeps the DAC for as long as the switch is on,
 * through every pause, so no other app can play on it; letting it go meant
 * pausing, opening the app and finding Settings › Audio. This tile is that
 * switch, reachable from the notification shade over whatever app is open:
 *
 *  - **on, tapped**: the music pauses and the DAC is released
 *    ([UsbExclusiveController.releaseForOtherApps]);
 *  - **off, tapped**: the DAC is claimed again
 *    ([UsbExclusiveController.takeBack]); the music stays paused.
 *
 * It is the same setting as Settings › Audio › Exclusive USB DAC, so the two
 * always agree, and the tile shows what the switch would: on or off, and
 * under it the DAC held, or that none is plugged in.
 */
@AndroidEntryPoint
class DacQuickTileService : TileService() {

    @Inject lateinit var controller: UsbExclusiveController
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
                preferences.usbExclusiveBitPerfectEnabled,
                controller.holdsDac,
                controller.dacInfo,
            ) { enabled, held, dac -> TileLook.of(enabled, held, dac?.displayName) }
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
            if (preferences.usbExclusiveBitPerfectEnabled.first()) controller.releaseForOtherApps()
            else controller.takeBack()
        }
    }

    override fun onDestroy() {
        // A release in flight finishes: its pause is already out, and the
        // switch is one write away. Only the tile's own watching stops.
        listening?.cancel()
        val pending = change
        if (pending == null || pending.isCompleted) scope.cancel()
        else pending.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    private fun show(look: TileLook) {
        val tile = qsTile ?: return
        tile.state = if (look.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val detail = when (look.detail) {
            TileLook.Detail.RELEASED -> getString(R.string.tile_dac_released)
            TileLook.Detail.NO_DAC -> getString(R.string.tile_dac_no_device)
            TileLook.Detail.HELD -> look.dacName ?: getString(R.string.tile_dac_held)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = detail
        // What a screen reader says for the tile: its name, then its state.
        tile.contentDescription = getString(R.string.tile_exclusive_dac) + ", " + detail
        tile.updateTile()
    }
}

/**
 * What the tile shows, worked out apart from Android so it has a plain test
 * ([DacQuickTileTest]): [on] is the setting, and [detail] what stands under
 * the name.
 */
internal data class TileLook(val on: Boolean, val detail: Detail, val dacName: String?) {
    enum class Detail {
        /** Off: other apps may have the DAC. */
        RELEASED,

        /** On, with no DAC to hold: nothing plugged in, or its permission not given yet. */
        NO_DAC,

        /** On, and holding the DAC [dacName] names. */
        HELD,
    }

    companion object {
        /**
         * [enabled] is the setting, [held] whether the driver has a DAC open,
         * [dacName] the open DAC's name. The setting decides on and off, and
         * decides alone: a DAC still closing a moment after the tap is
         * already released as far as the listener is concerned.
         */
        fun of(enabled: Boolean, held: Boolean, dacName: String?): TileLook = when {
            !enabled -> TileLook(on = false, Detail.RELEASED, null)
            !held -> TileLook(on = true, Detail.NO_DAC, null)
            else -> TileLook(on = true, Detail.HELD, dacName?.takeIf { it.isNotBlank() })
        }
    }
}
