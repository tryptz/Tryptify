package tf.monochrome.android.ui.eq.dac

import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.usb.DacSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.eq.AutoPreamp
import tf.monochrome.android.audio.eq.dac.DacEqFit
import tf.monochrome.android.audio.eq.dac.DacEqMapping
import tf.monochrome.android.audio.usb.daceq.DacEqDevice
import tf.monochrome.android.audio.usb.daceq.DacLink
import tf.monochrome.android.audio.usb.daceq.DacNotConfirmed
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint

/** What the page is doing with the DAC right now. */
enum class DacEqBusy { IDLE, READING, SENDING, RESTARTING }

/** What a read found on the DAC, in the app's own bands. */
data class DacEqHeld(
    val bands: List<EqBand>,
    val preampDb: Float?,
    val slot: Int,
    /** Low, high, band and all pass filters, which the app's EQ has no band for. */
    val passFilters: Int,
    /** The DAC holds something that cannot be shown as bands at all. */
    val notExact: Boolean,
)

/** How the last send ended. */
sealed interface DacEqOutcome {
    data object Verified : DacEqOutcome
    data class Mismatch(val what: List<String>) : DacEqOutcome
    data class NotConfirmed(val why: DacNotConfirmed) : DacEqOutcome
    data class Failed(val message: String) : DacEqOutcome
}

/**
 * @property bands the EQ being edited, always inside the connected DAC's
 *   limits: every way in (a preset, an import, a drag) goes through
 *   [DacEqFit].
 * @property fit how the EQ last loaded was made to fit; null once it has
 *   been edited by hand or came from the DAC itself.
 * @property sendPreampDb the preamp a send would write: the automatic one,
 *   the slider's on the DAC's own grid, or 0 for a DAC that takes none.
 * @property issues why Contour would refuse this EQ; empty = it can be sent.
 * @property onDac whether the DAC already holds exactly this EQ.
 */
data class DacEqUiState(
    val bands: List<EqBand> = emptyList(),
    val selectedBandId: Int? = null,
    val sourceName: String? = null,
    val fit: DacEqFit.Result? = null,
    val preampAuto: Boolean = true,
    val preampManualDb: Float = 0f,
    val sendPreampDb: Float = 0f,
    val peakBoostDb: Float = 0f,
    val issues: List<String> = emptyList(),
    val held: DacEqHeld? = null,
    val onDac: Boolean = false,
    val slot: Int? = null,
    val busy: DacEqBusy = DacEqBusy.IDLE,
    val progress: Float = 0f,
    val outcome: DacEqOutcome? = null,
    val readError: String? = null,
)

/**
 * The DAC EQ page's logic: what is plugged in, the EQ being edited, and the
 * read and the send. Kept apart from the view model so that a test can run
 * it against a simulated DAC ([DacEqDevice]).
 */
class DacEqSession(
    private val device: DacEqDevice,
    private val scope: CoroutineScope,
    private val fitOn: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {

    val link: StateFlow<DacLink> = device.link

    private val _state = MutableStateFlow(DacEqUiState())
    val state: StateFlow<DacEqUiState> = _state.asStateFlow()

    private var snapshot: DacSnapshot? = null
    private var readFor: String? = null

    val target: DeviceTarget?
        get() = when (val l = link.value) {
            is DacLink.Ready -> l.dac.target
            is DacLink.NeedsPermission -> l.dac.target
            else -> null
        }

    init {
        scope.launch {
            link.collect { l ->
                val ready = l as? DacLink.Ready
                // A DAC that leaves the bus to save its EQ is expected back: what
                // the page knows of it is kept until the send has its verdict.
                val sending = _state.value.busy == DacEqBusy.SENDING || _state.value.busy == DacEqBusy.RESTARTING
                if (l !is DacLink.Ready && l !is DacLink.NeedsPermission && !sending) forgetDac()
                // A DAC that has just become reachable is read once, by itself:
                // a read changes nothing on it, and the page is about what it holds.
                if (ready != null && ready.dac.key != readFor && _state.value.busy == DacEqBusy.IDLE) {
                    readFor = ready.dac.key
                    change { it.copy(slot = ready.dac.target.destinationSlot) }
                    // An EQ loaded for another DAC is made to fit this one.
                    val s = _state.value
                    if (s.bands.isNotEmpty()) load(s.sourceName, s.bands, emptyList(), emptyList())
                    read()
                }
                // Another DAC, or none: what Contour makes of the EQ changes with it.
                recompute()
            }
        }
    }

    fun onStart() = device.watch()
    fun onStop() = device.unwatch()
    fun requestPermission() = device.requestPermission()

    private fun forgetDac() {
        snapshot = null
        readFor = null
        change { it.copy(held = null, onDac = false, readError = null, outcome = null, slot = null) }
    }

    // ---- reading ----

    fun read() {
        if (_state.value.busy != DacEqBusy.IDLE) return
        val dac = target ?: return
        change { it.copy(busy = DacEqBusy.READING, readError = null) }
        scope.launch {
            try {
                val read = device.read()
                took(read, dac)
                // First look at a DAC with nothing loaded yet: show what it holds.
                if (_state.value.bands.isEmpty()) loadFromDac()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change { it.copy(readError = e.message ?: e.javaClass.simpleName) }
            } finally {
                change { it.copy(busy = DacEqBusy.IDLE) }
            }
        }
    }

    private fun took(read: DacSnapshot, dac: DeviceTarget) {
        snapshot = read
        val held = when (val eq = read.importExact()) {
            is DacImport.Ready -> DacEqMapping.fromDac(eq.eq, dac).let {
                DacEqHeld(it.bands, it.preampDb, read.slot, it.passFilters, notExact = false)
            }
            is DacImport.Rejected -> DacEqHeld(emptyList(), read.shownPreamp?.toFloat(), read.slot, 0, notExact = true)
        }
        change { it.copy(held = held) }
    }

    // ---- where the EQ comes from ----

    fun loadFromDac() {
        val held = _state.value.held ?: return
        val preamp = held.preampDb
        change {
            it.copy(
                bands = held.bands.filter { b -> b.enabled }.mapIndexed { i, b -> b.copy(id = i) },
                selectedBandId = null,
                sourceName = null,
                fit = null,
                preampAuto = preamp == null,
                preampManualDb = preamp ?: 0f,
                outcome = null,
            )
        }
    }

    fun loadImported(name: String, bands: List<EqBand>) = load(name.ifBlank { null }, bands, emptyList(), emptyList())

    /**
     * [bands] made to fit the connected DAC ([DacEqFit]) and put in the
     * editor. [measurement] and [aim] are a preset's headphone curve and the
     * curve it was corrected toward, when it kept them.
     */
    fun load(name: String?, bands: List<EqBand>, measurement: List<FrequencyPoint>, aim: List<FrequencyPoint>) {
        val dac = target ?: return
        scope.launch {
            val fitted = withContext(fitOn) { DacEqFit.fit(bands, dac, measurement, aim) }
            change {
                it.copy(
                    bands = fitted.bands,
                    selectedBandId = null,
                    sourceName = name,
                    fit = fitted,
                    // A loaded EQ starts on the automatic preamp: its bands may
                    // have changed to fit, and a preamp that was right for the
                    // old ones would be a guess for these.
                    preampAuto = true,
                    outcome = null,
                )
            }
        }
    }

    // ---- editing ----

    fun selectBand(id: Int?) = change { it.copy(selectedBandId = if (it.selectedBandId == id) null else id) }

    fun updateBand(band: EqBand) = edit { bands -> bands.map { if (it.id == band.id) held(band) else it } }

    fun dragBand(id: Int, freq: Float, gain: Float) =
        edit { bands -> bands.map { if (it.id == id) held(it.copy(freq = freq, gain = gain)) else it } }

    fun addBand() {
        val dac = target ?: return
        if (_state.value.bands.size >= dac.caps.bands) return
        val id = (_state.value.bands.maxOfOrNull { it.id } ?: -1) + 1
        edit { it + held(EqBand(id = id, type = FilterType.PEAKING, freq = 1000f, gain = 0f, q = 1f)) }
        change { it.copy(selectedBandId = id) }
    }

    fun removeBand(id: Int) {
        edit { bands -> bands.filter { it.id != id } }
        change { if (it.selectedBandId == id) it.copy(selectedBandId = null) else it }
    }

    /** [band] inside the connected DAC's limits, with a kind of filter it has. */
    private fun held(band: EqBand): EqBand {
        val caps = target?.caps ?: return band
        val type = band.type.takeIf { DacEqMapping.toDac(it) in caps.types } ?: FilterType.PEAKING
        return DacEqFit.limit(band.copy(type = type), caps)
    }

    private fun edit(how: (List<EqBand>) -> List<EqBand>) =
        change { it.copy(bands = how(it.bands), fit = null, outcome = null) }

    fun setPreampAuto(auto: Boolean) {
        change { it.copy(preampAuto = auto, preampManualDb = if (auto) it.preampManualDb else it.sendPreampDb, outcome = null) }
    }

    fun setPreamp(db: Float) {
        change { it.copy(preampAuto = false, preampManualDb = db, outcome = null) }
    }

    fun setSlot(slot: Int) = change { it.copy(slot = slot, outcome = null) }

    // ---- what a send would be ----

    private fun profile(state: DacEqUiState, dac: DeviceTarget): Profile {
        val preamp: Double? = when {
            !DacEqMapping.writesPreamp(dac) -> 0.0
            state.preampAuto -> null
            else -> dac.heldPreamp(state.preampManualDb.toDouble().coerceIn(dac.preampMin, dac.preampMax))
        }
        return DacEqMapping.profile(state.sourceName ?: DacEqMapping.FALLBACK_NAME, state.bands, preamp, dac, now())
    }

    /**
     * Every change to the page's state goes through here, so that what is
     * worked out from it (the preamp a send would write, Contour's verdict,
     * whether the DAC already holds it) is never a step behind it. A test
     * once read the page between the two and saw the last EQ's preamp
     * beside this one's bands.
     */
    private inline fun change(how: (DacEqUiState) -> DacEqUiState) = _state.update { derived(how(it)) }

    private fun recompute() = change { it }

    /** [s] with what Contour makes of its EQ, for the DAC connected now. */
    private fun derived(s: DacEqUiState): DacEqUiState {
        val dac = target
        val peak = AutoPreamp.peakBoostDb(s.bands, DacEqMapping.DISPLAY_SAMPLE_RATE)
        if (dac == null) return s.copy(peakBoostDb = peak, issues = emptyList(), onDac = false)
        val profile = profile(s, dac)
        val issues = runCatching { dac.issues(profile) }.getOrElse { listOf(it.message ?: "Unrepresentable EQ") }
        val preamp = when {
            !DacEqMapping.writesPreamp(dac) -> 0f
            else -> profile.preampDb?.toFloat()
                ?: runCatching { profile.effectivePreampDb(dac.caps.shelfForm).toFloat() }.getOrDefault(-peak)
        }
        val same = issues.isEmpty() && s.held != null && runCatching { snapshot?.matches(profile) == true }.getOrDefault(false)
        return s.copy(peakBoostDb = peak, issues = issues, sendPreampDb = preamp, onDac = same)
    }

    // ---- sending ----

    /** The one thing here that changes a DAC. Called from the held button only. */
    fun send() {
        val s = _state.value
        val dac = target ?: return
        if (s.busy != DacEqBusy.IDLE || s.issues.isNotEmpty() || link.value !is DacLink.Ready) return
        val profile = profile(s, dac)
        change { it.copy(busy = DacEqBusy.SENDING, progress = 0f, outcome = null) }
        scope.launch {
            val outcome = try {
                val result = device.send(
                    profile = profile,
                    slot = s.slot,
                    onProgress = { p -> _state.update { it.copy(progress = p.coerceIn(0f, 1f)) } },
                    onRestarting = { _state.update { it.copy(busy = DacEqBusy.RESTARTING) } },
                )
                val back = result.readBack
                // What the DAC holds now: the read-back, or unknown until it is read again.
                if (back != null) took(back, dac) else { snapshot = null; change { it.copy(held = null) } }
                when {
                    result.verified -> DacEqOutcome.Verified
                    result.notConfirmed != null -> DacEqOutcome.NotConfirmed(result.notConfirmed)
                    else -> DacEqOutcome.Mismatch(result.mismatches)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snapshot = null
                change { it.copy(held = null) }
                DacEqOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            // The DAC may have come back as a new USB device: it is the one just
            // read, so it is not read again for being "new".
            (link.value as? DacLink.Ready)?.let { readFor = it.dac.key }
            change { it.copy(busy = DacEqBusy.IDLE, progress = 0f, outcome = outcome) }
            // Pulled out for good while its save was being waited for.
            if (target == null) forgetDac()
        }
    }
}
