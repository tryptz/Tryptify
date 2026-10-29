// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.style.GlyphStyleLibrary
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Trypt Mania's Themes: StepMania noteskins, themes and announcers imported
 * from a file (the StepMania Archive's downloads: ZIP, .smzip or 7z), and the
 * ones in use. Built like Packs, because it is one: a menu, so the app's (see
 * `docs/ui-invariants.md`, Trypt Mania): a top bar with Back, the theme's page
 * and ink, `GlyphPanel`s, `GlyphButton`s. What a StepMania theme changes is
 * the playfield's art and sounds, never these menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphThemesScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val themes = state.themes
    val styles = themes.styles
    var removing by remember { mutableStateOf<GlyphStyleLibrary.Style?>(null) }
    var browsing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onEvent(GlyphEvent.ImportStyle(uri))
    }
    BackHandler { onEvent(GlyphEvent.CloseThemes) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tm_themes_title), modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.CloseThemes) }) {
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
            themes.message?.let { message ->
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(text = "OK", onClick = { onEvent(GlyphEvent.DismissThemesMessage) }, height = 40.dp)
                }
            }

            // ── importing ───────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_themes_import_title))
                Text(
                    stringResource(R.string.tm_themes_import_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MonoDimens.spacingSm))
                if (themes.importing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                }
                GlyphButton(
                    text = stringResource(R.string.tm_themes_browse_archive),
                    onClick = { browsing = true },
                    height = 44.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphButton(
                    text = stringResource(R.string.tm_themes_import_button),
                    onClick = {
                        if (!themes.importing) picker.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/x-7z-compressed", "application/octet-stream"),
                        )
                    },
                    height = 44.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── themes ──────────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_themes_themes))
                Text(stringResource(R.string.tm_themes_themes_desc), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                StyleRow(
                    name = stringResource(R.string.tm_themes_own_theme),
                    detail = stringResource(R.string.tm_themes_own_theme_desc),
                    inUse = themes.themeId == null,
                    onUse = { onEvent(GlyphEvent.UseTheme(null)) },
                    onRemove = null,
                )
                for (style in styles.themes) {
                    StyleRow(
                        name = style.name,
                        detail = style.source,
                        inUse = themes.themeId == style.id,
                        onUse = { onEvent(GlyphEvent.UseTheme(style.id)) },
                        onRemove = { removing = style },
                    )
                }
            }

            // ── noteskins ───────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_themes_noteskins))
                Text(stringResource(R.string.tm_themes_noteskins_desc), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (styles.noteskins.isEmpty()) {
                    Text(stringResource(R.string.tm_themes_none_yet), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                val skin = state.gameplay.modifiers.skin
                for (style in styles.noteskins) {
                    val id = GlyphSkin.importedId(style.id)
                    StyleRow(
                        name = style.name,
                        detail = style.source,
                        inUse = skin.id == id,
                        onUse = { onEvent(GlyphEvent.SetSkin(GlyphSkin.fromId(id))) },
                        onRemove = { removing = style },
                    )
                }
            }

            // ── announcers ──────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_themes_announcers))
                Text(stringResource(R.string.tm_themes_announcers_desc), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                StyleRow(
                    name = stringResource(R.string.tm_themes_no_announcer),
                    detail = null,
                    inUse = themes.announcerId == null,
                    onUse = { onEvent(GlyphEvent.UseAnnouncer(null)) },
                    onRemove = null,
                )
                for (style in styles.announcers) {
                    StyleRow(
                        name = style.name,
                        detail = style.source,
                        inUse = themes.announcerId == style.id,
                        onUse = { onEvent(GlyphEvent.UseAnnouncer(style.id)) },
                        onRemove = { removing = style },
                    )
                }
            }
            // ── characters ──────────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.tm_themes_characters))
                Text(stringResource(R.string.tm_themes_characters_desc), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                StyleRow(
                    name = stringResource(R.string.tm_themes_no_character),
                    detail = null,
                    inUse = themes.characterId == null,
                    onUse = { onEvent(GlyphEvent.UseCharacter(null)) },
                    onRemove = null,
                )
                for (style in styles.characters) {
                    StyleRow(
                        name = style.name,
                        detail = style.source,
                        inUse = themes.characterId == style.id,
                        onUse = { onEvent(GlyphEvent.UseCharacter(style.id)) },
                        onRemove = { removing = style },
                    )
                }
                if (styles.characters.isNotEmpty() && styles.dances.isEmpty()) {
                    Text(
                        stringResource(R.string.tm_themes_dances_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                    )
                }
            }

            // ── dances characters share ────────────────────────────────
            if (styles.dances.isNotEmpty()) {
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.tm_themes_dances))
                    Text(stringResource(R.string.tm_themes_dances_desc), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    for (style in styles.dances) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = MonoDimens.spacingXs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
                        ) {
                            Text(style.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            GlyphButton(
                                text = stringResource(R.string.tm_themes_remove),
                                onClick = { removing = style },
                                onClickLabel = stringResource(R.string.tm_themes_remove_title, style.name),
                                height = 40.dp,
                            )
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.tm_themes_rights),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LocalBottomChromeInset.current + MonoDimens.spacingLg))
        }
    }

    if (browsing) {
        StepManiaArchiveBrowser(
            onDismiss = { browsing = false },
            onDownload = { url, name -> onEvent(GlyphEvent.ImportStyleUrl(url, name)) },
        )
    }

    removing?.let { style ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.tm_themes_remove_title, style.name)) },
            text = { Text(stringResource(R.string.tm_themes_remove_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(GlyphEvent.RemoveStyle(style))
                    removing = null
                }) { Text(stringResource(R.string.tm_themes_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.tm_themes_keep)) } },
        )
    }
}

/** One theme, noteskin or announcer: its name and where it came from, Use (or In use), and Remove. */
@Composable
private fun StyleRow(
    name: String,
    detail: String?,
    inUse: Boolean,
    onUse: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MonoDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val use = stringResource(R.string.tm_themes_use)
        val using = stringResource(R.string.tm_themes_in_use)
        GlyphButton(
            text = if (inUse) using else use,
            onClick = { if (!inUse) onUse() },
            onClickLabel = "$use $name",
            height = 40.dp,
        )
        if (onRemove != null) {
            GlyphButton(
                text = stringResource(R.string.tm_themes_remove),
                onClick = onRemove,
                onClickLabel = stringResource(R.string.tm_themes_remove_title, name),
                height = 40.dp,
            )
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
