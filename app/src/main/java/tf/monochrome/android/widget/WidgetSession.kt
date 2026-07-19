package tf.monochrome.android.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tf.monochrome.android.player.PlaybackService
import kotlin.coroutines.resume

/**
 * Data + control layer for the now-playing widget. Everything the widget shows or
 * does is driven by a SHORT-LIVED Media3 [MediaController] bound to
 * [PlaybackService] — the same session the notification and lock screen use — so
 * the widget never holds a persistent connection (which would keep the service
 * bound) and never has to duplicate playback state.
 */

/** A one-shot snapshot of the session's now-playing state. */
data class NowPlayingSnapshot(
    val hasSession: Boolean,
    val isPlaying: Boolean,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val positionMs: Long,
    val durationMs: Long,
) {
    /** Fraction played, for the top progress line; 0 when the duration is unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        val IDLE = NowPlayingSnapshot(
            hasSession = false, isPlaying = false, title = "", artist = "",
            artworkUri = null, positionMs = 0L, durationMs = 0L,
        )

        /** Sample state used only for the widget-picker preview. */
        val PREVIEW = NowPlayingSnapshot(
            hasSession = true, isPlaying = true, title = "Song title", artist = "Artist",
            artworkUri = null, positionMs = 84_000L, durationMs = 210_000L,
        )
    }
}

/**
 * Connect a Media3 [MediaController] to [PlaybackService] on the main looper, hand
 * it to [block], then ALWAYS release it. Controllers are single-threaded and must
 * be built and used on the main thread, so the whole thing runs on
 * [Dispatchers.Main]. Returns null (and skips [block]) if no session is reachable.
 */
suspend fun <T> withSessionController(context: Context, block: (MediaController) -> T): T? =
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = suspendCancellableCoroutine { cont ->
            future.addListener(
                { cont.resume(runCatching { future.get() }.getOrNull()) },
                MoreExecutors.directExecutor(),
            )
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        } ?: return@withContext null
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }

/** Read the current now-playing state, or [NowPlayingSnapshot.IDLE] if nothing is loaded. */
suspend fun readNowPlaying(context: Context): NowPlayingSnapshot =
    withSessionController(context) { mc ->
        val md = mc.mediaMetadata
        val hasItem = mc.currentMediaItem != null || md.title != null
        if (!hasItem) {
            NowPlayingSnapshot.IDLE
        } else {
            NowPlayingSnapshot(
                hasSession = true,
                isPlaying = mc.isPlaying,
                title = (md.title ?: md.displayTitle)?.toString().orEmpty(),
                artist = (md.artist ?: md.albumArtist)?.toString().orEmpty(),
                artworkUri = md.artworkUri?.toString(),
                positionMs = mc.currentPosition.coerceAtLeast(0L),
                // duration is C.TIME_UNSET (negative) until the item is prepared.
                durationMs = mc.duration.let { if (it > 0L) it else 0L },
            )
        }
    } ?: NowPlayingSnapshot.IDLE

/**
 * Decode the cover to a small software bitmap through the app's shared Coil loader
 * (which has the embedded-art fetcher registered, so file:// audio paths resolve).
 * Downscaled to keep well under the RemoteViews per-widget bitmap budget, and
 * allowHardware(false) so Palette can read pixels and RemoteViews can serialize it.
 */
suspend fun loadArtwork(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(256)
            .allowHardware(false)
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}

/**
 * The album accent — the same vibrant-swatch priority the player uses
 * (DynamicColorExtractor) — falling back to the player's blue so a tinted glyph or
 * progress line never vanishes against the dark scrim on a monochrome cover.
 */
fun accentFrom(bitmap: Bitmap): Color {
    val palette = Palette.from(bitmap).maximumColorCount(24).generate()
    val rgb = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.darkVibrantSwatch?.rgb
        ?: palette.dominantSwatch?.rgb
        ?: return WidgetColors.AccentFallback
    return Color(rgb)
}

// ── Transport actions (one-shot connect → command → release → redraw) ──────────

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withSessionController(context) { mc -> if (mc.isPlaying) mc.pause() else mc.play() }
        // Optimistic redraw; PlaybackService's listener also pushes updateAll.
        NowPlayingWidget().update(context, glanceId)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withSessionController(context) { mc -> mc.seekToNext() }
        NowPlayingWidget().update(context, glanceId)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withSessionController(context) { mc -> mc.seekToPrevious() }
        NowPlayingWidget().update(context, glanceId)
    }
}
