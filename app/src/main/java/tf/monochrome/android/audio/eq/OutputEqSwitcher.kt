package tf.monochrome.android.audio.eq

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.android.R
import tf.monochrome.android.audio.pipeline.OutputDeviceProbe
import tf.monochrome.android.audio.usb.LibusbUacDriver
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.EqRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Switches the AutoEQ preset when the output changes, to the one assigned to
 * that output (see [OutputEq]) — Poweramp's per-output presets.
 *
 * Only assigned outputs switch: one nobody assigned leaves the EQ alone. A
 * preset already active is left as it is, so edits made on top of it survive
 * a reconnect. Applying one switches the EQ on, since an assignment is a
 * request to hear it, and says so in a short notice.
 *
 * Runs for the life of the process (started from the Application), so the
 * switch happens with the app in the background too: putting headphones on
 * should not need the app open.
 */
@Singleton
class OutputEqSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
    probe: OutputDeviceProbe,
    libusbDriver: LibusbUacDriver,
    private val preferences: PreferencesManager,
    // A Provider: this is built on the app's startup path, and the preset
    // database is needed only when an output actually switches.
    private val eqRepository: Provider<EqRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var started = false

    /**
     * The output playing now, or null for one with no slot (HDMI, a call).
     *
     * A DAC claimed for exclusive output is that DAC whatever Android reports:
     * libusb takes it from Android's own driver, and Android stops listing it.
     */
    val current: StateFlow<OutputId?> = combine(probe.routed, libusbDriver.device) { routed, dac ->
        if (dac != null) {
            val name = runCatching { dac.productName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            OutputId(OutputSlot.USB, name)
        } else {
            routed?.let { OutputEq.idFor(it.kind, it.productName) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _applied = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** The id of each preset this applied: an open EQ screen reloads it. */
    val applied: SharedFlow<String> = _applied.asSharedFlow()

    /** Idempotent; main thread. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            current
                .filterNotNull()
                // Outputs flicker on the way to where they settle: a headphone
                // connecting, a DAC being claimed. Acting on each step would
                // switch twice and say so twice.
                .debounce(SETTLE_MS)
                .distinctUntilChanged()
                .collect { output ->
                    preferences.rememberEqOutput(output)
                    applyFor(output)
                }
        }
    }

    /**
     * Apply what the current output is assigned now: after the assign sheet's
     * OK, so a preset given to the headphones you are wearing takes effect
     * without unplugging them.
     */
    fun reapply() {
        val output = current.value ?: return
        scope.launch { applyFor(output) }
    }

    private suspend fun applyFor(output: OutputId) = mutex.withLock {
        val presetId = OutputEq.resolve(output, preferences.eqOutputAssignments.first()) ?: return@withLock
        val enabled = preferences.eqEnabled.first()
        if (presetId == OutputEq.EQ_OFF) {
            if (!enabled) return@withLock
            // As the EQ screen's own switch does: system-wide AutoEQ is a part
            // of the EQ, and left on it would go on correcting every app.
            preferences.setEqEnabled(false)
            preferences.setSystemWideAutoEqEnabled(false)
            notify(context.getString(R.string.eq_output_switched_off, label(context, output)))
            return@withLock
        }
        if (enabled && preferences.eqActivePresetId.first() == presetId) return@withLock
        // A preset deleted since it was assigned, or one whose bands no longer
        // decode: leave the EQ alone rather than flatten it.
        val preset = eqRepository.get().getPresetById(presetId)
        if (preset == null || preset.isCorrupted) return@withLock
        preferences.applyEqPreset(preset)
        _applied.tryEmit(preset.id)
        notify(context.getString(R.string.eq_output_switched, preset.name, label(context, output)))
    }

    private fun notify(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SETTLE_MS = 750L
    }
}

/** What an output is called on screen: its own name, or its slot's. */
fun label(context: Context, output: OutputId): String =
    output.name ?: context.getString(output.slot.labelRes)

@get:StringRes
val OutputSlot.labelRes: Int
    get() = when (this) {
        OutputSlot.SPEAKER -> R.string.eq_output_speaker
        OutputSlot.WIRED -> R.string.eq_output_wired
        OutputSlot.BLUETOOTH -> R.string.eq_output_bluetooth
        OutputSlot.USB -> R.string.eq_output_usb
    }
