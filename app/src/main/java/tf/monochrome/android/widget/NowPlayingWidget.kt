package tf.monochrome.android.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
 * The home-screen now-playing widget, in flat glass.
 *
 * One see-through pane with a hairline edge, the way ColorOS draws its own
 * glass: the wallpaper shows through it. On it, the sharp cover, the text,
 * the transport glyphs and a play disc. No gradients and no blurred cover:
 * a frosted-cover version was tried and rejected.
 *
 * The look is the listener's (Settings › Appearance › Home screen widget,
 * [WidgetStyle]): the glass's colour and opacity, the edge, the cover, the
 * progress line and the play disc. [resolveWidgetLook] turns that into
 * colours, and picks the text colour so it reads on the chosen glass.
 *
 * The picker offers it three times (small 2x2, medium 4x2, large 4x4; see
 * the receivers), but it is one widget: every entry resizes, and the layout
 * follows the size it is given. The launcher picks the largest layout that
 * fits; each is laid out to fit its own minimum.
 *
 * The corner is the launcher's own (`widget_glass_radius`, the system's from
 * Android 12), so on ColorOS, One UI or Pixel it rounds like the widgets
 * around it, and `appWidgetBackground` lets the launcher clip and animate it.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, BAR, SQUARE, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = NowPlayingSnapshotStore.read(context)
        val art = snapshot.artworkUri?.let { loadArtwork(context, it) }
        val coverColor = art?.let { averageColor(it) }
        val saved = WidgetStyleStore.read(context)
        provideContent {
            // Collected, not read once: a change in Settings redraws a widget
            // whose session is still open.
            val style by WidgetStyleStore.flow(context).collectAsState(saved)
            val parts = WidgetParts(
                look = resolveWidgetLook(style, coverColor),
                art = art,
                showCover = style.showCover,
                showProgress = style.showProgress,
            )
            GlassWidget(snapshot, parts)
        }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 50.dp)
        val BAR = DpSize(260.dp, 64.dp)
        val SQUARE = DpSize(120.dp, 120.dp)
        val MEDIUM = DpSize(280.dp, 140.dp)
        val LARGE = DpSize(280.dp, 260.dp)
    }
}

/** What the layouts draw with: the colours, and which parts the listener turned on. */
private class WidgetParts(
    val look: WidgetLook,
    val art: Bitmap?,
    val showCover: Boolean,
    val showProgress: Boolean,
)

@Composable
private fun GlassWidget(snapshot: NowPlayingSnapshot, parts: WidgetParts) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(R.dimen.widget_glass_radius)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        // The pane and its edge are tinted shapes rather than a background
        // colour, so they round on every Android version.
        Image(
            provider = ImageProvider(R.drawable.widget_glass_fill),
            colorFilter = ColorFilter.tint(ColorProvider(parts.look.pane)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize(),
        )
        parts.look.edge?.let { edge ->
            Image(
                provider = ImageProvider(R.drawable.widget_glass_edge),
                colorFilter = ColorFilter.tint(ColorProvider(edge)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
        when (LocalSize.current) {
            NowPlayingWidget.LARGE -> LargeLayout(snapshot, parts)
            NowPlayingWidget.MEDIUM -> MediumLayout(snapshot, parts)
            NowPlayingWidget.SQUARE -> SquareLayout(snapshot, parts)
            NowPlayingWidget.BAR -> BarLayout(snapshot, parts)
            else -> CompactLayout(snapshot, parts)
        }
    }
}

/** 4x4: a big cover and the title, progress, then a full-width transport. */
@Composable
private fun LargeLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(20.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (parts.showCover) {
                Cover(parts, 120.dp)
                Spacer(GlanceModifier.width(16.dp))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                TrackText(snapshot, parts.look, titleSize = 20.sp, artistSize = 15.sp, titleLines = 2)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        if (parts.showProgress) {
            Progress(snapshot.progress, parts.look, 4.dp)
            Spacer(GlanceModifier.height(14.dp))
        }
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlGlyph(R.drawable.ic_glass_skip_previous, widgetText(R.string.action_previous), parts.look, 52.dp, actionRunCallback<PreviousAction>())
            Spacer(GlanceModifier.width(20.dp))
            PlayDisc(snapshot.isPlaying, parts.look, 64.dp)
            Spacer(GlanceModifier.width(20.dp))
            ControlGlyph(R.drawable.ic_glass_skip_next, widgetText(R.string.action_next), parts.look, 52.dp, actionRunCallback<NextAction>())
        }
    }
}

/** Fits MEDIUM's height: 140 less the padding and the progress row leaves 92. */
private val MEDIUM_COVER = 88.dp

/** 4x2 and 5x2: the cover, the title and transport beside it, progress along the bottom. */
@Composable
private fun MediumLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (parts.showCover) {
                Cover(parts, MEDIUM_COVER)
                Spacer(GlanceModifier.width(14.dp))
            }
            // As tall as the cover: the title lines up with its top, the transport with its foot.
            Column(modifier = GlanceModifier.defaultWeight().height(MEDIUM_COVER)) {
                TrackText(snapshot, parts.look, titleSize = 17.sp, artistSize = 13.sp)
                Spacer(GlanceModifier.defaultWeight())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlGlyph(R.drawable.ic_glass_skip_previous, widgetText(R.string.action_previous), parts.look, 40.dp, actionRunCallback<PreviousAction>())
                    Spacer(GlanceModifier.width(6.dp))
                    PlayDisc(snapshot.isPlaying, parts.look, 44.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    ControlGlyph(R.drawable.ic_glass_skip_next, widgetText(R.string.action_next), parts.look, 40.dp, actionRunCallback<NextAction>())
                }
            }
        }
        if (parts.showProgress) {
            Spacer(GlanceModifier.height(12.dp))
            Progress(snapshot.progress, parts.look, 4.dp)
        }
    }
}

/** 2x2: cover and play disc across the top, the title at the foot, progress under it. */
@Composable
private fun SquareLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (parts.showCover) Cover(parts, 52.dp)
            Spacer(GlanceModifier.defaultWeight())
            PlayDisc(snapshot.isPlaying, parts.look, 40.dp)
        }
        Spacer(GlanceModifier.defaultWeight())
        TrackText(snapshot, parts.look, titleSize = 14.sp, artistSize = 12.sp)
        if (parts.showProgress) {
            Spacer(GlanceModifier.height(7.dp))
            Progress(snapshot.progress, parts.look, 3.dp)
        }
    }
}

/** 4x1: cover, title over a progress line, and the transport. */
@Composable
private fun BarLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.showCover) {
            Cover(parts, 44.dp)
            Spacer(GlanceModifier.width(12.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            TrackText(snapshot, parts.look, titleSize = 14.sp, artistSize = 12.sp)
            if (parts.showProgress) {
                Spacer(GlanceModifier.height(5.dp))
                Progress(snapshot.progress, parts.look, 3.dp)
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        ControlGlyph(R.drawable.ic_glass_skip_previous, widgetText(R.string.action_previous), parts.look, 36.dp, actionRunCallback<PreviousAction>())
        PlayDisc(snapshot.isPlaying, parts.look, 38.dp)
        ControlGlyph(R.drawable.ic_glass_skip_next, widgetText(R.string.action_next), parts.look, 36.dp, actionRunCallback<NextAction>())
    }
}

/** 2x1, and anything smaller than the others: cover, title, play. */
@Composable
private fun CompactLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.showCover) {
            Cover(parts, 34.dp)
            Spacer(GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            TrackText(snapshot, parts.look, titleSize = 13.sp, artistSize = 11.sp)
        }
        Spacer(GlanceModifier.width(6.dp))
        PlayDisc(snapshot.isPlaying, parts.look, 34.dp)
    }
}

/** Title in the ink, artist softer; "Nothing playing" before the first song. */
@Composable
private fun TrackText(snapshot: NowPlayingSnapshot, look: WidgetLook, titleSize: TextUnit, artistSize: TextUnit, titleLines: Int = 1) {
    Text(
        text = snapshot.title.ifBlank { widgetText(R.string.widget_nothing_playing) },
        maxLines = titleLines,
        style = TextStyle(color = ColorProvider(look.ink), fontSize = titleSize, fontWeight = FontWeight.Bold),
    )
    if (snapshot.artist.isNotBlank()) {
        Text(
            text = snapshot.artist,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(look.inkSoft), fontSize = artistSize),
        )
    }
}

/** The cover, corners scaled with its size; a clearer pane with a play glyph when there is none. */
@Composable
private fun Cover(parts: WidgetParts, size: Dp) {
    val corner = size * 0.22f
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(corner)
            .background(ImageProvider(R.drawable.widget_glass_cover_empty)),
        contentAlignment = Alignment.Center,
    ) {
        if (parts.art != null) {
            Image(
                provider = ImageProvider(parts.art),
                contentDescription = widgetText(R.string.widget_album_art),
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize().cornerRadius(corner),
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_glass_play),
                colorFilter = ColorFilter.tint(ColorProvider(parts.look.inkSoft)),
                contentDescription = null,
                modifier = GlanceModifier.size(size * 0.42f),
            )
        }
    }
}

/** Previous / next: a glyph in the ink on a round tap target, no plate. */
@Composable
private fun ControlGlyph(resId: Int, desc: String, look: WidgetLook, size: Dp, onClick: Action) {
    Box(
        modifier = GlanceModifier.size(size).cornerRadius(size / 2).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(resId),
            colorFilter = ColorFilter.tint(ColorProvider(look.ink)),
            contentDescription = desc,
            modifier = GlanceModifier.size(size * 0.65f),
        )
    }
}

/** Play/pause on a disc: solid ink with the glyph cut in the glass's colour, or clear glass. */
@Composable
private fun PlayDisc(isPlaying: Boolean, look: WidgetLook, size: Dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_glass_disc),
            colorFilter = ColorFilter.tint(ColorProvider(look.disc)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_glass_pause else R.drawable.ic_glass_play),
            colorFilter = ColorFilter.tint(ColorProvider(look.onDisc)),
            contentDescription = widgetText(if (isPlaying) R.string.action_pause else R.string.action_play),
            modifier = GlanceModifier.size(size * 0.58f),
        )
    }
}

/** A rounded line: the ink over a faint track of it. */
@Composable
private fun Progress(progress: Float, look: WidgetLook, height: Dp) {
    Box(modifier = GlanceModifier.fillMaxWidth().height(height).cornerRadius(height / 2)) {
        LinearProgressIndicator(
            progress = progress,
            modifier = GlanceModifier.fillMaxSize(),
            color = ColorProvider(look.ink),
            backgroundColor = ColorProvider(look.track),
        )
    }
}

/**
 * A widget string in the app's language. The launcher hosts the widget with
 * the application's context, which below Android 13 ignores the in-app
 * language, so it is wrapped like the activity's.
 */
@androidx.compose.runtime.Composable
private fun widgetText(id: Int): String =
    tf.monochrome.android.locale.AppLanguage.wrap(androidx.glance.LocalContext.current).getString(id)
