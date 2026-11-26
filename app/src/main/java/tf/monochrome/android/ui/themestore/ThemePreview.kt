package tf.monochrome.android.ui.themestore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePart
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.LocalVisualLinks
import tf.monochrome.android.ui.player.rememberVisualLinkDriver
import tf.monochrome.android.ui.settings.PlayerChromePreview
import tf.monochrome.android.ui.settings.StudioPreview
import tf.monochrome.android.ui.settings.UiPanelsPreview
import tf.monochrome.android.ui.theme.InterFontFamily
import tf.monochrome.android.ui.theme.MATERIAL_YOU_KEY
import tf.monochrome.android.ui.theme.Paper
import tf.monochrome.android.ui.theme.buildTypography
import tf.monochrome.android.ui.theme.customScheme
import tf.monochrome.android.ui.theme.getColorScheme
import tf.monochrome.android.ui.theme.lightVariantOf
import tf.monochrome.android.ui.theme.loadAppFontFamily

/** What each part is called, and its icon, wherever the store lists parts. */
@Composable
fun partLabel(part: ThemePart): String = stringResource(
    when (part) {
        ThemePart.COLOURS -> R.string.theme_part_colours
        ThemePart.LYRICS -> R.string.theme_part_lyrics
        ThemePart.PLAYER_GLASS -> R.string.theme_part_player_glass
        ThemePart.PANELS -> R.string.theme_part_panels
        ThemePart.FONT -> R.string.theme_part_font
        ThemePart.VISUALIZER -> R.string.theme_part_visualizer
        ThemePart.MAPS -> R.string.theme_part_maps
        ThemePart.LINKS -> R.string.theme_part_links
        ThemePart.CRT -> R.string.theme_part_crt
    },
)

@Composable
fun partDescription(part: ThemePart): String = stringResource(
    when (part) {
        ThemePart.COLOURS -> R.string.theme_part_colours_desc
        ThemePart.LYRICS -> R.string.theme_part_lyrics_desc
        ThemePart.PLAYER_GLASS -> R.string.theme_part_player_glass_desc
        ThemePart.PANELS -> R.string.theme_part_panels_desc
        ThemePart.FONT -> R.string.theme_part_font_desc
        ThemePart.VISUALIZER -> R.string.theme_part_visualizer_desc
        ThemePart.MAPS -> R.string.theme_part_maps_desc
        ThemePart.LINKS -> R.string.theme_part_links_desc
        ThemePart.CRT -> R.string.theme_part_crt_desc
    },
)

fun partIcon(part: ThemePart): ImageVector = when (part) {
    ThemePart.COLOURS -> Icons.Default.Palette
    ThemePart.LYRICS -> Icons.Default.Lyrics
    ThemePart.PLAYER_GLASS -> Icons.Default.BlurOn
    ThemePart.PANELS -> Icons.Default.ViewQuilt
    ThemePart.FONT -> Icons.Default.TextFields
    ThemePart.VISUALIZER -> Icons.Default.GraphicEq
    ThemePart.MAPS -> Icons.Default.Public
    ThemePart.LINKS -> Icons.Default.MusicNote
    ThemePart.CRT -> Icons.Default.Tv
}

/**
 * [content] in [pack]'s colours, font and glass, and nowhere else: the
 * preview of a theme is the app's own components under the pack's settings,
 * not a picture of them. Parts the pack does not carry are the viewer's own,
 * which is also what they keep after importing it.
 */
@Composable
fun PackThemeScope(pack: ThemePack, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colours = pack.colours
    val scheme = remember(colours, dark) {
        when {
            colours == null -> null
            colours.custom -> customScheme(Color(colours.accent), Color(colours.background))
            else -> {
                val name = when (colours.theme) {
                    "system" -> if (dark) "monochrome_dark" else lightVariantOf("monochrome")
                    // The publisher's wallpaper is not here to read.
                    MATERIAL_YOU_KEY -> "monochrome_dark"
                    else -> colours.theme
                }
                getColorScheme(name, if (colours.paper == "warm") Paper.Warm else Paper.Crisp)
            }
        }
    }
    val font = pack.font
    val typography = remember(font) {
        if (font == null) null else buildTypography(loadAppFontFamily(context, font) ?: InterFontFamily, 1f)
    }
    MaterialTheme(
        colorScheme = scheme ?: MaterialTheme.colorScheme,
        typography = typography ?: MaterialTheme.typography,
    ) {
        CompositionLocalProvider(
            LocalMiniPlayerGlass provides (pack.panelGlass ?: LocalMiniPlayerGlass.current),
            LocalPlayerGlass provides (pack.playerGlass ?: LocalPlayerGlass.current),
        ) {
            content()
        }
    }
}

/** The lyrics face: tall enough for the sung line and the lines coming under it. */
private val LYRICS_FACE_HEIGHT = 300.dp

/** Which face of a pack the preview shows. */
enum class PreviewFace { PANELS, PLAYER, LYRICS }

/**
 * A pack drawn by the real renderers: the UI panels over a sample page, the
 * player's transport and dock, or the lyrics with their glass, glow and rays
 * — with its music links running if a song is playing.
 */
@Composable
fun PackPreview(
    pack: ThemePack,
    face: PreviewFace,
    analyzer: SpectrumAnalyzerTap,
    modifier: Modifier = Modifier,
) {
    PackThemeScope(pack) {
        val haze = rememberHazeState()
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(if (face == PreviewFace.LYRICS) LYRICS_FACE_HEIGHT else 312.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            when (face) {
                PreviewFace.PANELS -> UiPanelsPreview(
                    glass = LocalMiniPlayerGlass.current,
                    hazeState = haze,
                    page = MaterialTheme.colorScheme.background,
                    track = null,
                )
                PreviewFace.PLAYER -> PlayerChromePreview(glass = LocalPlayerGlass.current, hazeState = haze)
                PreviewFace.LYRICS -> {
                    val fx = pack.lyrics ?: tf.monochrome.android.domain.model.LyricsFxSettings.DEFAULT
                    val live = rememberVisualLinkDriver(
                        tap = analyzer,
                        links = pack.links.orEmpty(),
                        active = true,
                        delayMs = tf.monochrome.android.ui.player.LocalOutputSyncMs.current,
                    )
                    val position = remember { MutableStateFlow(0L) }
                    CompositionLocalProvider(LocalVisualLinks provides live) {
                        // Made-up lyrics sung by the player's own lyric
                        // view, several lines tall: the pack's intro, word
                        // highlight, rays and glides, not a single sample line.
                        StudioPreview(fx, null, position, analyzer, generated = true, height = LYRICS_FACE_HEIGHT)
                    }
                }
            }
        }
    }
}

/**
 * A store card's picture: the pack's page colour with its accent on it,
 * painted from the two colours the store lists, so a card needs no download.
 */
@Composable
fun PackSwatch(accent: Int, background: Int, modifier: Modifier = Modifier) {
    val page = if (background != 0) Color(background) else MaterialTheme.colorScheme.background
    val tint = if (accent != 0) Color(accent) else MaterialTheme.colorScheme.primary
    val ink = if (page.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(page)
            .border(1.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(tint))
            Box(Modifier.fillMaxWidth(0.8f).height(6.dp).clip(CircleShape).background(ink.copy(alpha = 0.5f)))
            Box(Modifier.fillMaxWidth(0.55f).height(6.dp).clip(CircleShape).background(ink.copy(alpha = 0.3f)))
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(width = 36.dp, height = 14.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.85f)),
        )
    }
}

/** The parts a pack holds, as a row of small icons with their names read out. */
@Composable
fun PartIcons(parts: Set<ThemePart>, modifier: Modifier = Modifier) {
    val names = ThemePart.entries.filter { it in parts }
    val description = names.map { partLabel(it) }.joinToString(", ")
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEach { part ->
            androidx.compose.material3.Icon(
                partIcon(part),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (names.isEmpty()) Spacer(Modifier.width(1.dp))
    }
}

@Composable
internal fun PreviewFaceLabel(face: PreviewFace): String = stringResource(
    when (face) {
        PreviewFace.PANELS -> R.string.theme_preview_panels
        PreviewFace.PLAYER -> R.string.theme_preview_player
        PreviewFace.LYRICS -> R.string.theme_preview_lyrics
    },
)

@Composable
internal fun SmallLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}
