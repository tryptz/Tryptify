// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.chart.GlyphDemoChart
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Trypt Mania's front door: Play, the map editor and Settings, with the
 * leaderboard in the top bar.
 *
 * Behind it, a playfield plays a demo chart by itself, arrows rising into the
 * receptors under the title in the player's own noteskin, faded so the menu
 * reads first and gone before the buttons. It is a menu of the app: the
 * theme's page, a top bar with Back, glass pills, and the tab bar and mini
 * player still underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphMenuScreen(
    state: GlyphUiState,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    onEvent: (GlyphEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modifiers = state.gameplay.modifiers
    val chromeNow = LocalBottomChromeNow.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        GlyphPreviewField(
            chart = GlyphDemoChart.chart,
            timing = GlyphDemoChart.timing,
            turn = GlyphTurn.OFF,
            scrollMode = GlyphScrollMode.XMod(DEMO_SPEED),
            skin = modifiers.skin,
            look = GlyphFieldLook(
                reverse = true,
                // A backdrop: the explosions and the beat, none of the light.
                graphics = GlyphGraphics(
                    hitRays = false,
                    noteLighting = false,
                    explosions = modifiers.graphics.explosions,
                    beatFlash = modifiers.graphics.beatFlash,
                    comboBurst = false,
                ),
            ),
            assets = assets,
            palette = palette,
            loop = GlyphDemoChart.loopStartSeconds to GlyphDemoChart.loopSeconds,
            receptorInset = RECEPTOR_INSET,
            noteScale = DEMO_NOTE_SCALE,
            reducedMotion = modifiers.reducedMotion,
            fadeLoop = false,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = BACKDROP_ALPHA
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Gone before the buttons, so they sit on the page.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            0.5f to Color.Black,
                            0.78f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onEvent(GlyphEvent.OpenLeaderboard) }) {
                        Icon(Icons.Default.Leaderboard, contentDescription = "Leaderboard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )

            Text(
                text = stringResource(R.string.trypt_mania).uppercase(Locale.getDefault()).replace(' ', '\n'),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = MaterialTheme.typography.displayMedium.fontSize * 0.95f,
                modifier = Modifier
                    .padding(horizontal = MonoDimens.spacingXl)
                    .semantics { heading() },
            )
            // The mode is in beta. Its own line under the name, small and in
            // the accent, so the logo keeps its two words.
            Text(
                text = stringResource(R.string.badge_beta).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = MonoDimens.spacingXl, vertical = MonoDimens.spacingXs),
            )
            Spacer(Modifier.height(MonoDimens.spacingSm))
            Text(
                text = "StepMania's rules, your library.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MonoDimens.spacingXl),
            )

            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonoDimens.spacingLg)
                    .padding(bottom = chromeNow + MonoDimens.spacingMd),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
            ) {
                MenuPill(
                    title = "Play",
                    subtitle = "Pick a song, set your mods, go",
                    icon = Icons.Default.PlayArrow,
                    large = true,
                    onClick = { onEvent(GlyphEvent.OpenSongs(GlyphSongListMode.PLAY)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MenuPill(
                    title = stringResource(R.string.tm_multiplayer),
                    subtitle = stringResource(R.string.tm_mp_menu_subtitle),
                    icon = Icons.Default.Groups,
                    onClick = { onEvent(GlyphEvent.OpenMultiplayer) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MenuPill(
                    title = stringResource(R.string.tm_charting_menu_title),
                    subtitle = stringResource(R.string.tm_charting_menu_subtitle),
                    icon = Icons.Default.AutoAwesome,
                    onClick = { onEvent(GlyphEvent.OpenCharting) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    MenuPill(
                        title = "Map editor",
                        icon = Icons.Default.Edit,
                        onClick = { onEvent(GlyphEvent.OpenSongs(GlyphSongListMode.EDIT)) },
                        modifier = Modifier.weight(1f),
                    )
                    MenuPill(
                        title = "Packs",
                        icon = Icons.Default.LibraryMusic,
                        onClick = { onEvent(GlyphEvent.OpenPacks) },
                        modifier = Modifier.weight(1f),
                    )
                    MenuPill(
                        title = "Settings",
                        icon = Icons.Default.Tune,
                        onClick = { onEvent(GlyphEvent.OpenSettings) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One of the menu's glass pills: an icon, its name, and for Play a line under it. */
@Composable
private fun MenuPill(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    large: Boolean = false,
) {
    GlassPill(
        onClick = onClick,
        hazeState = null,
        height = if (large) 76.dp else 60.dp,
        onClickLabel = title,
        modifier = modifier,
    ) {
        val scheme = MaterialTheme.colorScheme
        if (!large && subtitle == null) {
            // Three to a row: the icon over its name, which shrinks before it would be cut,
            // so "Map editor" and "Settings" stay whole in a wide font.
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = MonoDimens.spacingSm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 9.sp,
                        maxFontSize = MaterialTheme.typography.titleSmall.fontSize,
                        stepSize = 0.5.sp,
                    ),
                )
            }
            return@GlassPill
        }
        Row(
            modifier = Modifier
                .align(if (large) Alignment.CenterStart else Alignment.Center)
                .padding(horizontal = if (large) MonoDimens.spacingXl else MonoDimens.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(if (large) 28.dp else 20.dp),
            )
            Spacer(Modifier.width(if (large) MonoDimens.spacingMd else MonoDimens.spacingSm))
            Column {
                Text(
                    text = title,
                    style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Below the title, where the rising arrows meet their receptors. */
private val RECEPTOR_INSET = 236.dp
private const val DEMO_SPEED = 2.25f
private const val DEMO_NOTE_SCALE = 0.8f
private const val BACKDROP_ALPHA = 0.42f
