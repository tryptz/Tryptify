package tf.monochrome.android.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
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
 * The picker offers it three times (small 2x2, medium 5x2, large 4x4; see
 * the receivers), but it is one widget: every entry resizes, and the layout
 * and the cover follow the exact size it is given ([pickLayout]).
 *
 * The corner is the launcher's own (`widget_glass_radius`, the system's from
 * Android 12), so on ColorOS, One UI or Pixel it rounds like the widgets
 * around it, and `appWidgetBackground` lets the launcher clip and animate it.
 */
class NowPlayingWidget : GlanceAppWidget() {

    // Exact, not buckets: see WidgetSizing for the 5x2 that got the bar layout.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = NowPlayingSnapshotStore.read(context)
        val art = snapshot.artworkUri?.let { loadArtwork(context, it) }
        val coverColor = art?.let { averageColor(it) }
        val saved = WidgetStyleStore.read(context)
        val ground = wallpaperGround(context) ?: GROUND
        provideContent {
            // Collected, not read once: a change in Settings redraws a widget
            // whose session is still open.
            val style by WidgetStyleStore.flow(context).collectAsState(saved)
            val parts = WidgetParts(
                look = resolveWidgetLook(style, coverColor, ground),
                art = art,
                showCover = style.showCover,
                showProgress = style.showProgress,
            )
            GlassWidget(snapshot, parts)
        }
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
    // The pane is a background colour, the one way a widget takes a colour
    // with its alpha. A tint drops it: Glance tints through ImageView's
    // setColorFilter, which is SRC_ATOP and keeps the drawable's own alpha,
    // so a white shape tinted "dark at 22%" came out solid light grey and
    // the wallpaper never showed through (seen on device). The corner clip
    // rounds it from Android 12; below that it is square.
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(R.dimen.widget_glass_radius)
            .background(ColorProvider(parts.look.pane))
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        parts.look.edge?.let { edge ->
            // The edge's alpha is in the drawable (0.35); the tint gives only its colour.
            Image(
                provider = ImageProvider(R.drawable.widget_glass_edge),
                colorFilter = ColorFilter.tint(ColorProvider(edge.copy(alpha = 1f))),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
        val size = LocalSize.current
        when (pickLayout(size.width, size.height, parts.showProgress)) {
            WidgetLayout.Large -> LargeLayout(snapshot, parts, largeCover(size.width))
            WidgetLayout.Medium -> MediumLayout(snapshot, parts, mediumCover(size.width, size.height, parts.showProgress))
            WidgetLayout.Square -> SquareLayout(snapshot, parts)
            WidgetLayout.Bar -> BarLayout(snapshot, parts, barCover(size.height))
            WidgetLayout.Compact -> CompactLayout(snapshot, parts)
        }
    }
}

/** 4x4: a big cover ([largeCover]) and the title, progress, then a full-width transport. */
@Composable
private fun LargeLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts, cover: Dp) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(20.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (parts.showCover) {
                Cover(parts, cover)
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

/**
 * 4x2 and 5x2: the cover, the title and transport beside it, progress along
 * the bottom. The cover fills the height there is ([mediumCover]).
 */
@Composable
private fun MediumLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts, cover: Dp) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(MEDIUM_PAD)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (parts.showCover) {
                Cover(parts, cover)
                Spacer(GlanceModifier.width(COVER_GAP))
            }
            // As tall as the cover: the title lines up with its top, the transport with its foot.
            Column(modifier = GlanceModifier.defaultWeight().height(if (parts.showCover) cover else TEXT_BLOCK)) {
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
private fun BarLayout(snapshot: NowPlayingSnapshot, parts: WidgetParts, cover: Dp) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.showCover) {
            Cover(parts, cover)
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
                colorFilter = ColorFilter.tint(ColorProvider(parts.look.ink)),
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

/**
 * Play/pause on a disc: solid ink with the glyph in the opposite colour, or
 * clear glass. The solid disc is a tinted circle (round on every Android
 * version); the clear one is a background colour, since a tint would make it
 * solid (see [GlassWidget]).
 */
@Composable
private fun PlayDisc(isPlaying: Boolean, look: WidgetLook, size: Dp) {
    val solid = look.disc.alpha >= 1f
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .then(if (solid) GlanceModifier else GlanceModifier.background(ColorProvider(look.disc)))
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        if (solid) {
            Image(
                provider = ImageProvider(R.drawable.widget_glass_disc),
                colorFilter = ColorFilter.tint(ColorProvider(look.disc)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
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
