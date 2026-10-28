package tf.monochrome.android.widget

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How the home-screen widget looks, set under Settings › Appearance › Home
 * screen widget. Every option stays flat: a colour at an opacity, never a
 * gradient (docs/ui-invariants.md, Home-screen widget).
 */
data class WidgetStyle(
    val glass: Glass = Glass.Light,
    /** The pane's opacity, in percent. */
    val opacity: Int = 18,
    val edge: Boolean = true,
    val showCover: Boolean = true,
    val showProgress: Boolean = true,
    val play: PlayButton = PlayButton.Solid,
    val text: Text = Text.Auto,
) {
    enum class Glass { Light, Dark, Album }
    enum class PlayButton { Solid, Glass }

    /** Auto picks white or dark from the glass over the wallpaper's colour. */
    enum class Text { Auto, White, Dark }

    companion object {
        val DEFAULT = WidgetStyle()
        const val MIN_OPACITY = 0
        const val MAX_OPACITY = 85
    }
}

/**
 * The colours a [WidgetStyle] works out to, for the widget and the preview
 * in Settings alike, so the two cannot drift apart.
 */
data class WidgetLook(
    val pane: Color,
    val edge: Color?,
    val ink: Color,
    val inkSoft: Color,
    val track: Color,
    val disc: Color,
    val onDisc: Color,
)

/**
 * On [WidgetStyle.Text.Auto] the text colour follows the glass: the pane is
 * worked out over [ground], the wallpaper's main colour ([wallpaperGround]),
 * and dark glass gets white text, light glass dark text. White text on light
 * glass over a light wallpaper is the failure this prevents; it happened
 * when the ground was a fixed dim colour and the owner's wallpaper was light.
 * White and Dark are the listener's override, for a wallpaper whose main
 * colour is not what sits behind the widget.
 */
fun resolveWidgetLook(style: WidgetStyle, coverColor: Int?, ground: Color = GROUND): WidgetLook {
    val base = when (style.glass) {
        WidgetStyle.Glass.Light -> Color.White
        WidgetStyle.Glass.Dark -> Color(0xFF0B0D10)
        WidgetStyle.Glass.Album -> coverColor?.let { Color(it).copy(alpha = 1f) } ?: Color.White
    }
    val alpha = style.opacity.coerceIn(WidgetStyle.MIN_OPACITY, WidgetStyle.MAX_OPACITY) / 100f
    val pane = base.copy(alpha = alpha)
    val seen = mix(ground.copy(alpha = 1f), base, alpha)
    val darkInk = when (style.text) {
        // 0.2 is where white and near-black text read equally well (about 4.2:1 each).
        WidgetStyle.Text.Auto -> seen.luminance() > 0.2f
        WidgetStyle.Text.White -> false
        WidgetStyle.Text.Dark -> true
    }
    val ink = if (darkInk) Color(0xFF14161A) else Color.White
    val glassPlay = style.play == WidgetStyle.PlayButton.Glass
    return WidgetLook(
        pane = pane,
        edge = if (style.edge) ink.copy(alpha = 0.35f) else null,
        ink = ink,
        inkSoft = ink.copy(alpha = 0.78f),
        track = ink.copy(alpha = 0.25f),
        disc = if (glassPlay) ink.copy(alpha = 0.18f) else ink,
        onDisc = if (glassPlay) ink else if (darkInk) Color.White else Color(0xFF14161A),
    )
}

/** What the glass is judged against when the wallpaper's colour cannot be read. */
internal val GROUND = Color(0xFF2A2840)

/**
 * The wallpaper's main colour, which Android hands any app without a
 * permission (`WallpaperManager.getWallpaperColors`, API 27). Null when it
 * cannot say (older Android, a live wallpaper that reports none, an OEM that
 * refuses); callers fall back to [GROUND]. The widget reads it on every
 * redraw, so a new wallpaper is picked up by the next song change.
 */
fun wallpaperGround(context: Context): Color? = wallpaperColors(context)?.firstOrNull()

/** The wallpaper's main, second and third colours, as many as it reports. */
fun wallpaperColors(context: Context): List<Color>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        val colors = WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
        listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
            .map { Color(it.toArgb()) }
    }.getOrNull()
}

private fun mix(under: Color, over: Color, t: Float) = Color(
    red = under.red + (over.red - under.red) * t,
    green = under.green + (over.green - under.green) * t,
    blue = under.blue + (over.blue - under.blue) * t,
)

/** The cover's average colour, for the Album glass: flat, so there is no gradient to pick. */
fun averageColor(bitmap: Bitmap): Int =
    Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)

/**
 * Where the style is kept: its own DataStore file, beside the snapshot's, for
 * the same reason (the widget runs outside Hilt). Not synced: it is about
 * this phone's home screen.
 */
private val Context.widgetStyleStore by preferencesDataStore(name = "widget_style")

object WidgetStyleStore {

    fun flow(context: Context): Flow<WidgetStyle> = context.widgetStyleStore.data.map { it.toStyle() }

    suspend fun read(context: Context): WidgetStyle = flow(context).first()

    suspend fun write(context: Context, style: WidgetStyle) {
        context.widgetStyleStore.edit { p ->
            p[GLASS] = style.glass.name
            p[OPACITY] = style.opacity
            p[EDGE] = style.edge
            p[COVER] = style.showCover
            p[PROGRESS] = style.showProgress
            p[PLAY] = style.play.name
            p[TEXT] = style.text.name
        }
    }

    private fun Preferences.toStyle(): WidgetStyle {
        val d = WidgetStyle.DEFAULT
        return WidgetStyle(
            glass = this[GLASS]?.let { n -> WidgetStyle.Glass.entries.firstOrNull { it.name == n } } ?: d.glass,
            opacity = (this[OPACITY] ?: d.opacity).coerceIn(WidgetStyle.MIN_OPACITY, WidgetStyle.MAX_OPACITY),
            edge = this[EDGE] ?: d.edge,
            showCover = this[COVER] ?: d.showCover,
            showProgress = this[PROGRESS] ?: d.showProgress,
            play = this[PLAY]?.let { n -> WidgetStyle.PlayButton.entries.firstOrNull { it.name == n } } ?: d.play,
            text = this[TEXT]?.let { n -> WidgetStyle.Text.entries.firstOrNull { it.name == n } } ?: d.text,
        )
    }

    private val GLASS = stringPreferencesKey("glass")
    private val OPACITY = intPreferencesKey("opacity")
    private val EDGE = booleanPreferencesKey("edge")
    private val COVER = booleanPreferencesKey("cover")
    private val PROGRESS = booleanPreferencesKey("progress")
    private val PLAY = stringPreferencesKey("play")
    private val TEXT = stringPreferencesKey("text")
}
