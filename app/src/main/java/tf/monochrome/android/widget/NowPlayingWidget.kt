package tf.monochrome.android.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import tf.monochrome.android.R
import tf.monochrome.android.ui.main.MainActivity

/**
 * Home-screen now-playing widget styled after the player. Glance/RemoteViews can't
 * run the player's liquid-glass shader, so the look is approximated: a dark,
 * near-opaque rounded scrim (the player's BackgroundBlack), a thin album-accent
 * progress line on top (the flat cousin of the glass "thermometer"), rounded album
 * art, white/grey two-line track text, and accent-tinted transport controls with a
 * filled accent play/pause disc — mirroring the MiniPlayer bar.
 */
class NowPlayingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readNowPlaying(context)
        val art = snapshot.artworkUri?.let { loadArtwork(context, it) }
        val accent = art?.let { accentFrom(it) } ?: WidgetColors.AccentFallback
        provideContent { WidgetContent(snapshot, art, accent) }
    }

    /** Sample render for the widget picker (no live session yet). */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { WidgetContent(NowPlayingSnapshot.PREVIEW, null, WidgetColors.AccentFallback) }
    }
}

@Composable
private fun WidgetContent(snapshot: NowPlayingSnapshot, art: Bitmap?, accent: Color) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(ColorProvider(WidgetColors.Scrim)),
    ) {
        // Top-edge progress line — accent fill over a faint white track.
        LinearProgressIndicator(
            progress = snapshot.progress,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = ColorProvider(accent),
            backgroundColor = ColorProvider(WidgetColors.ProgressTrack),
        )
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(art)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = snapshot.title.ifBlank { "Nothing playing" },
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(WidgetColors.Title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                if (snapshot.artist.isNotBlank()) {
                    Text(
                        text = snapshot.artist,
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(WidgetColors.Artist), fontSize = 12.sp),
                    )
                }
            }
            Spacer(GlanceModifier.width(6.dp))
            ControlGlyph(R.drawable.ic_glass_skip_previous, "Previous", accent, actionRunCallback<PreviousAction>())
            PlayPauseDisc(snapshot.isPlaying, accent)
            ControlGlyph(R.drawable.ic_glass_skip_next, "Next", accent, actionRunCallback<NextAction>())
        }
    }
}

/** Rounded album cover; tapping it opens the app. Falls back to a dark tile. */
@Composable
private fun AlbumArt(art: Bitmap?) {
    Box(
        modifier = GlanceModifier
            .size(52.dp)
            .cornerRadius(8.dp)
            .background(ColorProvider(WidgetColors.ArtPlaceholder))
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize().cornerRadius(8.dp),
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_glass_play),
                colorFilter = ColorFilter.tint(ColorProvider(WidgetColors.Artist)),
                contentDescription = null,
                modifier = GlanceModifier.size(22.dp),
            )
        }
    }
}

/** A 44dp tap target with a ~26dp accent-tinted transport glyph. */
@Composable
private fun ControlGlyph(resId: Int, desc: String, tint: Color, onClick: Action) {
    Box(
        modifier = GlanceModifier.size(44.dp).cornerRadius(22.dp).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(resId),
            colorFilter = ColorFilter.tint(ColorProvider(tint)),
            contentDescription = desc,
            modifier = GlanceModifier.size(26.dp),
        )
    }
}

/** The filled accent play/pause disc, echoing the player's main transport button. */
@Composable
private fun PlayPauseDisc(isPlaying: Boolean, accent: Color) {
    Box(
        modifier = GlanceModifier
            .size(46.dp)
            .cornerRadius(23.dp)
            .background(ColorProvider(accent))
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_glass_pause else R.drawable.ic_glass_play),
            colorFilter = ColorFilter.tint(ColorProvider(onAccent(accent))),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size(22.dp),
        )
    }
}

/** Dark glyph on a light accent, white on a dark accent — always legible on the disc. */
private fun onAccent(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color(0xFF07100E) else Color(0xFFFFFFFF)

/** Widget palette, derived from the player's design tokens (PlayerDesignTokens / Color.kt). */
object WidgetColors {
    /** ~90%-opaque BackgroundBlack — dense enough to keep white text legible over any wallpaper. */
    val Scrim = Color(0xE6050706)
    val Title = Color(0xFFFFFFFF)          // onSurface / MonoWhite
    val Artist = Color(0xFFB0B0B0)         // onSurfaceVariant / MonoTextSecondary
    val ProgressTrack = Color(0x38FFFFFF)  // ~22% white — the inactive tube colour
    val ArtPlaceholder = Color(0xFF1A1D1C)
    /** PlayerGlowBlue — the player's accent fallback when a cover has no vibrant colour. */
    val AccentFallback = Color(0xFF7EB6FF)
}
