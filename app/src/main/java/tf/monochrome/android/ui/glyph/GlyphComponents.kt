// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import tf.monochrome.android.glyph.asset.GlyphAssetId
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphIcon
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog

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
    tint: Color = GlyphTheme.Muted,
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
 * An icon button.
 *
 * The artwork is 24 dp and the touch target is 48, as the pack's integration
 * notes require. [label] is mandatory rather than nullable: every control in
 * this mode is an unlabelled pictogram, and one without a name is unusable with
 * a screen reader.
 */
@Composable
fun GlyphIconButton(
    icon: GlyphIcon,
    label: String,
    assets: GlyphAssetRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val tint = when {
        !enabled -> GlyphTheme.Muted
        active -> GlyphTheme.Positive
        else -> GlyphTheme.Paper
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(GlyphTheme.Grid))
            .background(if (active) GlyphTheme.InkRaised else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics {
                // State is spoken, not left to the colour. "Metronome, on".
                contentDescription = if (active) "$label, on" else label
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphImage(
            id = GlyphAssetCatalog.icon(icon),
            assets = assets,
            // Named by the parent; a second description would be read twice.
            contentDescription = null,
            size = 24.dp,
            tint = tint,
        )
    }
}

/**
 * A flat technical panel.
 *
 * No glass and no blur. Gameplay surfaces are read at speed while moving, and
 * a translucent panel over a moving playfield costs legibility for decoration.
 * Menus elsewhere in the app keep their glass; this mode's panels are panels.
 */
@Composable
fun GlyphPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = GlyphTheme.Grid * 2,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(GlyphTheme.PanelCorner))
            .background(GlyphTheme.InkPanel)
            .border(
                width = GlyphTheme.PanelBorder,
                color = GlyphTheme.Hairline,
                shape = RoundedCornerShape(GlyphTheme.PanelCorner),
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A label above a value.
 *
 * The value is monospaced so a digit changing does not re-flow the number, and
 * the pair is read as one thing by a screen reader rather than as two stray
 * fragments ("Combo" … "148").
 */
@Composable
fun GlyphStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = GlyphTheme.Paper,
    typography: GlyphTypography,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: $value"
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = typography.label,
            color = GlyphTheme.Muted,
            maxLines = 1,
        )
        Text(
            text = value,
            style = typography.title,
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
 * "roughly this much" and the numbers here are the point.
 */
@Composable
fun GlyphMeter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    description: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(GlyphTheme.InkRaised)
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

/** A row of chips where exactly one is selected. */
@Composable
fun <T> GlyphChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    typography: GlyphTypography,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid),
    ) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .sizeIn(minHeight = 40.dp)
                    .clip(RoundedCornerShape(GlyphTheme.Grid))
                    .background(if (isSelected) GlyphTheme.Paper else GlyphTheme.InkRaised)
                    .clickable(onClick = { onSelect(option) })
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics {
                        // Selection is spoken, never inferred from the fill.
                        contentDescription =
                            if (isSelected) "${label(option)}, selected" else label(option)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = typography.mono,
                    color = if (isSelected) GlyphTheme.Ink else GlyphTheme.Paper,
                    maxLines = 1,
                )
            }
        }
    }
}
