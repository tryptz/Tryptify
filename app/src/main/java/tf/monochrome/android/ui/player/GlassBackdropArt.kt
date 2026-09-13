package tf.monochrome.android.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The real pixels behind the player's glass, so the shader can lens *the
 * artwork* instead of the procedural stand-in it reconstructs in
 * [LIQUID_GLASS_SRC]'s `backdropField`.
 *
 * ### Why a bitmap and not a live layer capture
 *
 * The obvious design — capture the backdrop into a `GraphicsLayer` every frame
 * and hand it to the shader — is not expressible on the platform.
 * `RenderEffect.createRuntimeShaderEffect(shader, "content")` binds exactly
 * ONE input, and this shader already spends it: `content` is the pane's own
 * pixels, whose alpha field is the heightfield every bevel normal is derived
 * from. A second `uniform shader` has to be set through
 * `RuntimeShader.setInputShader`, which takes an `android.graphics.Shader` —
 * and there is no public way to turn a `GraphicsLayer` (or the `RenderNode`
 * under it) into one. So a live capture cannot be a second input.
 *
 * The album art can, because it is already a bitmap and it only changes when
 * the track does. That covers the case that actually matters: with the blurred
 * album background on, the cover *is* what is behind every pane in the player.
 *
 * ### Why the thumbnail is tiny
 *
 * The backdrop this has to match is [BlurredCoverLayer] — the cover stretched
 * over the screen under a 64dp gaussian blur. Lensing a sharp, full-resolution
 * cover would refract detail that is nowhere on the screen, and it would read
 * as a bug rather than as glass. A [ART_SIZE]px thumbnail sampled with the
 * hardware's bilinear filter is, to within a few percent, that blur already —
 * for one ~64KB texture uploaded once per track instead of a per-frame capture.
 * The bilinear part is load-bearing and has to be asked for explicitly; see
 * the filter mode set in [rememberBackdropArt].
 */
@Immutable
internal data class BackdropArt(
    /** Bound to the shader's `uArt` input. Sampler coordinates are bitmap pixels. */
    val shader: Shader,
    val width: Int,
    val height: Int,
)

/**
 * Where a pane sits inside the art's own pixel grid.
 *
 * The shader asks a simple question — "what is on the screen behind me?" — and
 * this is the answer, in the only coordinates the sampler understands. Doing
 * the mapping here rather than in AGSL keeps the crop arithmetic in Kotlin,
 * where [BackdropArtRectTest] can hold it.
 *
 * [artW]/[artH] are the thumbnail's size, [rootW]/[rootH] the size of the root
 * layout the cover is stretched across, and [paneLeft]/[paneTop]/[paneW]/[paneH]
 * the pane's rect in that root. The cover is drawn with `ContentScale.Crop`, so
 * it is scaled by the LARGER of the two ratios (cover the destination, do not
 * letterbox) and centred on the overflowing axis; this inverts that mapping.
 *
 * Returns `[x, y, w, h]` in art pixels. Values outside the bitmap are expected
 * and fine — the sampler clamps, which is the correct behaviour for a pane at
 * the screen edge lensing what is just past it.
 */
internal fun backdropArtRect(
    artW: Int,
    artH: Int,
    rootW: Float,
    rootH: Float,
    paneLeft: Float,
    paneTop: Float,
    paneW: Float,
    paneH: Float,
): FloatArray {
    if (artW <= 0 || artH <= 0 || rootW <= 0f || rootH <= 0f) {
        return floatArrayOf(0f, 0f, artW.toFloat(), artH.toFloat())
    }
    val scale = max(rootW / artW, rootH / artH)
    val dx = (rootW - artW * scale) * 0.5f
    val dy = (rootH - artH * scale) * 0.5f
    return floatArrayOf(
        (paneLeft - dx) / scale,
        (paneTop - dy) / scale,
        paneW / scale,
        paneH / scale,
    )
}

/**
 * The current cover as a shader input, or null when there is nothing to lens.
 *
 * Holds the previous cover while the next one decodes, for the same reason
 * `rememberAlbumColors` does: [BlurredCoverLayer] dissolves between covers over
 * seconds, so dropping to null the instant the track changes would take the
 * refraction out from under a backdrop that is still on screen.
 */
@Composable
internal fun rememberBackdropArt(coverUrl: String?, enabled: Boolean): BackdropArt? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(coverUrl, enabled) {
        if (!enabled || coverUrl.isNullOrBlank()) {
            // A track with no artwork has no artwork behind the glass either;
            // this is the one case where clearing is right rather than a flash.
            bitmap = null
            return@LaunchedEffect
        }
        loadArt(context, coverUrl, ART_SIZE)?.let { bitmap = it }
    }

    val bmp = bitmap ?: return null
    return remember(bmp) {
        BackdropArt(
            shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                // Bilinear, said out loud. FILTER_MODE_DEFAULT resolves the
                // sampling from the Paint's isFilterBitmap flag — and a shader
                // bound through RuntimeShader.setInputShader has no Paint, so
                // the default lands on NEAREST. That point-sampled the
                // thumbnail across the whole surface, and the glass now has
                // surfaces the size of the window — the mini player fits the
                // cover along the full width of the bar, the Audio tools sheet
                // is a full-width panel. At that magnification every texel
                // became its own flat block of colour instead of glass.
                //
                // The class doc above already reasons from "sampled with the
                // hardware's bilinear filter" — the tiny thumbnail is only a
                // stand-in for a 64dp gaussian *because* it is smoothly
                // interpolated. This is the line that makes that true.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
            },
            width = bmp.width,
            height = bmp.height,
        )
    }
}

private suspend fun loadArt(context: Context, url: String, size: Int): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        // A hardware bitmap cannot be wrapped in a BitmapShader on every
        // driver, nor uploaded with GLUtils.texImage2D, and at this size the
        // software copy costs nothing.
        .allowHardware(false)
        .size(size, size)
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (_: Exception) {
    // Same posture as the palette extractor: a cover that will not decode
    // leaves the glass on its reconstructed field, which is what it used
    // before this existed.
    null
}

/**
 * Side of the backdrop thumbnail, in pixels.
 *
 * Small on purpose — see the class doc: lensing a sharp cover would refract
 * detail that is nowhere on the screen. But 64 was sized for one pane of the
 * player, and the glass has since grown surfaces that span the whole window:
 * the mini player fits the cover along the full width of the bar, and the
 * Audio tools sheet is a full-width panel. Stretched that far, 64 px puts a
 * texel every ~17 device pixels, and each one reads as its own block of
 * colour however smoothly it is interpolated.
 *
 * 128 is the size the ambient overlay already upscales over the entire screen
 * without looking blocky, and it is still only ~64KB, decoded once per track.
 */
private const val ART_SIZE = 128

/**
 * The cover as a plain bitmap, for the ambient visualizer's GL texture.
 *
 * Same decode as [rememberBackdropArt], but the overlay needs the bitmap
 * itself — `glTexImage2D` takes a `Bitmap`, not a `BitmapShader`. It once
 * asked for twice [ART_SIZE] because it upscales over the whole screen rather
 * than over one pane; the glass has since grown window-sized surfaces of its
 * own and the two sizes have met. Still tiny: 128px, ~64KB.
 *
 * The thumbnail is **pre-blurred here**, on a background dispatcher, rather
 * than in the GL shader: the overlay only bilinearly upscales the texture,
 * and a gaussian done on the CPU reads exactly like the 64dp `Modifier.blur`
 * [PlayerBlurredArtBackground] applies, while a blur attempted in the
 * fragment shader (sparse taps over the upscaled thumbnail) reads as blocky
 * tiles. Holds the last cover while the next decodes, like
 * [rememberBackdropArt]: dropping to null on a track change would take the
 * background out from under a dissolve that is still running.
 */
@Composable
internal fun rememberCoverBitmap(
    coverUrl: String?,
    enabled: Boolean,
    size: Int = AMBIENT_ART_SIZE,
): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverUrl, enabled, size) {
        if (!enabled || coverUrl.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val decoded = loadArt(context, coverUrl, size) ?: return@LaunchedEffect
        // Off the main thread: three separable box passes over the thumbnail
        // are cheap but not free, and this runs per track change.
        bitmap = withContext(Dispatchers.Default) { decoded.stackBlur(AMBIENT_BLUR_RADIUS) }
    }
    return bitmap
}

/**
 * Blur radius, in thumbnail pixels.
 *
 * Sized so the blurred thumbnail stretched across the screen matches the
 * coverage of the 64dp gaussian [PlayerBlurredArtBackground] applies to the
 * full-resolution cover: 64dp is roughly a sixth of a phone's width, and a
 * sixth of the 128px thumbnail is ~21px. Three box passes of radius r give a
 * gaussian of σ ≈ r/2, so r = 21 lands in the right place. Tuned against the
 * non-ambient background; both should read as the same wash of album colour
 * with nothing recognisable left in it.
 */
private const val AMBIENT_BLUR_RADIUS = 21

/**
 * A gaussian approximation: three box-blur passes, horizontal + vertical
 * each, with a sliding-window sum — O(width · height) per pass regardless of
 * radius. Runs once per track change over a 128px thumbnail, so it costs
 * well under a frame.
 *
 * Pixels are handled in premultiplied form (as [getPixels] returns them),
 * which is exactly what a GPU blur does, so a transparent-edged cover blurs
 * the same way it would on the Compose side.
 */
internal fun Bitmap.stackBlur(radius: Int): Bitmap {
    if (radius <= 0 || isRecycled) return this
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return this
    val a = IntArray(w * h)
    val b = IntArray(w * h)
    getPixels(a, 0, w, 0, 0, w, h)
    repeat(3) {
        blurAxis(a, b, w, h, radius, horizontal = true)
        blurAxis(b, a, w, h, radius, horizontal = false)
    }
    val out = Bitmap.createBitmap(w, h, config ?: Bitmap.Config.ARGB_8888)
    out.setPixels(a, 0, w, 0, 0, w, h)
    return out
}

/** One box-blur sweep along [horizontal] (x) or vertical (y), clamped edges. */
private fun blurAxis(
    src: IntArray,
    dst: IntArray,
    w: Int,
    h: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val window = radius * 2 + 1
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    val stride = if (horizontal) 1 else w
    for (o in 0 until outer) {
        val base = if (horizontal) o * w else o
        // Int accumulators: the window is at most 2·radius+1 samples of
        // 0..255, so a Long would be wasted — and the composed ARGB int
        // that lands back in the IntArray must be Int anyway.
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (i in -radius..radius) {
            val px = src[base + i.coerceIn(0, inner - 1) * stride]
            sa += (px ushr 24) and 0xFF
            sr += (px ushr 16) and 0xFF
            sg += (px ushr 8) and 0xFF
            sb += px and 0xFF
        }
        for (i in 0 until inner) {
            dst[base + i * stride] =
                ((sa / window) shl 24) or ((sr / window) shl 16) or
                    ((sg / window) shl 8) or (sb / window)
            val outPx = src[base + (i - radius).coerceIn(0, inner - 1) * stride]
            val inPx = src[base + (i + radius + 1).coerceIn(0, inner - 1) * stride]
            sa += ((inPx ushr 24) and 0xFF) - ((outPx ushr 24) and 0xFF)
            sr += ((inPx ushr 16) and 0xFF) - ((outPx ushr 16) and 0xFF)
            sg += ((inPx ushr 8) and 0xFF) - ((outPx ushr 8) and 0xFF)
            sb += (inPx and 0xFF) - (outPx and 0xFF)
        }
    }
}

/**
 * The ambient overlay's thumbnail, upscaled over the whole screen rather than
 * over one pane. The same size as [ART_SIZE] now that the glass has
 * full-width surfaces of its own; kept as its own constant because the two
 * answer different questions and need not move together.
 */
internal const val AMBIENT_ART_SIZE = 128

/**
 * How the artwork maps onto a pane.
 *
 * [ROOT] is the honest case and the one [backdropArtRect] was written for: the
 * cover really is stretched across the window behind the pane, so the pane
 * reads the slice of it that is actually there.
 *
 * [PANE] exists because that mapping degenerates. The mini player is a ~64dp
 * bar, about a twelfth of a phone's height, so the slice of a 64px thumbnail
 * behind it is roughly five pixels tall — and refraction displaces by a
 * fraction of one of those. Mapped honestly the bar would lens a flat colour,
 * which is to say nothing at all. It also is not honest: away from the player
 * the artwork is not behind the bar, the app's own content is. So the cover is
 * fitted to the bar instead, and its colours sweep across the length of it.
 * That is a material choice rather than a window onto something, and it is the
 * only version of this that is visible.
 */
internal enum class BackdropArtFit {
    ROOT,
    PANE,
}

/**
 * Where a glass pane sits in the root layout, captured at layout time and read
 * at draw time.
 *
 * It has to be snapshot state rather than a plain field: a pane whose surface
 * motion is zero drives no frame clock, so nothing else would invalidate its
 * layer when it moves, and the mini player would keep lensing the slice of
 * artwork it occupied three screens ago.
 */
@Stable
internal class BackdropAnchor {
    var rect by mutableStateOf(AnchorRect.Unset)
}

internal data class AnchorRect(
    val left: Float,
    val top: Float,
    val rootW: Float,
    val rootH: Float,
) {
    companion object {
        val Unset = AnchorRect(0f, 0f, 0f, 0f)
    }
}

@Composable
internal fun rememberBackdropAnchor(): BackdropAnchor = remember { BackdropAnchor() }

/** Records this node's position in the root layout into [anchor]. */
internal fun Modifier.backdropAnchor(anchor: BackdropAnchor): Modifier =
    onGloballyPositioned { coords ->
        val root = coords.findRootCoordinates()
        val pos = root.localPositionOf(coords, Offset.Zero)
        val next = AnchorRect(pos.x, pos.y, root.size.width.toFloat(), root.size.height.toFloat())
        if (next != anchor.rect) anchor.rect = next
    }

/**
 * Binds the real-backdrop uniforms for one pane.
 *
 * `uArt` is set on every path, including the ones that do not use it: SkSL
 * requires every child shader to have an input, and an unbound one is a draw
 * -time failure rather than a blank sample. [EMPTY_ART] is the stand-in.
 *
 * [mix] of zero leaves the shader on its reconstructed field and the output
 * bit-identical to what it produced before any of this existed.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.bindBackdropArt(
    art: BackdropArt?,
    fit: BackdropArtFit,
    scrim: Color,
    anchor: AnchorRect,
    paneW: Float,
    paneH: Float,
    mix: Float = REAL_BACKDROP_MIX,
) {
    setInputShader("uArt", art?.shader ?: EMPTY_ART)
    setFloatUniform("uArtScrim", scrim.red, scrim.green, scrim.blue)

    if (art == null || mix <= 0f || anchor.rootW <= 0f || anchor.rootH <= 0f ||
        paneW <= 0f || paneH <= 0f
    ) {
        setFloatUniform("uArtMix", 0f)
        setFloatUniform("uArtRect", 0f, 0f, 1f, 1f)
        setFloatUniform("uArtScreen", 0f, 0f, 1f, 1f)
        return
    }

    // PANE is the same crop arithmetic with the pane standing in for the
    // window: the cover is fitted to the bar rather than positioned behind it.
    val r = when (fit) {
        BackdropArtFit.ROOT -> backdropArtRect(
            artW = art.width,
            artH = art.height,
            rootW = anchor.rootW,
            rootH = anchor.rootH,
            paneLeft = anchor.left,
            paneTop = anchor.top,
            paneW = paneW,
            paneH = paneH,
        )
        BackdropArtFit.PANE -> backdropArtRect(
            artW = art.width,
            artH = art.height,
            rootW = paneW,
            rootH = paneH,
            paneLeft = 0f,
            paneTop = 0f,
            paneW = paneW,
            paneH = paneH,
        )
    }
    setFloatUniform("uArtMix", mix)
    setFloatUniform("uArtRect", r[0], r[1], r[2], r[3])
    // The scrim runs down the SCREEN, so it is placed by where the pane sits
    // there in both fits. PANE passes zero height on purpose: the bar is short
    // enough that a gradient across it would only tilt it, and it is standing
    // in for a scrim that is not really there, so it takes one flat value read
    // off its own height on screen.
    setFloatUniform(
        "uArtScreen",
        anchor.left / anchor.rootW,
        anchor.top / anchor.rootH,
        paneW / anchor.rootW,
        if (fit == BackdropArtFit.PANE) 0f else paneH / anchor.rootH,
    )
}

/**
 * How strongly a pane lenses the real cover when one is available.
 *
 * Whether there is one to lens is decided by whoever provides the art, not
 * here: [rememberBackdropArt] returns null when it should not be used, and a
 * null art binds uArtMix = 0.
 *
 * Not 1.0 on purpose. The reconstructed field it blends with carries the soft
 * top glow and the off-axis pools that the on-screen backdrop also has from
 * [DynamicAlbumGlow] and the reactive glow above it — layers the cover
 * thumbnail knows nothing about. Keeping a quarter of it in preserves them.
 */
internal const val REAL_BACKDROP_MIX = 0.75f

/** The scrim tone the shader adds back mid-screen: the dominant, darkened. */
internal fun backdropScrimTone(dominant: Color): Color = lerp(dominant, Color.Black, 0.62f)

private val EMPTY_ART: Shader by lazy {
    BitmapShader(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        Shader.TileMode.CLAMP,
        Shader.TileMode.CLAMP,
    )
}
