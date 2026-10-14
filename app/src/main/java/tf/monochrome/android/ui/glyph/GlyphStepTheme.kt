// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.glyph.engine.GlyphJudgement

/**
 * StepMania 5's default theme, as Trypt Mania wears it: its typefaces and the
 * judgement, grade and full-combo art from Themes/default, cut into
 * assets/stepmania/theme by tools/stepmania_skin/build_theme.py.
 *
 * The theme draws all its text from bitmaps of Roboto and Roboto Condensed;
 * the typefaces themselves are bundled instead, so the same letters stay sharp
 * at any size. Where StepMania uses each:
 * - [condensed] bold italic: screen titles and the option rows' headings
 *   ("OptionRow title", "Common Italic Condensed").
 * - [condensed]: option values and labels ("Common Condensed").
 * - [numbers]: Roboto Bold, the combo and every score ("Combo Numbers").
 */
class GlyphStepFonts(val condensed: FontFamily, val numbers: FontFamily) {

    val screenTitle = TextStyle(
        fontFamily = condensed, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
        fontSize = 26.sp, letterSpacing = 0.5.sp,
    )
    val rowTitle = TextStyle(
        fontFamily = condensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.5.sp,
    )
    val value = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Medium, fontSize = 17.sp)
    val label = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val combo = TextStyle(fontFamily = numbers, fontWeight = FontWeight.Bold, fontSize = 44.sp)
    val score = TextStyle(fontFamily = numbers, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    val bigScore = TextStyle(fontFamily = numbers, fontWeight = FontWeight.Bold, fontSize = 46.sp)
    val count = TextStyle(fontFamily = numbers, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    val banner = TextStyle(
        fontFamily = condensed, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
        fontSize = 56.sp, letterSpacing = 2.sp,
    )
}

@Composable
fun rememberStepFonts(): GlyphStepFonts {
    val context = LocalContext.current
    return remember(context) {
        val assets = context.assets
        runCatching {
            GlyphStepFonts(
                condensed = FontFamily(
                    Font("$FONTS/stepmania_condensed_medium.ttf", assets, FontWeight.Normal),
                    Font("$FONTS/stepmania_condensed_medium.ttf", assets, FontWeight.Medium),
                    Font("$FONTS/stepmania_condensed_bold.ttf", assets, FontWeight.Bold),
                    Font("$FONTS/stepmania_condensed_bold_italic.ttf", assets, FontWeight.Bold, FontStyle.Italic),
                ),
                numbers = FontFamily(
                    Font("$FONTS/stepmania_medium.ttf", assets, FontWeight.Medium),
                    Font("$FONTS/stepmania_bold.ttf", assets, FontWeight.Bold),
                ),
            )
        }.getOrElse { GlyphStepFonts(FontFamily.SansSerif, FontFamily.SansSerif) }
    }
}

/**
 * The theme's images, decoded once and kept: two dozen small PNGs that every
 * judgement and every results screen draw.
 */
object GlyphStepArt {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun judgement(judgement: GlyphJudgement, early: Boolean): String {
        val window = when (judgement) {
            GlyphJudgement.MARVELOUS -> "w1"
            GlyphJudgement.PERFECT -> "w2"
            GlyphJudgement.GREAT -> "w3"
            GlyphJudgement.GOOD -> "w4"
            GlyphJudgement.BOO -> "w5"
            GlyphJudgement.MISS -> "miss"
        }
        return "judgment_${window}_${if (early) "early" else "late"}.png"
    }

    fun cached(name: String): ImageBitmap? = cache[name]

    suspend fun load(context: Context, name: String): ImageBitmap? = cache[name] ?: withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("$ROOT/$name").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()?.also { cache[name] = it }
    }

    /** Every judgement sprite, so the first hit of a run is never drawn late. */
    suspend fun preloadJudgements(context: Context) {
        for (judgement in GlyphJudgement.entries) {
            load(context, judgement(judgement, early = true))
            load(context, judgement(judgement, early = false))
        }
    }

    private const val ROOT = "stepmania/theme"
}

/** [name] from the theme, or null until it has loaded. */
@Composable
fun rememberStepArt(name: String?): ImageBitmap? {
    if (name == null) return null
    val context = LocalContext.current
    var image by remember(name) { mutableStateOf(GlyphStepArt.cached(name)) }
    LaunchedEffect(name) {
        if (image == null) image = GlyphStepArt.load(context, name)
    }
    return image
}

/** StepMania's judgement colours: the default theme's JudgmentLine_W1..Miss. */
fun stepJudgementColour(judgement: GlyphJudgement): Color = when (judgement) {
    GlyphJudgement.MARVELOUS -> Color(0xFFA0DBF1)
    GlyphJudgement.PERFECT -> Color(0xFFF1E4A2)
    GlyphJudgement.GREAT -> Color(0xFFABE39B)
    GlyphJudgement.GOOD -> Color(0xFF86ACD6)
    GlyphJudgement.BOO -> Color(0xFF958CD6)
    GlyphJudgement.MISS -> Color(0xFFF97E7E)
}

/** JudgmentLine_Held and JudgmentLine_MaxCombo. */
val StepHeldColour = Color(0xFFFFFFFF)
val StepMaxComboColour = Color(0xFFFFC600)

/** The theme's words for each window, as its judgement sprites spell them. */
fun stepJudgementName(judgement: GlyphJudgement): String = when (judgement) {
    GlyphJudgement.MARVELOUS -> "Flawless"
    GlyphJudgement.PERFECT -> "Perfect"
    GlyphJudgement.GREAT -> "Great"
    GlyphJudgement.GOOD -> "Good"
    GlyphJudgement.BOO -> "Bad"
    GlyphJudgement.MISS -> "Miss"
}

private const val FONTS = "stepmania/theme/fonts"

/**
 * StepMania 5 default theme's Player 1 colour, for the life bar on the
 * playfield. The playfield is the mode's own, so it may carry StepMania's
 * colours; the menus take the app's theme.
 */
val StepPlayerColour = Color(0xFF4B82DC)
