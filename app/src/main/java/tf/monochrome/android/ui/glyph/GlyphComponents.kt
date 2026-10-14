// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.glyph.asset.GlyphAssetId
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * A pack glyph, rasterized on demand.
 *
 * For menus only. It suspends to load, which is exactly what the playfield must
 * never do — there the same repository is prewarmed and read synchronously. The
 * split is deliberate: menu artwork is a handful of icons at human speed, and
 * paying for a rasterization when one first appears is cheaper than warming
 * every icon in the pack up front.
 */
@Composable
fun GlyphImage(
    id: GlyphAssetId,
    assets: GlyphAssetRepository,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = GlyphTheme.Paper,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    var image by remember(id, sizePx, tint) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(id, sizePx, tint) {
        image = assets.load(id, sizePx, sizePx, tint)
    }

    val current = image
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        // Holds the layout while the raster is produced, so a row does not jump
        // when its icon arrives. Also the permanent state for a missing asset.
        Box(modifier = modifier.size(size))
    }
}

/**
 * A wide piece of decor, rasterized to the width it is given.
 *
 * Separate from [GlyphImage] because the decor artwork is not square — a
 * timeline ruler is 320 × 32 — and rasterizing it into a square box would
 * letterbox it into a fraction of the space with the padding the pack asks be
 * preserved turned into empty margin.
 */
@Composable
fun GlyphImageStrip(
    id: GlyphAssetId,
    assets: GlyphAssetRepository,
    aspect: Float,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    var image by remember(id, tint) { mutableStateOf<ImageBitmap?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(id, widthPx, tint) {
        if (widthPx > 0) {
            image = assets.load(id, widthPx, (widthPx / aspect).toInt().coerceAtLeast(1), tint)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .onSizeChanged { widthPx = it.width },
    ) {
        val current = image
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

/**
 * A card on a menu page: the app's own row-and-card glass ([liquidGlass]),
 * so it follows the theme the way a track row does, light themes included.
 *
 * For the menus only — Training Ground's readouts and the results. The
 * playfield never has a panel over it; the pause overlay is a `GlassPanel`
 * because it floats.
 */
@Composable
fun GlyphPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = MonoDimens.spacingLg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .liquidGlass(shape = MonoDimens.shapeLg)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A label above a value.
 *
 * The pair is read as one thing by a screen reader rather than as two stray
 * fragments ("Combo" … "148"). The colours and styles default to the menu's;
 * the HUD over the playfield passes its own, and a readout that changes while
 * it is read passes the monospaced [GlyphTypography] so a digit changing does
 * not re-flow the number.
 */
@Composable
fun GlyphStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    upperCaseLabel: Boolean = false,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: $value"
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (upperCaseLabel) label.uppercase() else label,
            style = labelStyle,
            color = labelColor,
            maxLines = 1,
        )
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A horizontal meter.
 *
 * Carries its reading as text as well as width, because a bar alone says
 * "roughly this much" and the numbers here are the point. The track defaults
 * to a faint wash of the content colour, so it reads on the page and on glass
 * alike; the HUD passes the playfield's own.
 */
@Composable
fun GlyphMeter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    description: String? = null,
    trackColor: Color = LocalContentColor.current.copy(alpha = 0.16f),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

/**
 * A row of chips where exactly one is selected, as the app's glass
 * ([GlassChoiceChip]): the selected one carries the accent rim and label, and
 * says it is selected to a screen reader rather than leaving it to the fill.
 *
 * Scrolls horizontally rather than clipping. Five difficulty chips with their
 * meters ("Challenge 14") need about 450 dp, and a phone lane gives them ~358 —
 * so the hardest tier fell off the right edge with nothing to say it was there.
 * The same happens to the six CMod values on a narrow device.
 */
@Composable
fun <T> GlyphChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
    ) {
        for (option in options) {
            GlassChoiceChip(
                label = label(option),
                selected = option == selected,
                accent = accent,
                onClick = { onSelect(option) },
                height = 40.dp,
            )
        }
    }
}

/**
 * A button of the app's glass: a [GlassPill], the panel recipe, swelling under
 * the finger. [primary] marks the one action a panel is for — its label and
 * icon take the accent — and is never the only difference a reader is given:
 * the label says what it does.
 *
 * Inside a pane of glass the colours are the glass's ink, not the theme's, so
 * they are read from [MaterialTheme] inside the pill rather than passed in.
 */
@Composable
fun GlyphButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    onClickLabel: String? = null,
    hazeState: HazeState? = LocalGlassBackdrop.current,
) {
    GlassPill(
        onClick = onClick,
        hazeState = hazeState,
        modifier = modifier,
        height = height,
        enabled = enabled,
        onClickLabel = onClickLabel,
    ) {
        val scheme = MaterialTheme.colorScheme
        val ink = when {
            !enabled -> scheme.onSurface.copy(alpha = 0.38f)
            primary -> scheme.primary
            else -> scheme.onSurface
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = MonoDimens.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A small caption over a group of controls, in the menu's quieter ink. */
@Composable
fun GlyphSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
