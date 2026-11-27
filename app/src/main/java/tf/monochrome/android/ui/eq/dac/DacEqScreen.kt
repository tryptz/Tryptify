package tf.monochrome.android.ui.eq.dac

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.DeviceTarget
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.dac.DacEqCatalog
import tf.monochrome.android.audio.eq.dac.DacEqFit
import tf.monochrome.android.audio.eq.dac.DacEqMapping
import tf.monochrome.android.audio.eq.dac.DacEqUntested
import tf.monochrome.android.audio.usb.daceq.DacLink
import tf.monochrome.android.audio.usb.daceq.DacNotConfirmed
import tf.monochrome.android.audio.usb.daceq.caps
import tf.monochrome.android.audio.usb.daceq.dacName
import tf.monochrome.android.audio.usb.daceq.untestedDac
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.eq.BandChip
import tf.monochrome.android.ui.eq.FrequencyResponseGraph
import tf.monochrome.android.ui.eq.ImportEqProfileSheet
import tf.monochrome.android.ui.eq.SectionLabel
import tf.monochrome.android.ui.eq.TabChip
import tf.monochrome.android.ui.eq.ValueSlider
import tf.monochrome.android.ui.eq.formatFreq
import tf.monochrome.android.ui.eq.logFreqToSlider
import tf.monochrome.android.ui.eq.sliderToLogFreq
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.popBackStackSafe
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DAC EQ: an EQ of the app's, written into a USB DAC's own memory.
 *
 * The page is the Parametric EQ editor's layout (the same graph, band chips
 * and sliders) around three things of its own: the card of the DAC plugged
 * in, where the EQ comes from, and the held button that sends it. Nothing is
 * written to a DAC by anything on this page but that button.
 */
@Composable
fun DacEqScreen(
    navController: NavController,
    viewModel: DacEqViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    DacEqContent(
        session = viewModel.session,
        sources = sources,
        onChoosePreset = viewModel::loadPreset,
        onBack = { navController.popBackStackSafe() },
    )
}

/** The page itself, on a [DacEqSession]: the app's, or a test's with a simulated DAC. */
@Composable
fun DacEqContent(
    session: DacEqSession,
    sources: List<DacEqSource>,
    onChoosePreset: (DacEqSource) -> Unit,
    onBack: () -> Unit,
) {
    val link by session.link.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()

    // The USB bus is followed only while the page is on screen.
    LifecycleStartEffect(Unit) {
        session.onStart()
        onStopOrDispose { session.onStop() }
    }

    var choosingPreset by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }

    val dac: DeviceTarget? = when (val l = link) {
        is DacLink.Ready -> l.dac.target
        is DacLink.NeedsPermission -> l.dac.target
        else -> null
    }
    val ready = link is DacLink.Ready
    val idle = state.busy == DacEqBusy.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomChromeInset.current)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                stringResource(R.string.dac_eq_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (ready) {
                IconButton(onClick = { session.read() }, enabled = idle) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.dac_eq_read),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DeviceCard(link = link, onAllow = session::requestPermission)
            link.untestedDac?.let { untested ->
                Spacer(Modifier.height(8.dp))
                Warning(stringResource(R.string.dac_eq_untested, untested.driveAs.caps.name))
                if (untested.resetsDacSettings) {
                    Spacer(Modifier.height(4.dp))
                    Warning(stringResource(R.string.dac_eq_untested_resets))
                }
            }

            if (dac == null) {
                Spacer(Modifier.height(16.dp))
                Note(stringResource(R.string.dac_eq_intro))
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.dac_eq_supported))
                Spacer(Modifier.height(6.dp))
                val bands = DacEqCatalog.all.map { it.caps.name to it.caps.bands }
                val untested = DacEqUntested.all.map { "${it.name} (${stringResource(R.string.dac_eq_untested_tag)})" to it.caps().bands }
                (bands + untested).forEach { (name, count) ->
                    Text(
                        "$name · ${stringResource(R.string.dac_eq_on_dac_bands, count)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Credit()
                Spacer(Modifier.height(32.dp))
                return@Column
            }

            if (ready) {
                Spacer(Modifier.height(14.dp))
                HeldOnDac(state = state, dac = dac, canLoad = idle, onLoad = session::loadFromDac, onRead = session::read)
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel(stringResource(R.string.dac_eq_start_from))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip(label = stringResource(R.string.dac_eq_from_presets), isSelected = false, onClick = { choosingPreset = true })
                TabChip(label = stringResource(R.string.dac_eq_from_import), isSelected = false, onClick = { importing = true })
            }
        }

        if (dac != null && state.bands.isEmpty()) {
            Note(
                stringResource(R.string.dac_eq_empty),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
            )
        }

        if (dac != null && state.bands.isNotEmpty()) {
            val caps = link.caps ?: dac.caps
            val maxGain = max(-caps.gainMinDb, caps.gainMaxDb).toFloat()

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))
                SectionLabel(state.sourceName?.let { "${stringResource(R.string.dac_eq_to_send)} · $it" } ?: stringResource(R.string.dac_eq_to_send))
            }

            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                FrequencyResponseGraph(
                    originalCurve = emptyList(),
                    targetCurve = emptyList(),
                    eqBands = state.bands,
                    preamp = state.sendPreampDb,
                    sampleRate = DacEqMapping.DISPLAY_SAMPLE_RATE,
                    centerOnZero = true,
                    showLegend = false,
                    maxAbsDragGain = maxGain,
                    onBandDragged = { id, f, g -> session.dragBand(id, f, g) },
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                state.fit?.let { FitLine(it, caps.bands) }

                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.eq_bands_caps))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.bands.sortedBy { it.freq }.forEach { band ->
                        BandChip(
                            label = formatFreq(band.freq),
                            gainDb = band.gain,
                            isSelected = band.id == state.selectedBandId,
                            onClick = { session.selectBand(band.id) },
                            onLongPress = { session.removeBand(band.id) }
                        )
                    }
                    if (state.bands.size < caps.bands) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                                .bounceClick(onClick = { session.addBand() }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.eq_add_band),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                state.bands.find { it.id == state.selectedBandId }?.let { band ->
                    Spacer(Modifier.height(12.dp))
                    BandEditor(
                        band = band,
                        number = state.bands.sortedBy { it.freq }.indexOf(band) + 1,
                        caps = caps,
                        onChange = session::updateBand,
                        onRemove = { session.removeBand(band.id) },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Preamp(state = state, dac = dac, onAuto = session::setPreampAuto, onChange = session::setPreamp)

                val slots = dac.fiio?.userSlots.orEmpty().sorted()
                if (slots.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel(stringResource(R.string.dac_eq_slot))
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        slots.forEachIndexed { i, slot ->
                            TabChip(
                                label = dac.fiio?.slotLabels?.get(slot) ?: "USER ${i + 1}",
                                isSelected = slot == state.slot,
                                onClick = { session.setSlot(slot) }
                            )
                        }
                    }
                }

                if (dac.emulatesHighShelf && state.bands.any { it.type == FilterType.HIGHSHELF }) {
                    Spacer(Modifier.height(12.dp))
                    Note(stringResource(R.string.dac_eq_high_shelf_note))
                }

                Spacer(Modifier.height(18.dp))
                Send(state = state, ready = ready, onSend = session::send)
                Spacer(Modifier.height(14.dp))
                Credit()
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (choosingPreset) {
        PresetSheet(
            sources = sources,
            onDismiss = { choosingPreset = false },
            onChoose = { choosingPreset = false; onChoosePreset(it) },
        )
    }

    if (importing && dac != null) {
        ImportEqProfileSheet(
            maxBandGainDb = (link.caps ?: dac.caps).let { max(-it.gainMinDb, it.gainMaxDb).toFloat() },
            onDismiss = { importing = false },
            onImport = { left, _, name ->
                importing = false
                left?.let { session.loadImported(name, it.bands) }
            },
        )
    }
}

// ---- the DAC ----

@Composable
private fun DeviceCard(link: DacLink, onAllow: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Usb,
            contentDescription = null,
            tint = if (link is DacLink.Ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val (title, detail) = when (link) {
                DacLink.None -> stringResource(R.string.dac_eq_no_dac) to stringResource(R.string.dac_eq_no_dac_hint)
                // Its USB id goes with it, so its owner can say exactly which device this is.
                is DacLink.Unknown -> stringResource(R.string.dac_eq_unknown_dac, link.name) to
                    listOfNotNull(link.usbId.takeIf { it.isNotEmpty() }?.let { "USB $it" }, stringResource(R.string.dac_eq_unknown_dac_hint)).joinToString(" · ")
                is DacLink.NeedsPermission -> link.dacName.orEmpty() to stringResource(R.string.dac_eq_needs_permission)
                is DacLink.Ready -> link.dacName.orEmpty() to limits(link.caps ?: link.dac.target.caps)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (link is DacLink.NeedsPermission) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.dac_eq_allow)) }
        }
    }
}

@Composable
private fun limits(caps: DeviceCapabilities): String =
    stringResource(R.string.dac_eq_limits, caps.bands, db(caps.gainMinDb.toFloat()), db(caps.gainMaxDb.toFloat()))

/** What the last read found, and the way to bring it into the editor. */
@Composable
private fun HeldOnDac(state: DacEqUiState, dac: DeviceTarget, canLoad: Boolean, onLoad: () -> Unit, onRead: () -> Unit) {
    SectionLabel(stringResource(R.string.dac_eq_on_dac))
    Spacer(Modifier.height(4.dp))
    val held = state.held
    when {
        state.busy == DacEqBusy.READING -> Note(stringResource(R.string.dac_eq_reading))
        state.readError != null -> Problem(stringResource(R.string.dac_eq_failed), listOf(state.readError))
        // Not read yet, or a send that was not proven: only a read says what it holds.
        held == null -> OutlinedButton(onClick = onRead, enabled = canLoad) { Text(stringResource(R.string.dac_eq_read)) }
        held.notExact -> Note(stringResource(R.string.dac_eq_not_exact))
        else -> {
            val on = held.bands.count { it.enabled && it.gain != 0f }
            val summary = when {
                on == 0 -> stringResource(R.string.dac_eq_on_dac_flat)
                held.preampDb != null && DacEqMapping.writesPreamp(dac) ->
                    stringResource(R.string.dac_eq_on_dac_summary, on, db(held.preampDb))
                else -> stringResource(R.string.dac_eq_on_dac_bands, on)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (on > 0 && !state.onDac) {
                    OutlinedButton(onClick = onLoad, enabled = canLoad) {
                        Text(stringResource(R.string.dac_eq_load_from_dac))
                    }
                }
            }
            if (held.passFilters > 0) {
                Spacer(Modifier.height(4.dp))
                Note(stringResource(R.string.dac_eq_pass_filters, held.passFilters))
            }
        }
    }
}

// ---- the EQ ----

@Composable
private fun FitLine(fit: DacEqFit.Result, dacBands: Int) {
    val text = when {
        fit.asItIs -> stringResource(R.string.dac_eq_fit_as_is)
        fit.fromMeasurement -> stringResource(R.string.dac_eq_fit_autoeq, dacBands)
        fit.refit -> stringResource(R.string.dac_eq_fit_refit, fit.sourceBands, fit.bands.size, "%.1f".format(fit.maxErrorDb))
        else -> stringResource(R.string.dac_eq_fit_limited, fit.limited)
    }
    Note(text)
    if (fit.refit && fit.limited > 0) Note(stringResource(R.string.dac_eq_fit_limited, fit.limited))
}

@Composable
private fun BandEditor(band: EqBand, number: Int, caps: DeviceCapabilities, onChange: (EqBand) -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionLabel(stringResource(R.string.eq_band_n_caps, number))
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.eq_remove_band),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
    }

    // Only the kinds of filter this DAC has.
    val kinds = FilterType.entries.filter { DacEqMapping.toDac(it) in caps.types }
    if (kinds.size > 1) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { type ->
                val selected = band.type == type
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .then(
                            if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        )
                        .bounceClick(onClick = { onChange(band.copy(type = type)) })
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (type) {
                            FilterType.PEAKING -> "PEAK"
                            FilterType.LOWSHELF -> "LOW-S"
                            FilterType.HIGHSHELF -> "HIGH-S"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 1.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    ValueSlider(
        label = stringResource(R.string.eq_frequency),
        value = logFreqToSlider(band.freq),
        valueRange = 0f..1f,
        display = formatFreq(band.freq),
        onChange = { onChange(band.copy(freq = sliderToLogFreq(it))) }
    )
    val gains = caps.gainRange(DacEqMapping.toDac(band.type))
    ValueSlider(
        label = stringResource(R.string.eq_gain),
        value = band.gain,
        valueRange = gains.start.toFloat()..gains.endInclusive.toFloat(),
        display = "%+.1f dB".format(band.gain),
        onChange = { onChange(band.copy(gain = (it * 10f).roundToInt() / 10f)) }
    )
    // The slider runs over the app's usual Q; the DAC's own range (a slope,
    // on some) is kept by the view model, which moves a value back inside.
    ValueSlider(
        label = "Q",
        value = band.q,
        valueRange = 0.1f..10f,
        display = "%.2f".format(band.q),
        onChange = { onChange(band.copy(q = it)) }
    )
}

@Composable
private fun Preamp(state: DacEqUiState, dac: DeviceTarget, onAuto: (Boolean) -> Unit, onChange: (Float) -> Unit) {
    SectionLabel(stringResource(R.string.eq_preamp_caps))
    if (!DacEqMapping.writesPreamp(dac)) {
        if (state.peakBoostDb > 0.05f) {
            Spacer(Modifier.height(4.dp))
            Warning(stringResource(R.string.dac_eq_no_preamp, "%.1f".format(state.peakBoostDb)))
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.dac_eq_preamp_auto),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${db(state.sendPreampDb)} dB",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Switch(checked = state.preampAuto, onCheckedChange = onAuto)
    }
    if (!state.preampAuto) {
        val step = dac.preampStep.toFloat()
        ValueSlider(
            label = "",
            value = state.preampManualDb,
            valueRange = dac.preampMin.toFloat()..dac.preampMax.toFloat(),
            display = "${db(state.sendPreampDb)} dB",
            onChange = { onChange((it / step).roundToInt() * step) }
        )
        if (state.sendPreampDb > -state.peakBoostDb + 0.05f && state.peakBoostDb > 0.05f) {
            Warning(stringResource(R.string.dac_eq_clip_warning, "%.1f".format(state.peakBoostDb)))
        }
    }
}

// ---- sending ----

@Composable
private fun Send(state: DacEqUiState, ready: Boolean, onSend: () -> Unit) {
    if (state.issues.isNotEmpty()) {
        Problem(stringResource(R.string.dac_eq_issues), state.issues)
        Spacer(Modifier.height(10.dp))
    }
    when (state.busy) {
        DacEqBusy.SENDING -> {
            Note(stringResource(R.string.dac_eq_sending))
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            return
        }
        DacEqBusy.RESTARTING -> {
            Note(stringResource(R.string.dac_eq_restarting))
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return
        }
        else -> Unit
    }
    when (val outcome = state.outcome) {
        null -> Unit
        DacEqOutcome.Verified -> Good(stringResource(R.string.dac_eq_verified))
        is DacEqOutcome.Mismatch -> Problem(stringResource(R.string.dac_eq_mismatch), outcome.what)
        is DacEqOutcome.Failed -> Problem(stringResource(R.string.dac_eq_failed), listOf(outcome.message))
        is DacEqOutcome.NotConfirmed -> Warning(
            stringResource(
                when (outcome.why) {
                    DacNotConfirmed.TIMEOUT -> R.string.dac_eq_not_confirmed_timeout
                    DacNotConfirmed.PERMISSION -> R.string.dac_eq_not_confirmed_permission
                    DacNotConfirmed.SILENT -> R.string.dac_eq_not_confirmed_silent
                }
            )
        )
    }
    if (state.outcome != null) Spacer(Modifier.height(10.dp))

    if (state.onDac && state.outcome == null) {
        Note(stringResource(R.string.dac_eq_same_as_dac))
        Spacer(Modifier.height(10.dp))
    }
    if (state.needsRead) {
        Warning(stringResource(R.string.dac_eq_untested_read_first))
        Spacer(Modifier.height(10.dp))
    }
    Note(stringResource(R.string.dac_eq_risk))
    Spacer(Modifier.height(10.dp))
    HoldToSend(
        label = stringResource(R.string.dac_eq_hold_to_send),
        enabled = ready && state.busy == DacEqBusy.IDLE && state.issues.isEmpty() && !state.needsRead && state.bands.isNotEmpty(),
        onSend = onSend,
    )
}

/**
 * The only thing on the page that writes to a DAC, so it cannot be brushed
 * by accident: it fills while it is held and sends when it is full; letting
 * go before that sends nothing.
 */
@Composable
private fun HoldToSend(label: String, enabled: Boolean, onSend: () -> Unit) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val send by rememberUpdatedState(onSend)
    val shape = RoundedCornerShape(26.dp)
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .border(1.dp, if (enabled) accent else outline, shape)
            .drawBehind {
                if (enabled) drawRect(accent.copy(alpha = 0.14f))
                drawRect(accent.copy(alpha = 0.5f), size = Size(size.width * fill.value, size.height))
            }
            .semantics {
                role = Role.Button
                if (enabled) onLongClick(label) { send(); true }
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val filling = scope.launch {
                            fill.snapTo(0f)
                            fill.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            send()
                        }
                        tryAwaitRelease()
                        filling.cancel()
                        fill.snapTo(0f)
                    })
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val HOLD_MS = 1200

// ---- the preset list ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(sources: List<DacEqSource>, onDismiss: () -> Unit, onChoose: (DacEqSource) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.dac_eq_choose_preset),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            if (sources.isEmpty()) {
                Note(stringResource(R.string.dac_eq_no_presets), modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(sources, key = { (if (it.parametric) "p:" else "a:") + it.preset.id }) { source ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick(onClick = { onChoose(source) })
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            source.preset.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(
                                stringResource(if (source.parametric) R.string.search_parametric_eq else R.string.eq_precision_autoeq),
                                stringResource(R.string.dac_eq_on_dac_bands, source.preset.bands.count { it.enabled }),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- small pieces ----

/**
 * Whose work the DAC side is. A tap shows Contour's own notice, which is in
 * the APK with its licence (assets/licenses/contour): it names the project
 * Contour's protocol maths came from and says no DAC maker is behind any of
 * this.
 */
@Composable
private fun Credit() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Text(
        stringResource(R.string.dac_eq_credit),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.bounceClick(onClick = { open = true })
    )
    if (!open) return
    val notice = remember {
        runCatching { context.assets.open("licenses/contour/NOTICE.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Contour") },
        text = {
            Text(
                notice.ifBlank { stringResource(R.string.dac_eq_credit) },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Good(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}

/** A heading in the error colour over the lines Contour gave, which are English and kept as they are. */
@Composable
private fun Problem(heading: String, lines: List<String>) {
    Text(heading, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
    lines.take(6).forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A level in dB with its sign and no needless decimals: "-4", "+2.5", "0". */
private fun db(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return when {
        rounded == 0f -> "0"
        rounded == rounded.toInt().toFloat() -> String.format(Locale.getDefault(), "%+d", rounded.toInt())
        else -> String.format(Locale.getDefault(), "%+.1f", rounded)
    }
}
