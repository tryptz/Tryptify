package tf.monochrome.android.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Builds the [FontFamily] for a stored font id, or null to mean "use the
 * built-in default" (Inter).
 *
 * Two id shapes, because there are two kinds of font:
 *  - `asset:fonts/x.ttf` — one Tryptify ships, read out of the APK.
 *  - an absolute path    — one the user imported into `filesDir/custom_fonts`.
 *
 * Both are declared as five weights over a single file. That reads oddly but is
 * correct for variable fonts, which every bundled font is: Compose derives the
 * `wght` variation axis from the declared [FontWeight], so one file genuinely
 * renders Light through Bold. For a static font the axis is ignored and the
 * platform synthesises the missing weights, which is the behaviour imported
 * fonts had before any of this existed.
 *
 * Returns null rather than throwing on a missing or unreadable font, so a
 * deleted file or a bad import falls back to the default instead of taking the
 * whole interface down.
 */
fun loadAppFontFamily(context: Context, fontId: String?): FontFamily? {
    if (fontId.isNullOrBlank()) return null
    return runCatching {
        if (BundledFonts.isBundled(fontId)) {
            val path = BundledFonts.assetPathOf(fontId)
            FontFamily(
                Font(path, context.assets, FontWeight.Light),
                Font(path, context.assets, FontWeight.Normal),
                Font(path, context.assets, FontWeight.Medium),
                Font(path, context.assets, FontWeight.SemiBold),
                Font(path, context.assets, FontWeight.Bold),
            )
        } else {
            val file = File(fontId)
            if (!file.exists()) return null
            FontFamily(
                Font(file, FontWeight.Light),
                Font(file, FontWeight.Normal),
                Font(file, FontWeight.Medium),
                Font(file, FontWeight.SemiBold),
                Font(file, FontWeight.Bold),
            )
        }
    }.getOrNull()
}
