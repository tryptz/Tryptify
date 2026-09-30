package tf.monochrome.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * How a catalog presents itself on a pill: name, colour and mark.
 *
 * [light] and [dark] are the same brand hue at two lightnesses. The official
 * colours are tuned for white backgrounds; as text on this app's dark surfaces
 * Qobuz teal and Deezer purple are hard to read, so dark themes get a lifted
 * shade. A null colour means "the theme's own" — TIDAL's brand is black and
 * white, and the non-catalog sources have no brand at all.
 */
data class SourceBrand(
    val label: String,
    val light: Color?,
    val dark: Color?,
    @DrawableRes val logo: Int? = null,
    val icon: ImageVector? = null,
    // Monochrome marks are tinted with the text colour; coloured marks draw as-is.
    val tintLogo: Boolean = false,
)

// The Qobuz and Deezer marks are PNGs cut from the brands' own artwork
// (drawable-nodpi, 192px): the Qobuz record "Q", and Deezer's heart. Qobuz's
// colour is the teal at the top of its brand gradient; Deezer's is the heart's.
fun SourceType.brand(): SourceBrand = when (this) {
    SourceType.API -> SourceBrand("TIDAL", null, null, logo = R.drawable.logo_tidal, tintLogo = true)
    SourceType.QOBUZ -> SourceBrand("Qobuz", Color(0xFF0F6F78), Color(0xFF4FC3C4), logo = R.drawable.logo_qobuz)
    SourceType.APPLE -> SourceBrand("Apple Music", Color(0xFFFA243C), Color(0xFFFF6B7D), logo = R.drawable.logo_apple_music)
    SourceType.DEEZER -> SourceBrand("Deezer", Color(0xFFA238FF), Color(0xFFC98BFF), logo = R.drawable.logo_deezer)
    SourceType.LOCAL -> SourceBrand("Local", null, null, icon = Icons.Default.PhoneAndroid)
    SourceType.COLLECTION -> SourceBrand("Collection", null, null, icon = Icons.Default.LibraryMusic)
    SourceType.LIVE_RADIO -> SourceBrand("Live", null, null, icon = Icons.Default.Radio)
}

/** The brand colour for the current theme, or the theme's own when the brand has none. */
@Composable
fun SourceBrand.color(): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return (if (isDark) dark else light) ?: MaterialTheme.colorScheme.onSurface
}

/** The brand mark at [size]: the catalog's logo, or a stand-in icon for non-catalog sources. */
@Composable
fun SourceBrandMark(brand: SourceBrand, size: Dp, tint: Color = brand.color()) {
    when {
        brand.logo != null -> Icon(
            painter = painterResource(brand.logo),
            contentDescription = null,
            tint = if (brand.tintLogo) tint else Color.Unspecified,
            modifier = Modifier.size(size),
        )
        brand.icon != null -> Icon(
            imageVector = brand.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/** Small "where this result comes from" pill: brand mark plus name, in the brand colour. */
@Composable
fun SourcePill(source: SourceType, modifier: Modifier = Modifier) {
    val brand = source.brand()
    val color = brand.color()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = MonoDimens.badgePaddingV, bottom = MonoDimens.badgePaddingV),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceBrandMark(brand, size = 11.dp, tint = color)
            Text(text = brand.label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
