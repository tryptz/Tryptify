package tf.monochrome.android.ui.musiclinks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.LinkBand
import tf.monochrome.android.domain.model.LinkCurve
import tf.monochrome.android.domain.model.LinkFormula
import tf.monochrome.android.domain.model.LinkShape
import tf.monochrome.android.domain.model.LinkSmoothing
import tf.monochrome.android.domain.model.LinkWay
import tf.monochrome.android.domain.model.MusicLinkFollower
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.HideAppChrome
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.PressableGlass
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.settings.FxSlider
import tf.monochrome.android.ui.settings.FxToggle
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The green that marks anything moving with the music, everywhere it shows:
 * the note on a linked slider or knob, the link's range on its track. Darker on
 * a light page, where the bright one would not clear 3:1.
 */
@Composable
fun musicLinkColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF00873E) else Color(0xFF00E676)

/**
 * What a screen offers its linkable controls: which settings are linked, their
 * values this frame, and how to open the link sheet. A control reads [isLinked]
 * in composition (it changes only when the links do) and [liveValue] only in a
 * draw block (it changes every frame).
 */
@Stable
class MusicLinkHost(
    private val linkedKeys: State<Set<String>>,
    private val liveValues: () -> Map<String, Float>,
    private val ranges: State<Map<String, ClosedFloatingPointRange<Float>>>,
    val open: (String) -> Unit,
) {
    fun isLinked(key: String): Boolean = key in linkedKeys.value

    /** The link's lowest..highest, for drawing its reach on the control. */
    fun rangeOf(key: String): ClosedFloatingPointRange<Float>? = ranges.value[key]

    /** This frame's value. Draw phase only. */
    fun liveValue(key: String): Float? = liveValues()[key]
}

/** The link host for the controls on this screen; null where nothing can be linked. */
val LocalMusicLinkHost = staticCompositionLocalOf<MusicLinkHost?> { null }

/** What the link editor needs to know about the setting it moves. */
data class LinkTargetInfo(
    val label: String,
    val min: Float,
    val max: Float,
    val format: (Float) -> String,
)

@Composable
fun bandLabel(band: LinkBand): String = stringResource(
    when (band) {
        LinkBand.KICK -> R.string.music_link_band_kick
        LinkBand.VOCALS -> R.string.music_link_band_vocals
        LinkBand.SNARE -> R.string.music_link_band_snare
        LinkBand.HATS -> R.string.music_link_band_hats
        LinkBand.CUSTOM -> R.string.music_link_band_custom
    },
)

@Composable
fun wayLabel(way: LinkWay): String = stringResource(
    when (way) {
        LinkWay.UP -> R.string.music_link_way_up
        LinkWay.DOWN -> R.string.music_link_way_down
        LinkWay.BOTH -> R.string.music_link_way_both
    },
)

/** "30 Hz", "1.2 kHz", "16 kHz". */
fun formatHz(hz: Float): String = when {
    hz < 1000f -> "${hz.roundToInt()} Hz"
    hz < 10_000f -> String.format(Locale.US, "%.1f kHz", hz / 1000f)
    else -> "${(hz / 1000f).roundToInt()} kHz"
}

private fun hzToPos(hz: Float): Float =
    ln(hz.coerceIn(LinkShape.MIN_HZ, LinkShape.MAX_HZ) / LinkShape.MIN_HZ) / ln(LinkShape.MAX_HZ / LinkShape.MIN_HZ)

private fun posToHz(pos: Float): Float =
    LinkShape.MIN_HZ * (LinkShape.MAX_HZ / LinkShape.MIN_HZ).pow(pos.coerceIn(0f, 1f))

/**
 * The dim layer behind a link sheet: Back closes the sheet, and nothing
 * reaches the screen underneath. A tap on it closes the sheet too, unless
 * [dismissOnTap] is off — the editor's, where a stray tap beside the panel
 * must not throw away a link half made.
 */
@Composable
fun MusicLinkScrim(onDismiss: () -> Unit, dim: Float = 0.32f, dismissOnTap: Boolean = true) {
    BackHandler(onBack = onDismiss)
    val closeLabel = stringResource(R.string.action_close)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (dismissOnTap) closeLabel else null,
                onClick = { if (dismissOnTap) onDismiss() },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = dim)) }
    }
}

/**
 * One big action in a link sheet: a glass slab the height of a thumb, an icon,
 * and a line saying what it does. [primary] tints the slab the link green.
 */
@Composable
private fun LinkActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    hazeState: HazeState?,
) {
    val base = LocalMiniPlayerGlass.current
    val green = musicLinkColor()
    val glass = if (primary) base.copy(tintColor = green.toArgb()) else base
    PressableGlass(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        corner = 18.dp,
        enabled = enabled,
        hazeState = hazeState,
        glass = glass,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/**
 * The sheet a long press on a linkable control opens, at the bottom of its
 * screen. It is drawn in the screen's own window — never a dialog or a
 * ModalBottomSheet, which is a window of its own and would leave this glass
 * nothing to frost (see docs/ui-invariants.md, "Glass").
 *
 * [linked] says what to offer: "Move with the music" for a setting without a
 * link, change / pause / remove for one with. [cannotReason], when set, says
 * why this control cannot follow the music at all.
 */
@Composable
fun BoxScope.MusicLinkOptionsSheet(
    title: String,
    valueText: String,
    linked: Boolean,
    paused: Boolean,
    followsText: String?,
    cannotReason: String?,
    hazeState: HazeState?,
    onMove: () -> Unit,
    onChange: () -> Unit,
    onTogglePause: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    MusicLinkScrim(onDismiss)
    CompositionLocalProvider(LocalGlassBackdrop provides hazeState) {
        GlassPanel(
            hazeState = hazeState,
            glass = LocalMiniPlayerGlass.current,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomChromeNow.current),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(valueText, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                }
                if (followsText != null) {
                    Text(
                        followsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                when {
                    cannotReason != null -> LinkActionRow(
                        icon = Icons.Default.MusicOff,
                        title = stringResource(R.string.music_link_move_with_music),
                        subtitle = cannotReason,
                        onClick = {},
                        enabled = false,
                        hazeState = hazeState,
                    )
                    !linked -> LinkActionRow(
                        icon = Icons.Default.MusicNote,
                        title = stringResource(R.string.music_link_move_with_music),
                        subtitle = stringResource(R.string.music_link_move_with_music_desc),
                        onClick = onMove,
                        primary = true,
                        hazeState = hazeState,
                    )
                    else -> {
                        LinkActionRow(
                            icon = Icons.Default.Edit,
                            title = stringResource(R.string.music_link_change),
                            subtitle = null,
                            onClick = onChange,
                            hazeState = hazeState,
                        )
                        LinkActionRow(
                            icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            title = stringResource(if (paused) R.string.music_link_resume else R.string.music_link_pause),
                            subtitle = null,
                            onClick = onTogglePause,
                            hazeState = hazeState,
                        )
                        LinkActionRow(
                            icon = Icons.Default.Delete,
                            title = stringResource(R.string.music_link_remove),
                            subtitle = null,
                            onClick = onRemove,
                            hazeState = hazeState,
                        )
                    }
                }
                GlassPill(
                    onClick = onDismiss,
                    hazeState = hazeState,
                    height = 48.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * The link editor: the same choices in the same order wherever a link is made
 * — what it listens to, which part of the sound, how far, which way, how it
 * feels, the curve — with the rarer ones folded under "More options". Every
 * change is handed out at once through [onShapeChange], so a screen with a live
 * preview shows it before the listener presses Done.
 *
 * [listensTo] is the source picker: the mixer offers its buses, a visual link
 * shows the one thing it can hear. [spectrum] and [strength] feed the band
 * picker's live picture and meter; both are read in draw blocks.
 *
 * It takes nearly the whole window, the tab bar and the mini player stepping
 * aside while it is open: squeezed under a preview and over the bar, it had
 * room for its title and little else, and every choice was a scroll away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.MusicLinkEditor(
    title: String,
    target: LinkTargetInfo,
    shape: LinkShape,
    onShapeChange: (LinkShape) -> Unit,
    spectrum: State<FloatArray>?,
    strength: () -> Float,
    note: String?,
    isNew: Boolean,
    hazeState: HazeState?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)?,
    listensTo: @Composable () -> Unit,
) {
    HideAppChrome(true)
    MusicLinkScrim(onCancel, dim = 0.32f, dismissOnTap = false)
    val accent = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalGlassBackdrop provides hazeState) {
        GlassPanel(
            hazeState = hazeState,
            glass = LocalMiniPlayerGlass.current,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                // With the bar gone the screen runs to the bottom edge, and
                // this is the system bar alone.
                .padding(top = 8.dp, bottom = LocalBottomChromeNow.current + 8.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.music_link_editor_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionTitle(stringResource(R.string.music_link_listens_to))
                    listensTo()

                    SectionTitle(stringResource(R.string.music_link_which_part))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinkBand.entries.forEach { band ->
                            GlassChoiceChip(
                                label = bandLabel(band),
                                selected = shape.band == band,
                                accent = accent,
                                height = 40.dp,
                                onClick = {
                                    onShapeChange(
                                        if (band == LinkBand.CUSTOM) {
                                            shape.copy(band = band, loHz = shape.effectiveLoHz, hiHz = shape.effectiveHiHz)
                                        } else {
                                            shape.copy(band = band)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    BandPicture(shape, spectrum, strength)
                    if (shape.band == LinkBand.CUSTOM) {
                        FxSlider(
                            stringResource(R.string.music_link_from), formatHz(shape.loHz),
                            hzToPos(shape.loHz), 0f..1f,
                        ) { p ->
                            val lo = posToHz(p).coerceAtMost(shape.hiHz / LinkShape.MIN_BAND_RATIO)
                            onShapeChange(shape.copy(loHz = lo))
                        }
                        FxSlider(
                            stringResource(R.string.music_link_to), formatHz(shape.hiHz),
                            hzToPos(shape.hiHz), 0f..1f,
                        ) { p ->
                            val hi = posToHz(p).coerceAtLeast(shape.loHz * LinkShape.MIN_BAND_RATIO)
                            onShapeChange(shape.copy(hiHz = hi))
                        }
                    }

                    SectionTitle(stringResource(R.string.music_link_how_far))
                    FxSlider(
                        stringResource(R.string.music_link_lowest), target.format(shape.lowest),
                        shape.lowest, target.min..target.max,
                    ) { v -> onShapeChange(shape.copy(lowest = v)) }
                    FxSlider(
                        stringResource(R.string.music_link_highest), target.format(shape.highest),
                        shape.highest, target.min..target.max,
                    ) { v -> onShapeChange(shape.copy(highest = v)) }

                    SectionTitle(stringResource(R.string.music_link_which_way))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkWay.entries.forEach { way ->
                            GlassChoiceChip(
                                label = wayLabel(way),
                                selected = shape.way == way,
                                accent = accent,
                                height = 44.dp,
                                onClick = { onShapeChange(shape.copy(way = way)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    SectionTitle(stringResource(R.string.music_link_feel))
                    FxSlider(
                        stringResource(R.string.music_link_feel_range),
                        stringResource(
                            R.string.music_link_feel_value,
                            shape.attackMs.roundToInt(),
                            shape.releaseMs.roundToInt(),
                        ),
                        shape.feel, 0f..1f,
                    ) { v -> onShapeChange(shape.copy(feel = v)) }

                    SectionTitle(stringResource(R.string.music_link_curve))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkCurve.entries.forEach { curve ->
                            GlassChoiceChip(
                                label = stringResource(
                                    when (curve) {
                                        LinkCurve.EVEN -> R.string.music_link_curve_even
                                        LinkCurve.GENTLE -> R.string.music_link_curve_gentle
                                        LinkCurve.STRONG -> R.string.music_link_curve_strong
                                    },
                                ),
                                selected = shape.curve == curve,
                                accent = accent,
                                height = 40.dp,
                                onClick = { onShapeChange(shape.copy(curve = curve)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        stringResource(
                            when (shape.curve) {
                                LinkCurve.EVEN -> R.string.music_link_curve_even_desc
                                LinkCurve.GENTLE -> R.string.music_link_curve_gentle_desc
                                LinkCurve.STRONG -> R.string.music_link_curve_strong_desc
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )

                    MoreOptions(shape, onShapeChange, accent)
                    Spacer(Modifier.height(4.dp))
                }
                if (onRemove != null) {
                    GlassPill(
                        onClick = onRemove,
                        hazeState = hazeState,
                        height = 44.dp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Text(stringResource(R.string.music_link_remove), fontWeight = FontWeight.Bold)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassPill(
                        onClick = onCancel,
                        hazeState = hazeState,
                        height = 52.dp,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
                    }
                    GlassPill(
                        onClick = onDone,
                        hazeState = hazeState,
                        height = 52.dp,
                        glass = LocalMiniPlayerGlass.current.copy(tintColor = musicLinkColor().toArgb()),
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text(
                            stringResource(if (isNew) R.string.music_link_start else R.string.action_done),
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/**
 * The live spectrum with the chosen part lit, and under it a meter of how
 * strong that part is after the link's own follower. Both read their state in
 * the draw phase, so the editor does not recompose with the music.
 */
@Composable
private fun BandPicture(shape: LinkShape, spectrum: State<FloatArray>?, strength: () -> Float) {
    val green = musicLinkColor()
    val ink = LocalContentColor.current
    val silent by remember(spectrum) {
        derivedStateOf { spectrum == null || spectrum.value.all { it == 0f } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (spectrum != null) {
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val bins = spectrum.value
                val lo = hzToPos(shape.effectiveLoHz) * size.width
                val hi = hzToPos(shape.effectiveHiHz) * size.width
                drawRoundRect(
                    color = green.copy(alpha = 0.16f),
                    topLeft = Offset(lo, 0f),
                    size = Size((hi - lo).coerceAtLeast(2f), size.height),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                if (bins.isNotEmpty()) {
                    val n = 64
                    val w = size.width / n
                    for (i in 0 until n) {
                        val b = (i * bins.size / n).coerceIn(0, bins.size - 1)
                        // The analyser's bins sit around 0 dB for pink noise; -40..+12 fills the box.
                        val h = ((bins[b] + 40f) / 52f).coerceIn(0.03f, 1f) * size.height
                        val x = i * w
                        val inBand = x + w / 2 in lo..hi
                        drawRect(
                            color = if (inBand) green else ink.copy(alpha = 0.3f),
                            topLeft = Offset(x + w * 0.15f, size.height - h),
                            size = Size(w * 0.7f, h),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("20 Hz", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
                Text(
                    "${formatHz(shape.effectiveLoHz)} – ${formatHz(shape.effectiveHiHz)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("20 kHz", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
            }
        }
        Text(
            stringResource(R.string.music_link_strength),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(ink.copy(alpha = 0.18f), cornerRadius = r)
            val s = strength().coerceIn(0f, 1f)
            if (s > 0f) drawRoundRect(green, size = Size(size.width * s, size.height), cornerRadius = r)
        }
        Text(
            stringResource(if (silent) R.string.music_link_no_music else R.string.music_link_strength_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreOptions(shape: LinkShape, onShapeChange: (LinkShape) -> Unit, accent: Color) {
    var open by rememberSaveable { mutableStateOf(shape.formula.isNotBlank()) }
    PressableGlass(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
        shape = RoundedCornerShape(16.dp),
        corner = 16.dp,
        hazeState = LocalGlassBackdrop.current,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.music_link_more_options), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }
    }
    AnimatedVisibility(visible = open) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FxSlider(
                stringResource(R.string.music_link_quiet), "${shape.quietDb.roundToInt()} dB",
                shape.quietDb, LinkShape.MIN_QUIET_DB..0f,
                description = stringResource(R.string.music_link_quiet_desc),
            ) { v -> onShapeChange(shape.copy(quietDb = v)) }
            FxSlider(
                stringResource(R.string.music_link_boost),
                (if (shape.boostDb > 0f) "+" else "") + "${shape.boostDb.roundToInt()} dB",
                shape.boostDb, -LinkShape.MAX_BOOST_DB..LinkShape.MAX_BOOST_DB,
                description = stringResource(R.string.music_link_boost_desc),
            ) { v -> onShapeChange(shape.copy(boostDb = v)) }
            Text(stringResource(R.string.music_link_smoothing), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Text(
                stringResource(R.string.music_link_smoothing_desc),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.8f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                LinkSmoothing.entries.forEach { s ->
                    GlassChoiceChip(
                        label = stringResource(
                            when (s) {
                                LinkSmoothing.OFF -> R.string.music_link_smoothing_off
                                LinkSmoothing.LOW -> R.string.music_link_smoothing_low
                                LinkSmoothing.MEDIUM -> R.string.music_link_smoothing_medium
                                LinkSmoothing.HIGH -> R.string.music_link_smoothing_high
                            },
                        ),
                        selected = shape.smoothing == s,
                        accent = accent,
                        height = 40.dp,
                        onClick = { onShapeChange(shape.copy(smoothing = s)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            FxToggle(
                stringResource(R.string.music_link_pause_silent), shape.pauseWhenSilent,
                description = stringResource(R.string.music_link_pause_silent_desc),
            ) { on -> onShapeChange(shape.copy(pauseWhenSilent = on)) }
            FormulaField(shape, onShapeChange, accent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormulaField(shape: LinkShape, onShapeChange: (LinkShape) -> Unit, accent: Color) {
    val compiled = remember(shape.formula) { LinkFormula.compile(shape.formula) }
    Text(stringResource(R.string.music_link_formula), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
    Text(
        stringResource(R.string.music_link_formula_desc),
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = 0.8f),
    )
    OutlinedTextField(
        value = shape.formula,
        onValueChange = { onShapeChange(shape.copy(formula = it.take(LinkFormula.MAX_LENGTH))) },
        singleLine = true,
        placeholder = { Text("value + band * 0.5") },
        isError = !compiled.ok,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    val message = when (compiled.problem) {
        null -> if (shape.formula.isBlank()) null else stringResource(R.string.music_link_formula_ok)
        LinkFormula.Problem.UNKNOWN_WORD -> stringResource(R.string.music_link_formula_unknown, compiled.detail)
        LinkFormula.Problem.BAD_SYMBOL -> stringResource(R.string.music_link_formula_symbol, compiled.detail)
        LinkFormula.Problem.MISSING_BRACKET -> stringResource(R.string.music_link_formula_bracket)
        LinkFormula.Problem.ENDS_WITH_OPERATOR -> stringResource(R.string.music_link_formula_ends)
        LinkFormula.Problem.NO_BAND -> stringResource(R.string.music_link_formula_no_band)
        LinkFormula.Problem.TOO_LONG -> stringResource(R.string.music_link_formula_too_long)
        LinkFormula.Problem.NOT_FINISHED -> stringResource(R.string.music_link_formula_not_finished)
    }
    if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (compiled.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    Text(stringResource(R.string.music_link_formula_examples), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("value + band * 0.5", "value - band * 0.5", "value + band * band", "value + (fft[band] * 0.25)").forEach { example ->
            GlassChoiceChip(
                label = example,
                selected = shape.formula == example,
                accent = accent,
                height = 36.dp,
                onClick = { onShapeChange(shape.copy(formula = example)) },
            )
        }
        if (shape.formula.isNotBlank()) {
            GlassChoiceChip(
                label = stringResource(R.string.music_link_formula_clear),
                selected = false,
                accent = accent,
                height = 36.dp,
                onClick = { onShapeChange(shape.copy(formula = "")) },
            )
        }
    }
}

/**
 * The note before a linked control's label, and under its track the link's
 * reach and where it is this frame. Draws nothing for a control without a
 * link. The position is read in the draw phase.
 */
@Composable
fun LinkedTrackMarker(
    key: String,
    host: MusicLinkHost,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
) {
    val green = musicLinkColor()
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val range = host.rangeOf(key) ?: return@Canvas
        val span = (max - min).takeIf { it > 0f } ?: return@Canvas
        val a = ((minOf(range.start, range.endInclusive) - min) / span).coerceIn(0f, 1f) * size.width
        val b = ((maxOf(range.start, range.endInclusive) - min) / span).coerceIn(0f, 1f) * size.width
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(green.copy(alpha = 0.35f), topLeft = Offset(a, 0f), size = Size((b - a).coerceAtLeast(size.height), size.height), cornerRadius = r)
        val v = host.liveValue(key) ?: return@Canvas
        val x = ((v - min) / span).coerceIn(0f, 1f) * size.width
        drawCircle(green, radius = size.height * 1.2f, center = Offset(x, size.height / 2f))
    }
}

/** The one source a visual link can hear: shown, not chosen. */
@Composable
fun WhatYouHearRow() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = musicLinkColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.music_link_what_you_hear), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.music_link_what_you_hear_desc),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.8f),
            )
        }
    }
}

/** "Follows Kick & bass" — or "Paused". */
@Composable
fun followsText(shape: LinkShape, paused: Boolean): String =
    if (paused) {
        stringResource(R.string.music_link_paused)
    } else {
        stringResource(R.string.music_link_follows, bandLabel(shape.band))
    }

/**
 * The spectrum a link editor draws, with its own stake in the analyser while
 * the editor is open. The links listen to a spectrum of their own
 * (`SpectrumAnalyzerTap.linkPower`), which does not run the display's; an
 * editor that only read [SpectrumAnalyzerTap.spectrumBins] got zeros, drew
 * full bars and hid its meter behind "Play a song" with a song playing.
 */
@Composable
fun rememberEditorSpectrum(tap: tf.monochrome.android.audio.eq.SpectrumAnalyzerTap): State<FloatArray> {
    androidx.lifecycle.compose.LifecycleStartEffect(tap) {
        tap.acquire()
        onStopOrDispose { tap.release() }
    }
    return tap.spectrumBins.collectAsState()
}

/** A strength reading for a band the editor is not driving: its level now against a fixed floor. */
fun roughStrength(bins: FloatArray, shape: LinkShape): Float {
    if (bins.isEmpty() || bins.all { it == 0f }) return 0f
    val db = MusicLinkFollower.bandLevel(bins, shape.effectiveLoHz, shape.effectiveHiHz) + shape.boostDb
    return ((db + 30f) / 36f).coerceIn(0f, 1f)
}
