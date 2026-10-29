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
import java.io.File
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
    val theme = GlyphStepArt.themeDir
    return remember(context, theme) {
        val assets = context.assets
        // An imported theme's own typeface, for the playfield's text: the
        // combo, the scores, the HUD. The menus keep the app's.
        val themeFont = theme?.let { File(it, "fonts/${tf.monochrome.android.glyph.style.StepManiaStyleBaker.FONT}") }
            ?.takeIf { it.isFile }
        if (themeFont != null) {
            runCatching {
                val family = FontFamily(Font(themeFont))
                return@remember GlyphStepFonts(condensed = family, numbers = family)
            }
        }
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

    /**
     * The imported StepMania theme in use (Trypt Mania › Themes), or null for
     * the app's own. Its art stands in for the app's by name, file by file,
     * and anything it has not is the app's, so a theme with only judgements
     * changes only the judgements.
     */
    var themeDir: File? by mutableStateOf(null)
        private set

    fun useTheme(dir: File?) {
        if (dir == themeDir) return
        themeDir = dir
        cache.clear()
    }

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
        val themed = themeDir?.let { File(it, name) }?.takeIf { it.isFile }
        runCatching {
            if (themed != null) {
                // A theme's judgement cell is often mostly empty around its
                // letters; trimmed, the word is sized, not the cell. Done here
                // too for themes imported before the import trimmed them.
                BitmapFactory.decodeFile(themed.path)
                    ?.let { if (name.startsWith("judgment_")) trimmedToInk(it) else it }
                    ?.asImageBitmap()
            } else {
                context.assets.open("$ROOT/$name").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }
        }.getOrNull()?.also { cache[name] = it }
    }

    /** The theme's own art by [name] only (its combo digits, label, background); null for the app's own theme. */
    suspend fun loadThemeOnly(name: String): ImageBitmap? {
        val file = themeDir?.let { File(it, name) }?.takeIf { it.isFile } ?: return null
        cache["theme:$name"]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()?.also { cache["theme:$name"] = it }
        }
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
    val theme = GlyphStepArt.themeDir
    var image by remember(name, theme) { mutableStateOf(GlyphStepArt.cached(name)) }
    LaunchedEffect(name, theme) {
        if (image == null) image = GlyphStepArt.load(context, name)
    }
    return image
}

/** The theme's combo digits: one bitmap font page, and which cell each digit is. */
class StepComboDigits(val page: ImageBitmap, val cols: Int, val rows: Int, val cells: Map<Char, Int>) {
    val cellWidth: Int get() = page.width / cols
    val cellHeight: Int get() = page.height / rows
}

/** The imported theme's combo digits, or null (the app's own, or a theme without them). */
@Composable
fun rememberStepComboDigits(): StepComboDigits? {
    val theme = GlyphStepArt.themeDir ?: return null
    var digits by remember(theme) { mutableStateOf<StepComboDigits?>(null) }
    LaunchedEffect(theme) {
        val page = GlyphStepArt.loadThemeOnly(tf.monochrome.android.glyph.style.StepManiaStyleBaker.COMBO_DIGITS) ?: return@LaunchedEffect
        val info = withContext(Dispatchers.IO) {
            runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<tf.monochrome.android.glyph.style.StepManiaStyleBaker.ComboDigits>(
                        File(theme, tf.monochrome.android.glyph.style.StepManiaStyleBaker.COMBO_DIGITS_INFO).readText(),
                    )
            }.getOrNull()
        } ?: return@LaunchedEffect
        digits = StepComboDigits(page, info.cols, info.rows, info.cells.mapKeys { it.key.first() })
    }
    return digits
}

/** One of the imported theme's own images by [name], or null with the app's own theme. */
@Composable
fun rememberStepThemeArt(name: String): ImageBitmap? {
    val theme = GlyphStepArt.themeDir ?: return null
    var image by remember(theme, name) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(theme, name) { image = GlyphStepArt.loadThemeOnly(name) }
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

/** [art] cut to its visible pixels with a little room; as it is when all clear. */
private fun trimmedToInk(art: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = art.width
    val h = art.height
    val pixels = IntArray(w * h)
    art.getPixels(pixels, 0, w, 0, 0, w, h)
    var left = w
    var top = h
    var right = -1
    var bottom = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((pixels[y * w + x] ushr 24) > 8) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return art
    val pad = maxOf(1, minOf(w, h) / 32)
    left = (left - pad).coerceAtLeast(0)
    top = (top - pad).coerceAtLeast(0)
    right = (right + pad).coerceAtMost(w - 1)
    bottom = (bottom + pad).coerceAtMost(h - 1)
    return android.graphics.Bitmap.createBitmap(art, left, top, right - left + 1, bottom - top + 1)
}
