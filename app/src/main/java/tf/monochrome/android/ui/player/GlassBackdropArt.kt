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
 * for one 16KB texture uploaded once per track instead of a per-frame capture.
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
        loadArt(context, coverUrl)?.let { bitmap = it }
    }

    val bmp = bitmap ?: return null
    return remember(bmp) {
        BackdropArt(
            shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            width = bmp.width,
            height = bmp.height,
        )
    }
}

private suspend fun loadArt(context: Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        // A hardware bitmap cannot be wrapped in a BitmapShader on every
        // driver, and at this size the software copy costs nothing.
        .allowHardware(false)
        .size(ART_SIZE, ART_SIZE)
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (_: Exception) {
    // Same posture as the palette extractor: a cover that will not decode
    // leaves the glass on its reconstructed field, which is what it used
    // before this existed.
    null
}

private const val ART_SIZE = 64

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
    mix: Float,
    scrim: Color,
    anchor: AnchorRect,
    paneW: Float,
    paneH: Float,
) {
    setInputShader("uArt", art?.shader ?: EMPTY_ART)
    setFloatUniform("uArtScrim", scrim.red, scrim.green, scrim.blue)

    if (art == null || mix <= 0f || anchor.rootW <= 0f || anchor.rootH <= 0f) {
        setFloatUniform("uArtMix", 0f)
        setFloatUniform("uArtRect", 0f, 0f, 1f, 1f)
        setFloatUniform("uArtScreen", 0f, 0f, 1f, 1f)
        return
    }

    val r = backdropArtRect(
        artW = art.width,
        artH = art.height,
        rootW = anchor.rootW,
        rootH = anchor.rootH,
        paneLeft = anchor.left,
        paneTop = anchor.top,
        paneW = paneW,
        paneH = paneH,
    )
    setFloatUniform("uArtMix", mix)
    setFloatUniform("uArtRect", r[0], r[1], r[2], r[3])
    setFloatUniform(
        "uArtScreen",
        anchor.left / anchor.rootW,
        anchor.top / anchor.rootH,
        paneW / anchor.rootW,
        paneH / anchor.rootH,
    )
}

/**
 * How strongly a pane lenses the real cover when one is available.
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
