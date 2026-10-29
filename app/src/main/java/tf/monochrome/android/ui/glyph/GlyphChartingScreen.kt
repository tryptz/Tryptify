// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.text.NumberFormat
import java.util.Locale
import tf.monochrome.android.R
import tf.monochrome.android.audio.stepmania.ChartingAccount
import tf.monochrome.android.audio.stepmania.ChartingEntry
import tf.monochrome.android.audio.stepmania.ChartingPack
import tf.monochrome.android.audio.stepmania.ChartingPacks
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable

/**
 * Charting by the minute: the listener's minutes as a meter, the free first
 * song, the packs on sale and what they have charted.
 *
 * A menu, so the app's (see `docs/ui-invariants.md`, Trypt Mania): a top bar
 * with Back, the theme's page and ink, `GlyphPanel`s. The meter reads like a
 * plan's usage bar: one thin track, the share used filled in the accent, the
 * figures beside it, and no second colour until the minutes are gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphChartingScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val account = state.charting.account
    var picked by rememberSaveable { mutableStateOf(ChartingPacks.MOST_PICKED) }
    val pack = account.packs.firstOrNull { it.id == picked } ?: account.packs.first()
    val signedOut = account.loaded && !account.signedIn
    BackHandler { onEvent(GlyphEvent.CloseCharting) }
    // A checkout goes to the browser; coming back from it, the account is read again.
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.charting.checkoutUrl) {
        val url = state.charting.checkoutUrl ?: return@LaunchedEffect
        runCatching { uriHandler.openUri(url) }
        onEvent(GlyphEvent.ChartingUrlOpened)
    }
    LifecycleResumeEffect(Unit) {
        onEvent(GlyphEvent.RefreshCharting)
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tm_charting_title), modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.CloseCharting) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MonoDimens.spacingLg),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            val scheme = MaterialTheme.colorScheme
            state.charting.notice?.let { notice ->
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(notice), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(text = "OK", onClick = { onEvent(GlyphEvent.DismissChartingNotice) }, height = 40.dp)
                }
            }

            // ── the meter ───────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                UsageMeter(account)
            }

            // ── signed out: nothing here is theirs yet ──────────────────
            if (signedOut) {
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.tm_charting_sign_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    GlyphButton(text = stringResource(R.string.tm_mp_sign_in_button), primary = true, onClick = onSignIn)
                }
            }

            // ── the free song ───────────────────────────────────────────
            if (account.freeSongAvailable || (!account.loaded && !account.freeSongUsed)) {
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.tm_charting_free_title))
                    Text(
                        stringResource(R.string.tm_charting_free_body, ChartingAccount.MAX_SONG_MINUTES),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    GlyphButton(
                        text = stringResource(R.string.tm_charting_free_action),
                        icon = Icons.Default.PlayArrow,
                        primary = true,
                        onClick = { onEvent(GlyphEvent.OpenSongs(GlyphSongListMode.PLAY)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── charting now: the line, song by song ────────────────────
            val queue = state.charting.queue
            if (queue.active) {
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    val percent = (queue.fraction * 100).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.tm_charting_now),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        // A readout that changes while it is read: monospaced, so the digits do not shuffle.
                        Text(
                            text = "$percent%",
                            style = GlyphTypography(rememberStepTechFontFamily()).mono,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.tm_charting_now_count, queue.finished.size, queue.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphMeter(
                        fraction = queue.fraction,
                        color = scheme.primary,
                        description = "$percent percent",
                    )
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    queue.running.forEach { flight ->
                        LineRow(title = flight.job.title, stage = flight.stage, dim = false)
                    }
                    queue.waiting.take(WAITING_SHOWN).forEach { job ->
                        LineRow(title = job.title, stage = stringResource(R.string.tm_charting_waiting), dim = true)
                    }
                    val more = queue.waiting.size - WAITING_SHOWN
                    if (more > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.tm_charting_more_waiting, more, more),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MonoDimens.spacingXs),
                        )
                    }
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    GlyphButton(
                        text = stringResource(if (queue.total > 1) R.string.tm_charting_cancel_all else R.string.action_cancel),
                        onClick = { onEvent(GlyphEvent.CancelGeneration) },
                        height = 40.dp,
                    )
                }
            }

            // ── what has been charted ───────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd)) {
                    GlyphStat(
                        label = stringResource(R.string.tm_charting_stat_songs),
                        value = count(account.songsCharted),
                        modifier = Modifier.weight(1f),
                    )
                    GlyphStat(
                        label = stringResource(R.string.tm_charting_stat_minutes),
                        value = count(account.minutesUsed),
                        modifier = Modifier.weight(1f),
                    )
                    GlyphStat(
                        label = stringResource(R.string.tm_charting_stat_free),
                        value = stringResource(
                            if (account.freeSongUsed) R.string.tm_charting_free_spent else R.string.tm_charting_free_available,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── packs ───────────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_charting_packs_title))
                Text(
                    stringResource(
                        R.string.tm_charting_packs_note,
                        ChartingAccount.MIN_SONG_MINUTES,
                        ChartingAccount.SONG_MINUTES,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
                ) {
                    account.packs.forEach { option ->
                        PackRow(option, selected = option.id == picked, onPick = { picked = option.id })
                    }
                }
                Spacer(Modifier.height(MonoDimens.spacingMd))
                GlyphButton(
                    text = stringResource(R.string.tm_charting_buy, count(pack.minutes), price(pack)),
                    icon = Icons.Default.ShoppingCart,
                    primary = true,
                    onClick = { onEvent(GlyphEvent.BuyMinutes(pack.id)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MonoDimens.spacingSm))
                Text(
                    stringResource(R.string.tm_charting_fail_refund),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            // ── history ─────────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_charting_history))
                if (account.history.isEmpty()) {
                    Text(
                        stringResource(R.string.tm_charting_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    account.history.forEach { entry ->
                        HistoryRow(entry, onOpen = { onEvent(GlyphEvent.OpenChartedSong(entry.title)) })
                    }
                }
            }

            Text(
                stringResource(R.string.tm_charting_own_server),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MonoDimens.spacingXs),
            )
            Spacer(Modifier.height(LocalBottomChromeInset.current + MonoDimens.spacingLg))
        }
    }
}

/**
 * Minutes left over minutes bought: the figures on one line, a thin track
 * under them with the used share filled, and under that the share as a
 * percentage and about how many songs are left. With nothing bought the
 * track is empty and the line says the first song is free.
 */
@Composable
private fun UsageMeter(account: ChartingAccount) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = stringResource(R.string.tm_charting_minutes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            text = stringResource(R.string.tm_charting_of_minutes, count(account.minutesLeft), count(account.minutesBought)),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(MonoDimens.spacingSm))
    val fraction = account.usedFraction
    val percent = (fraction * 100).toInt()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(METER_HEIGHT)
            .clip(MonoDimens.shapeLg)
            .background(scheme.surfaceVariant),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(MonoDimens.shapeLg)
                    .background(if (account.minutesLeft == 0) scheme.error else scheme.primary),
            )
        }
    }
    Spacer(Modifier.height(MonoDimens.spacingSm))
    if (account.minutesBought == 0 && account.freeSongAvailable) {
        Text(
            text = stringResource(R.string.tm_charting_no_minutes),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    } else {
        Row {
            Text(
                text = stringResource(R.string.tm_charting_used_percent, percent),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.tm_charting_songs_left, account.songsLeft, account.songsLeft),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** One pack to pick: its name, its minutes and about how many songs, its price. */
@Composable
private fun PackRow(pack: ChartingPack, selected: Boolean, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val name = stringResource(packNameOf(pack.id))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeLg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = MonoDimens.shapeLg,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (pack.id == ChartingPacks.MOST_PICKED) {
                    Text(
                        text = stringResource(R.string.tm_charting_most_picked).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = menuAccent(scheme.primary),
                    )
                }
            }
            Text(
                text = stringResource(R.string.tm_charting_pack_line, count(pack.minutes), count(pack.songs)),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(price(pack), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** One song of the line: its title, and what it is at (or that it waits). */
@Composable
private fun LineRow(title: String, stage: String, dim: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MonoDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dim) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(MonoDimens.spacingSm))
        Text(
            text = stage,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * One line of the history. A song's line opens it in the song list; a pack
 * bought is no song and takes no tap. The press fills the row's own shape,
 * as a list row's does, with the arrow saying where it goes.
 */
@Composable
private fun HistoryRow(entry: ChartingEntry, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val song = entry.kind != ChartingEntry.Kind.BOUGHT
    val openLabel = stringResource(R.string.tm_charting_open_song)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (song) Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen) else Modifier)
            .padding(vertical = MonoDimens.spacingSm, horizontal = MonoDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = if (entry.kind == ChartingEntry.Kind.BOUGHT) stringResource(packNameOf(entry.title)) else entry.title
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(MonoDimens.spacingSm))
        val minutes = when (entry.kind) {
            ChartingEntry.Kind.BOUGHT -> "+" + count(entry.minutes)
            ChartingEntry.Kind.CHARTED -> "−" + count(entry.minutes)
            ChartingEntry.Kind.FAILED, ChartingEntry.Kind.FREE -> count(0)
        }
        Text(
            text = minutes,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.kind == ChartingEntry.Kind.BOUGHT) menuAccent(scheme.primary) else scheme.onSurface,
        )
        if (song) {
            Spacer(Modifier.width(MonoDimens.spacingXs))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            // The arrow's room, so the minutes line up down the list.
            Spacer(Modifier.width(MonoDimens.spacingXs + 20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = MonoDimens.spacingXs).semantics { heading() },
    )
}

private fun packNameOf(id: String): Int = when (id) {
    ChartingPacks.STARTER.id -> R.string.tm_charting_pack_starter
    ChartingPacks.LIBRARY.id -> R.string.tm_charting_pack_library
    else -> R.string.tm_charting_pack_big
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** Prices are in US dollars wherever the listener is: Creem charges them. */
private fun price(pack: ChartingPack): String = String.format(Locale.ROOT, "$%d.%02d", pack.priceCents / 100, pack.priceCents % 100)

private val METER_HEIGHT = 8.dp

/** Waiting songs listed before "and N more": a long batch is a count, not a wall. */
private const val WAITING_SHOWN = 8
