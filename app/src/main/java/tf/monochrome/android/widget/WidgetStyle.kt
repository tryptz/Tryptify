package tf.monochrome.android.widget

import android.content.Context
import android.graphics.Bitmap
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
) {
    enum class Glass { Light, Dark, Album }
    enum class PlayButton { Solid, Glass }

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
 * The text colour follows the glass, not a setting: the pane is worked out
 * over [GROUND], a dim home screen like the owner's, and dark glass gets
 * white text, light glass dark text. A white pane at 70% with white text on
 * it is the failure this prevents.
 */
fun resolveWidgetLook(style: WidgetStyle, coverColor: Int?): WidgetLook {
    val base = when (style.glass) {
        WidgetStyle.Glass.Light -> Color.White
        WidgetStyle.Glass.Dark -> Color(0xFF0B0D10)
        WidgetStyle.Glass.Album -> coverColor?.let { Color(it).copy(alpha = 1f) } ?: Color.White
    }
    val alpha = style.opacity.coerceIn(WidgetStyle.MIN_OPACITY, WidgetStyle.MAX_OPACITY) / 100f
    val pane = base.copy(alpha = alpha)
    val seen = mix(GROUND, base, alpha)
    // 0.2 is where white and near-black text read equally well (about 4.2:1 each).
    val darkInk = seen.luminance() > 0.2f
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

/** A dim wallpaper's colour; what the glass is judged against. */
internal val GROUND = Color(0xFF2A2840)

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
        )
    }

    private val GLASS = stringPreferencesKey("glass")
    private val OPACITY = intPreferencesKey("opacity")
    private val EDGE = booleanPreferencesKey("edge")
    private val COVER = booleanPreferencesKey("cover")
    private val PROGRESS = booleanPreferencesKey("progress")
    private val PLAY = stringPreferencesKey("play")
}
