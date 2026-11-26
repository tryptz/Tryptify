package tf.monochrome.android.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.debug.FrameStallLog
import tf.monochrome.android.domain.model.Spectrogram3DSettings
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 3D spectrogram's own feed ([SpectrumAnalyzerTap.spectrogramBins], read
 * in the frame loop), provided once around the player: the spectrum the other
 * views draw is re-levelled every frame, which a history of spectra must not
 * be. Without one the overlay is handed what its caller has.
 */
val LocalSpectrogramBins = androidx.compose.runtime.staticCompositionLocalOf<(() -> FloatArray)?> { null }

/**
 * The 3D spectrogram is drawn as one mesh with `drawVertices`, which Android
 * draws on the GPU from Android 10 on. Below that the view is not offered.
 */
internal val SPECTROGRAM_3D_SUPPORTED: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/**
 * The cover's 3D spectrogram: the music as a landscape, time sideways (the
 * newest at the right edge), pitch into the distance (bass at the front).
 * One mesh gives it its shape; from Android 13 a shader lights every pixel of
 * it from the full-resolution history ([Spectrogram3DGpu]), and below that,
 * or at Low quality, each mesh point is lit instead. It moves by the time
 * between frames, never by counting them, so it scrolls at the same speed
 * whatever the frame rate limit is.
 */
@Composable
internal fun Spectrogram3DOverlay(
    bins: () -> FloatArray,
    settings: Spectrogram3DSettings,
    /** The album's colour: the floor. */
    color: Color,
    /** The album's second colour: the loudest tops and the light glowing through the ridges. */
    glow: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** A new value (the next song) clears the landscape. */
    resetKey: Any? = null,
) {
    val scene = remember { Spectrogram3DScene() }
    val gpu = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Spectrogram3DGpu.createOrNull() else null
    }
    val plainPaint = remember { android.graphics.Paint().apply { this.color = android.graphics.Color.WHITE } }
    var frame by remember { mutableIntStateOf(0) }
    val currentSettings by rememberUpdatedState(settings)
    val currentBins by rememberUpdatedState(bins)
    LaunchedEffect(resetKey) {
        scene.clear()
        frame++
    }
    // Paused, the landscape stands still and nothing asks for frames.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            frame++
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                scene.advance(currentBins(), currentSettings, dt)
                frame++
            }
        }
    }
    Canvas(modifier.clipToBounds()) {
        // Read here so a new frame redraws without recomposing.
        @Suppress("UNUSED_EXPRESSION") frame
        val s = currentSettings
        val palette = SpectrogramPalette.of(color.toArgb(), glow.toArgb())
        val shaded = gpu != null && s.quality > Spectrogram3DSettings.MIN_QUALITY
        scene.build(size.width, size.height, s, palette, lit = !shaded)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (scene.indexCount > 0) {
                if (shaded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gpu!!.draw(native, scene, palette, s)
                } else {
                    native.drawVertices(
                        android.graphics.Canvas.VertexMode.TRIANGLES,
                        scene.vertexCount * 2, scene.verts, 0,
                        null, 0,
                        scene.colors, 0,
                        scene.indices, 0, scene.indexCount,
                        plainPaint,
                    )
                }
            }
        }
        FrameStallLog.drew(FrameStallLog.Effect.SPECTROGRAM, (size.width * size.height).toLong())
    }
}

/**
 * The landscape's two colours, made fit to draw with: the floor's from the
 * album's colour (kept saturated and neither dark nor pale, whatever the
 * album gave), the tops' from its second colour at full strength. A second
 * colour with no hue of its own (the grey of a dark background) takes one set
 * well apart from the floor's.
 */
internal class SpectrogramPalette(val base: FloatArray, val glow: FloatArray) {
    companion object {
        fun of(baseArgb: Int, glowArgb: Int): SpectrogramPalette {
            val b = hsv(baseArgb)
            val g = hsv(glowArgb)
            val baseHue = b[0]
            val floor = rgb(baseHue, b[1].coerceIn(0.55f, 0.9f), b[2].coerceIn(0.6f, 0.88f))
            val glowHue = if (g[1] < 0.15f || g[2] < 0.06f) (baseHue + 140f) % 360f else g[0]
            val top = rgb(glowHue, g[1].coerceIn(0.7f, 1f), 1f)
            return SpectrogramPalette(floor, top)
        }

        private fun hsv(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val d = mx - mn
            val h = when {
                d < 1e-5f -> 0f
                mx == r -> 60f * (((g - b) / d) % 6f)
                mx == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            return floatArrayOf((h + 360f) % 360f, if (mx <= 0f) 0f else d / mx, mx)
        }

        private fun rgb(h: Float, s: Float, v: Float): FloatArray {
            val c = v * s
            val hp = (h % 360f) / 60f
            val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
            val (r, g, b) = when (hp.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = v - c
            return floatArrayOf(r + m, g + m, b + m)
        }
    }
}

/**
 * The shader that lights the landscape, a pixel at a time, and what feeds
 * it: the history as two 8-bit textures (the high and the low byte of a
 * 16-bit height, in the alpha channel, which no colour management touches;
 * the decode is linear, so the GPU's own filtering interpolates heights
 * exactly) and the camera. The mesh hands each pixel its place in the
 * history as texture coordinates.
 *
 * Two pairs of bitmaps, used turn about: the frame being drawn on the render
 * thread reads one while the next is written into the other.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class Spectrogram3DGpu private constructor(private val shader: RuntimeShader) {
    private class Textures(width: Int, height: Int) {
        val hi: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val lo: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val hiShader = BitmapShader(hi, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        val loShader = BitmapShader(lo, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }

    private var pairs: Array<Textures>? = null
    private var turn = 0
    private val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }

    fun draw(canvas: android.graphics.Canvas, scene: Spectrogram3DScene, palette: SpectrogramPalette, s: Spectrogram3DSettings) {
        val w = scene.texColumns
        val h = scene.texRows
        var p = pairs
        if (p == null || p[0].hi.width != w || p[0].hi.height != h) {
            p?.forEach { it.hi.recycle(); it.lo.recycle() }
            p = arrayOf(Textures(w, h), Textures(w, h))
            pairs = p
        }
        turn = 1 - turn
        val t = p[turn]
        t.hi.copyPixelsFromBuffer(ByteBuffer.wrap(scene.texHi))
        t.lo.copyPixelsFromBuffer(ByteBuffer.wrap(scene.texLo))
        shader.setInputShader("uHi", t.hiShader)
        shader.setInputShader("uLo", t.loShader)
        shader.setFloatUniform("uGrid", scene.worldPerColumn, scene.worldPerRow, scene.lift)
        shader.setFloatUniform("uOrigin", scene.originU, scene.originX)
        shader.setFloatUniform("uCam", scene.camY, scene.camZ, scene.camScale)
        shader.setFloatUniform("uTilt", scene.camSin, scene.camCos)
        shader.setFloatUniform("uBase", palette.base)
        shader.setFloatUniform("uGlow", palette.glow)
        shader.setFloatUniform("uLook", s.gloss, s.subsurface, s.haze, (h - 1).toFloat())
        paint.shader = shader
        canvas.drawVertices(
            android.graphics.Canvas.VertexMode.TRIANGLES,
            scene.vertexCount * 2, scene.verts, 0,
            scene.texs, 0,
            null, 0,
            scene.indices, 0, scene.indexCount,
            paint,
        )
    }

    companion object {
        /** A shader of this overlay's own (never shared: it is a paint's shader), or null where it does not compile. */
        fun createOrNull(): Spectrogram3DGpu? =
            runCatching { Spectrogram3DGpu(RuntimeShader(SPECTROGRAM_3D_SHADER)) }.getOrNull()
    }
}

/**
 * The landscape's light, per pixel. `p` is the pixel's place in the history:
 * x in columns (the texture repeats sideways, so the ring needs no seam), y
 * in rows from the front; under row 0 it is the front wall.
 *
 * Floor in the album's colour, flanks to white, the loudest tops in its
 * second colour; a key light, a sharp gloss, the sky a wet surface mirrors
 * (stronger at a glancing angle), light glowing through ridges from a lamp
 * behind (subsurface scattering), shade in the hollows, and haze toward the
 * back. The steps the slope is measured over are the texels one pixel covers
 * there, worked out from the camera, so the far rows do not shimmer.
 */
internal const val SPECTROGRAM_3D_SHADER = """
uniform shader uHi;
uniform shader uLo;
uniform float3 uGrid;
uniform float2 uOrigin;
uniform float3 uCam;
uniform float2 uTilt;
uniform float3 uBase;
uniform float3 uGlow;
uniform float4 uLook;

float height(float2 p) {
    return (uHi.eval(p).a * 65280.0 + uLo.eval(p).a * 255.0) / 65535.0;
}

half4 main(float2 p) {
    float wall = clamp((0.5 - p.y) / 6.0, 0.0, 1.0);
    float2 q = float2(p.x, max(p.y, 0.5));
    float zr = q.y - 0.5;
    float z = zr * uGrid.y;
    float x = uOrigin.y - (q.x - uOrigin.x) * uGrid.x;
    float cz = max(uCam.x * uTilt.x + (z - uCam.y) * uTilt.y, 0.05);
    float fu = max(2.0, cz / (uCam.z * uGrid.x));
    float fv = max(1.25, cz * cz / (uCam.z * uCam.x * uGrid.y));

    float zn = clamp(zr / uLook.w, 0.0, 1.0);
    float haze = uLook.z;
    // The far rows go out of focus with the haze: measured over a wider step.
    float soft = haze * smoothstep(0.4, 1.0, zn);
    fu *= 1.0 + 3.0 * soft;
    fv *= 1.0 + 3.0 * soft;

    float h = height(q);
    float hl = height(float2(q.x - fu, q.y));
    float hr = height(float2(q.x + fu, q.y));
    float hd = height(float2(q.x, q.y - fv));
    float hu = height(float2(q.x, q.y + fv));
    float around = 0.25 * (hl + hr + hd + hu);
    h = mix(h, around, 0.7 * soft);
    float lift = uGrid.z;
    float sx = (hl - hr) * lift / (2.0 * fu * uGrid.x);
    float sz = (hu - hd) * lift / (2.0 * fv * uGrid.y);
    float3 n = normalize(float3(-sx, 1.0, -sz));
    float3 P = float3(x, h * lift, z);
    float3 V = normalize(float3(0.0, uCam.x, uCam.y) - P);
    float ndv = clamp(dot(n, V), 0.0, 1.0);

    // The colour is read a little wider than the height, so its bands curve
    // with the surface and do not show the texture's grid.
    float hc = mix(h, around, 0.45);
    float3 albedo = mix(uBase, float3(0.95, 0.96, 0.99), smoothstep(0.36, 0.58, hc));
    albedo = mix(albedo, uGlow * 0.5, smoothstep(0.64, 0.86, hc) * 0.94);

    float ao = clamp(1.0 - 3.0 * (around - h), 0.35, 1.0);

    float3 L = normalize(float3(-0.30, 0.80, -0.52));
    float diffuse = clamp((dot(n, L) + 0.35) / 1.35, 0.0, 1.0);
    float3 H = normalize(L + V);
    float ndh = max(dot(n, H), 0.0);
    float gloss = uLook.x;
    float spec = gloss * (1.7 * pow(ndh, 40.0 + 260.0 * gloss) + 0.2 * pow(ndh, 10.0));

    float3 R = reflect(-V, n);
    float fres = 0.04 + 0.96 * pow(1.0 - ndv, 5.0);
    float sky = smoothstep(-0.1, 0.5, R.z) * (1.0 - smoothstep(0.1, 0.85, R.y));
    float3 env = mix(uBase, float3(1.0), 0.35) * sky;

    float3 back = normalize(float3(0.18, 0.32, 0.93) + n * 0.6);
    float through = max(dot(V, -back), 0.0);
    float rim = (1.0 - ndv) * (1.0 - ndv);
    float sss = uLook.y * (0.9 * through * through * through + 0.22 * rim) * (0.25 + 0.75 * h);

    float3 col = albedo * (0.24 + 0.74 * diffuse) * ao
        + uGlow * sss
        + env * fres * gloss * 1.2
        + float3(spec);

    col = mix(col, uBase * 0.6 + 0.08, haze * 0.9 * zn * sqrt(zn));
    float alpha = 1.0 - haze * smoothstep(0.4, 1.0, zn);
    // The front wall: in shade from its top edge, to near black at its foot.
    col = mix(col, uBase * 0.05, step(p.y, 0.499) * (0.82 + 0.18 * wall));
    return half4(half3(clamp(col, 0.0, 1.0)) * half(alpha), half(alpha));
}
"""

/**
 * The landscape's state and geometry, free of Android so it is held to
 * numbers (`Spectrogram3DSceneTest`): the history of spectra, its textures,
 * the camera and the mesh.
 *
 * History: a ring of columns at the textures' resolution, one pushed every
 * [columnSeconds] of playing time from a live column that follows the sound.
 * The newest pushed column sits [phase] of a column left of the live one, so
 * the landscape glides rather than steps. The floor is there from the start:
 * a cleared history is flat, not missing.
 *
 * Level: the sound's own, not re-levelled each frame. A reference follows the
 * loudest band, up at once and down slowly, and [RANGE_DB] under it is the
 * floor, so a quiet passage lies low and silence is flat.
 */
internal class Spectrogram3DScene {
    /** History and texture size: columns (time, a ring) by rows (pitch). */
    var texColumns = 0; private set
    var texRows = 0; private set
    /** Columns across the front edge: [Spectrogram3DSettings.secondsAcross] of music. */
    var frontColumns = 0; private set
    private var colStride = 1
    private var rowStride = 1
    private var meshRows = 0

    private var ring = FloatArray(0)
    /** The ring's slot of the newest pushed column. */
    private var head = 0
    /** Columns pushed since the landscape was cleared. */
    var pushes = 0L; private set
    private val live = FloatArray(MAX_ROWS)
    private val sampled = FloatArray(MAX_ROWS)
    private val blurred = FloatArray(MAX_ROWS)
    private val ripple = FloatArray(MAX_ROWS)
    private val rowBin = FloatArray(MAX_ROWS + 1)
    private val rowTilt = FloatArray(MAX_ROWS)
    private var acc = 0f
    /** How far, in columns, the newest pushed column has moved off the live one. */
    var phase = 0f; private set
    /** The level the loudest band was last seen near, in the feed's dB. */
    var referenceDb = MIN_REFERENCE_DB; private set
    private var primed = false
    private var gridKey = ""

    /** The textures: each height's high and low byte, row by row. */
    var texHi = ByteArray(0); private set
    var texLo = ByteArray(0); private set

    // The mesh, as drawVertices takes it.
    var verts = FloatArray(0); private set
    var texs = FloatArray(0); private set
    var colors = IntArray(0); private set
    var indices = ShortArray(0); private set
    var vertexCount = 0; private set
    var indexCount = 0; private set
    /** The surface's indices come first; the front wall's follow. */
    var surfaceIndexCount = 0; private set
    /** Mesh columns in the last build, the live one included. */
    var meshColumns = 0; private set
    private var wx = FloatArray(0)
    private var wy = FloatArray(0)
    private var columnK = IntArray(0)

    // What the shader is told of the last build.
    var worldPerColumn = 0f; private set
    var worldPerRow = 0f; private set
    var lift = 0f; private set
    var originU = 0f; private set
    var originX = 0f; private set
    var camY = 0f; private set
    var camZ = 0f; private set
    var camScale = 0f; private set
    var camSin = 0f; private set
    var camCos = 0f; private set

    /** Seconds of music between two history columns. */
    fun columnSeconds(s: Spectrogram3DSettings): Float = s.secondsAcross / frontColumnsFor(s.quality)

    fun clear() {
        ring.fill(0f)
        live.fill(0f)
        texHi.fill(0)
        texLo.fill(0)
        head = 0
        pushes = 0L
        acc = 0f
        phase = 0f
        referenceDb = MIN_REFERENCE_DB
        primed = false
    }

    /** Sizes the grid for [s]; a new grid starts the landscape afresh. */
    fun prepare(s: Spectrogram3DSettings) {
        val key = "${s.quality}/${s.minHz}/${s.maxHz}"
        if (key == gridKey) return
        gridKey = key
        val q = s.quality.coerceIn(1, 4) - 1
        meshRows = MESH_ROWS[q]
        rowStride = ROW_STRIDE[q]
        colStride = COL_STRIDE[q]
        texRows = (meshRows - 1) * rowStride + 1
        frontColumns = FRONT_COLUMNS[q]
        // The widest field (the lowest camera) is about 1.9 front widths;
        // a multiple of four, so an 8-bit bitmap's rows need no padding.
        texColumns = ((frontColumns * MAX_EXTENT).toInt() + 8 + 3) / 4 * 4
        ring = FloatArray(texColumns * texRows)
        texHi = ByteArray(texColumns * texRows)
        texLo = ByteArray(texColumns * texRows)
        // Where each row's edges fall among the feed's log bins. The rows
        // are spread as [rowHz] says, not by the octave.
        val binSpan = ln(BIN_TOP_HZ / BIN_BOTTOM_HZ)
        val last = SpectrumAnalyzerTap.OUTPUT_BINS - 1
        for (j in 0..texRows) {
            val hz = rowHz((j - 0.5f) / (texRows - 1), s.minHz, s.maxHz).coerceAtLeast(BIN_BOTTOM_HZ)
            rowBin[j] = (ln(hz / BIN_BOTTOM_HZ) / binSpan * last).coerceIn(0f, last.toFloat())
        }
        // The feed is tilted up 4 dB an octave, which lays music flat for a
        // single line; a landscape wants its treble lower than its bass, as
        // the sound is, so some of the tilt is taken back.
        for (j in 0 until texRows) {
            val hz = rowHz(j.toFloat() / (texRows - 1), s.minHz, s.maxHz)
            rowTilt[j] = -TILT_BACK_DB_PER_OCTAVE * (ln(hz / 1000f) / ln(2f))
        }
        clear()
    }

    /** [dt] seconds of playing time with [bins] (the feed's dB) as the newest sound. */
    fun advance(bins: FloatArray, s: Spectrogram3DSettings, dt: Float) {
        prepare(s)
        // A long gap (the screen was off) is not hours of landscape to catch up on.
        val step = dt.coerceIn(0f, MAX_STEP_SECONDS)
        sample(bins, s, step)
        // Up to a new sound quickly, down from it slowly: a note is there at
        // once and is held as it dies, so a run of short notes on one pitch
        // is one bar with a pinch between them, not a row of bumps.
        val up = 1f - exp(-step / (ATTACK_TAU_MIN + s.smoothness * ATTACK_TAU_RANGE))
        val down = 1f - exp(-step / (RELEASE_TAU_MIN + s.smoothness * RELEASE_TAU_RANGE))
        for (j in 0 until texRows) {
            val d = blurred[j] - live[j]
            live[j] += d * (if (d > 0f) up else down)
        }
        val colSec = columnSeconds(s)
        acc += step
        while (acc >= colSec) {
            head = (head - 1 + texColumns) % texColumns
            System.arraycopy(live, 0, ring, head * texRows, texRows)
            writeSlot(head, live)
            pushes++
            acc -= colSec
        }
        phase = (acc / colSec).coerceIn(0f, 1f)
        // The live column right of the newest, and a copy beyond it: the
        // slope at the right edge is measured against it, not against the
        // oldest column the ring holds there.
        writeSlot((head - 1 + texColumns) % texColumns, live)
        writeSlot((head - 2 + texColumns) % texColumns, live)
    }

    /** The feed at this grid's pitches, as heights 0..1 against the reference, smoothed across pitch. */
    private fun sample(bins: FloatArray, s: Spectrogram3DSettings, step: Float) {
        val n = bins.size
        var loudest = SILENT_DB
        for (j in 0 until texRows) {
            val db = if (n == 0) {
                SILENT_DB
            } else {
                rowLevel(bins, rowBin[j].coerceAtMost(n - 1f), rowBin[j + 1].coerceAtMost(n - 1f)) + rowTilt[j]
            }
            sampled[j] = db
            if (db > loudest) loudest = db
        }
        // The reference: up to a louder sound within a few frames, down slowly.
        if (!primed && loudest > MIN_REFERENCE_DB + 6f) {
            referenceDb = loudest
            primed = true
        } else if (loudest > referenceDb) {
            referenceDb += (loudest - referenceDb) * (1f - exp(-step / REFERENCE_ATTACK_S))
        } else {
            // Down slowly, so a quiet passage lies low and a rest between
            // two notes moves nothing. Faster only for sound far under it (a
            // far quieter song), never for silence.
            val gap = referenceDb - loudest
            val far = if (loudest > MIN_REFERENCE_DB) max(0f, gap - REFERENCE_NEAR_DB) * REFERENCE_FAR_RATE else 0f
            val fall = REFERENCE_FALL_DB_S + far
            referenceDb = max(referenceDb - fall * step, max(loudest, MIN_REFERENCE_DB))
            // Back at the bottom: the next sound sets it at once.
            if (referenceDb <= MIN_REFERENCE_DB + 0.5f) primed = false
        }
        val floor = referenceDb - RANGE_DB
        val span = RANGE_DB - HEADROOM_DB
        for (j in 0 until texRows) {
            // What lies under the floor still stirs it a little: the ripple
            // the light catches where nothing is loud enough to stand up.
            ripple[j] = ((sampled[j] - (floor - RIPPLE_DB)) / RIPPLE_DB).coerceIn(0f, 1f)
            sampled[j] = heightOf((sampled[j] - floor) / span)
        }
        val sigma = (BLUR_MIN + s.smoothness * BLUR_RANGE) * texRows / 129f
        val radius = (sigma * 2.5f).roundToInt().coerceIn(0, 12)
        for (j in 0 until texRows) {
            var sum = 0f
            var weight = 0f
            for (k in -radius..radius) {
                val r = (j + k).coerceIn(0, texRows - 1)
                val w = exp(-0.5f * k * k / (sigma * sigma))
                sum += sampled[r] * w
                weight += w
            }
            // Most of the blur, and a little of the detail it took: the
            // fine ripple the light catches on the floor and on the bars.
            val soft = sum / weight
            val shaped = soft + (sampled[j] - soft) * DETAIL
            blurred[j] = RIPPLE * ripple[j] + (1f - RIPPLE) * shaped
        }
    }

    /** The loudest of the bins between [from] and [to], or between two where the row is narrower than a bin. */
    private fun rowLevel(bins: FloatArray, from: Float, to: Float): Float {
        val a = kotlin.math.ceil(from).toInt()
        val b = to.toInt()
        if (b < a) {
            val mid = (from + to) / 2f
            val i = mid.toInt().coerceAtMost(bins.size - 1)
            val f = mid - i
            val x = bins[i]
            val y = bins[min(i + 1, bins.size - 1)]
            val v = x + (y - x) * f
            return if (v.isFinite()) v else SILENT_DB
        }
        var loudest = SILENT_DB
        for (i in a..b) {
            val v = bins[i]
            if (v.isFinite() && v > loudest) loudest = v
        }
        return loudest
    }

    private fun writeSlot(slot: Int, column: FloatArray) {
        for (j in 0 until texRows) {
            val q = (column[j].coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
            val at = j * texColumns + slot
            texHi[at] = (q shr 8).toByte()
            texLo[at] = q.toByte()
        }
    }

    /** History column [k] (0 the newest pushed) at pitch row [j]. */
    fun historyHeight(k: Int, j: Int): Float =
        if (k < 0 || k >= texColumns - GUARD) 0f else ring[((head + k) % texColumns) * texRows + j]

    fun liveHeight(j: Int): Float = live[j]

    /** A texture's height at ring [slot] and row [j], decoded as the shader decodes it. */
    fun textureHeight(slot: Int, j: Int): Float {
        val at = j * texColumns + slot
        return ((texHi[at].toInt() and 0xFF) * 256 + (texLo[at].toInt() and 0xFF)) / 65535f
    }

    /** The slot the live column is written to. */
    val liveSlot: Int get() = (head - 1 + texColumns) % texColumns

    /** The mesh's height at history column [k] (-1 the live one) and row [j]: the mean of the block it stands for. */
    private fun blockHeight(k: Int, j: Int): Float {
        val j0 = max(j - rowStride / 2, 0)
        val j1 = min(j + rowStride / 2, texRows - 1)
        var sum = 0f
        var count = 0
        if (k < 0) {
            for (jj in j0..j1) { sum += live[jj]; count++ }
        } else {
            val k0 = max(k - colStride / 2, 0)
            val k1 = min(k + colStride / 2, texColumns - GUARD - 1)
            for (kk in k0..k1) {
                val base = ((head + kk) % texColumns) * texRows
                for (jj in j0..j1) { sum += ring[base + jj]; count++ }
            }
        }
        return if (count == 0) 0f else sum / count
    }

    /**
     * Builds the mesh for a [width] by [height] box: positions and each
     * point's place in the history ([texs]), and with [lit] each point's
     * colour too, for drawing without the shader.
     */
    fun build(width: Float, height: Float, s: Spectrogram3DSettings, palette: SpectrogramPalette, lit: Boolean) {
        prepare(s)
        vertexCount = 0
        indexCount = 0
        surfaceIndexCount = 0
        if (width <= 0f || height <= 0f || meshRows < 2) return
        val cam = Camera.of(s.angleDeg, width, height)
        val dx = 2f * cam.frontHalfWidth / frontColumns
        val right = cam.fieldHalfWidth
        // History columns in view: to a stride past the left edge.
        val kMax = min(texColumns - GUARD - 1, (2f * right / dx).toInt() + colStride + 1)
        if (columnK.size < kMax + 3) columnK = IntArray(kMax + 3)
        var columns = 0
        columnK[columns++] = -1 // the live column, at the right edge
        columnK[columns++] = 0
        // Then the columns that stay mesh columns as the history moves on:
        // a point rides the same column of sound until it leaves at the left.
        for (k in 1..kMax) {
            if ((pushes - 1 - k) % colStride == 0L) columnK[columns++] = k
        }
        val count = columns * meshRows
        val total = count + columns * 2
        if (verts.size < total * 2) {
            verts = FloatArray(total * 2)
            texs = FloatArray(total * 2)
            colors = IntArray(total)
            wx = FloatArray(count)
            wy = FloatArray(count)
        }
        val quads = (columns - 1) * (meshRows - 1) + (columns - 1)
        if (indices.size < quads * 6) indices = ShortArray(quads * 6)

        lift = HEIGHT_SCALE * s.peakHeight
        worldPerColumn = dx
        worldPerRow = 1f / (texRows - 1)
        // The texture repeats sideways; a turn is added so no column is negative.
        val u0 = texColumns + head + 0.5f
        originU = u0
        originX = right - phase * dx
        camY = cam.camY; camZ = cam.camZ; camScale = cam.scale; camSin = cam.sinA; camCos = cam.cosA

        for (c in 0 until columns) {
            val k = columnK[c]
            val x = if (k < 0) right else right - (k + phase) * dx
            val u = u0 + k
            for (m in 0 until meshRows) {
                val j = m * rowStride
                val v = c * meshRows + m
                wx[v] = x
                wy[v] = blockHeight(k, j) * lift
                cam.project(x, wy[v], j * worldPerRow, verts, v * 2)
                texs[v * 2] = u
                texs[v * 2 + 1] = j + 0.5f
            }
        }
        if (lit) {
            val light = Lighting(s, palette, cam)
            for (c in 0 until columns) {
                for (m in 0 until meshRows) {
                    val v = c * meshRows + m
                    val cl = max(c - 1, 0)
                    val cr = min(c + 1, columns - 1)
                    val ml = max(m - 1, 0)
                    val mr = min(m + 1, meshRows - 1)
                    val ddx = wx[cl * meshRows + m] - wx[cr * meshRows + m]
                    val slopeX = if (ddx != 0f) (wy[cl * meshRows + m] - wy[cr * meshRows + m]) / ddx else 0f
                    val slopeZ = (wy[c * meshRows + mr] - wy[c * meshRows + ml]) / ((mr - ml) * rowStride * worldPerRow)
                    val z = m.toFloat() / (meshRows - 1)
                    colors[v] = light.shade(wx[v], wy[v], z, slopeX, slopeZ, if (lift > 0f) wy[v] / lift else 0f)
                }
            }
        }
        // Back to front: the strip nearest the back first, so nearer ridges
        // are drawn over what they hide.
        var n = 0
        for (m in meshRows - 2 downTo 0) {
            for (c in 0 until columns - 1) {
                val a = c * meshRows + m
                val b = (c + 1) * meshRows + m
                indices[n++] = (a + 1).toShort(); indices[n++] = (b + 1).toShort(); indices[n++] = a.toShort()
                indices[n++] = (b + 1).toShort(); indices[n++] = b.toShort(); indices[n++] = a.toShort()
            }
        }
        surfaceIndexCount = n
        // The front wall, drawn last: from the front ridge down to the box's
        // bottom, so the cover never shows through under the nearest peaks.
        for (c in 0 until columns) {
            val top = count + c * 2
            val front = c * meshRows
            verts[top * 2] = verts[front * 2]
            verts[top * 2 + 1] = verts[front * 2 + 1]
            verts[(top + 1) * 2] = verts[front * 2]
            verts[(top + 1) * 2 + 1] = height
            texs[top * 2] = texs[front * 2]
            texs[top * 2 + 1] = 0.5f
            texs[(top + 1) * 2] = texs[front * 2]
            texs[(top + 1) * 2 + 1] = 0.5f - WALL_ROWS
            if (lit) {
                colors[top] = Lighting.wallTop(colors[front], palette)
                colors[top + 1] = Lighting.wallFoot(palette)
            }
        }
        for (c in 0 until columns - 1) {
            val a = count + c * 2
            val b = count + (c + 1) * 2
            indices[n++] = a.toShort(); indices[n++] = b.toShort(); indices[n++] = (a + 1).toShort()
            indices[n++] = b.toShort(); indices[n++] = (b + 1).toShort(); indices[n++] = (a + 1).toShort()
        }
        meshColumns = columns
        vertexCount = total
        indexCount = n
    }

    /**
     * A camera above the front edge looking down at [angleDeg] onto a field
     * 1 deep (pitch) and as wide as it must be to fill the box to the back,
     * fitted so the ground runs from the box's bottom (front) to [BACK_EDGE]
     * from its top (back).
     */
    class Camera private constructor(
        val sinA: Float,
        val cosA: Float,
        val camY: Float,
        val camZ: Float,
        /** Pixels a world unit spans at depth 1. */
        val scale: Float,
        private val originX: Float,
        private val originY: Float,
        /** Half the front edge's visible width, in world units. */
        val frontHalfWidth: Float,
        /** Half the field's width: enough to reach the box's sides at the back. */
        val fieldHalfWidth: Float,
    ) {
        /** Writes the screen position of world point ([x], [y], [z]) into [out] at [at]. */
        fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
            val qy = y - camY
            val qz = z - camZ
            val cy = qy * cosA + qz * sinA
            val cz = max(-qy * sinA + qz * cosA, 0.05f)
            out[at] = originX + x / cz * scale
            out[at + 1] = originY - cy / cz * scale
        }

        companion object {
            fun of(angleDeg: Float, width: Float, height: Float): Camera {
                val a = Math.toRadians(angleDeg.toDouble())
                val sinA = sin(a).toFloat()
                val cosA = cos(a).toFloat()
                val camY = DISTANCE * sinA
                val camZ = TARGET_Z - DISTANCE * cosA
                // Depth along the view of the front and back ground edges.
                val czFront = DISTANCE - TARGET_Z * cosA
                val czBack = DISTANCE + (1f - TARGET_Z) * cosA
                fun py(y: Float, z: Float): Float {
                    val qy = y - camY
                    val qz = z - camZ
                    return (qy * cosA + qz * sinA) / (-qy * sinA + qz * cosA)
                }
                // The ground runs from the box's bottom (the front, the bass)
                // to near its top (the back, the treble), as in a landscape
                // seen from a low camera; the peaks rise over the rows behind.
                val bottom = py(0f, 0f)
                val back = py(0f, 1f)
                val scale = height * (FRONT_EDGE - BACK_EDGE) / max(back - bottom, 1e-3f)
                // How much of the front edge that leaves in view: the field
                // is made wide enough to fill the box at the back.
                val byWidth = width / 2f * czFront
                val frontHalf = byWidth / scale
                val originY = height * FRONT_EDGE + bottom * scale
                return Camera(
                    sinA, cosA, camY, camZ, scale,
                    originX = width / 2f,
                    originY = originY,
                    frontHalfWidth = frontHalf,
                    fieldHalfWidth = frontHalf * czBack / czFront,
                )
            }
        }
    }

    /**
     * The shader's light at one mesh point, for drawing without it (below
     * Android 13, and at Low quality): the same colours, key light, gloss,
     * glow and haze, from the mesh's own slopes, without the mirrored sky
     * and the shade in the hollows.
     */
    private class Lighting(s: Spectrogram3DSettings, palette: SpectrogramPalette, private val cam: Camera) {
        private val br = palette.base[0]; private val bg = palette.base[1]; private val bb = palette.base[2]
        private val gr = palette.glow[0]; private val gg = palette.glow[1]; private val gb = palette.glow[2]
        private val gloss = s.gloss
        private val shine = 40f + 260f * s.gloss
        private val subsurface = s.subsurface
        private val haze = s.haze

        fun shade(x: Float, y: Float, zn: Float, slopeX: Float, slopeZ: Float, h: Float): Int {
            var nx = -slopeX
            var ny = 1f
            var nz = -slopeZ
            val nl = 1f / sqrt(nx * nx + ny * ny + nz * nz)
            nx *= nl; ny *= nl; nz *= nl
            var vx = -x
            var vy = cam.camY - y
            var vz = cam.camZ - zn
            val vl = 1f / sqrt(vx * vx + vy * vy + vz * vz)
            vx *= vl; vy *= vl; vz *= vl
            val ndv = (nx * vx + ny * vy + nz * vz).coerceIn(0f, 1f)
            val toWhite = smooth(0.36f, 0.58f, h)
            val toGlow = smooth(0.64f, 0.86f, h) * 0.94f
            var ar = mix(mix(br, 0.95f, toWhite), gr * 0.5f, toGlow)
            var ag = mix(mix(bg, 0.96f, toWhite), gg * 0.5f, toGlow)
            var ab = mix(mix(bb, 0.99f, toWhite), gb * 0.5f, toGlow)
            val diffuse = ((nx * KEY_X + ny * KEY_Y + nz * KEY_Z + 0.35f) / 1.35f).coerceIn(0f, 1f)
            var hx = KEY_X + vx; var hy = KEY_Y + vy; var hz = KEY_Z + vz
            val hl = 1f / sqrt(hx * hx + hy * hy + hz * hz)
            hx *= hl; hy *= hl; hz *= hl
            val ndh = (nx * hx + ny * hy + nz * hz).coerceAtLeast(0f)
            val spec = if (gloss > 0f && ndh > 0f) gloss * (1.7f * exp(shine * ln(ndh)) + 0.2f * exp(10f * ln(ndh))) else 0f
            var dx = BACK_X + nx * 0.6f; var dy = BACK_Y + ny * 0.6f; var dz = BACK_Z + nz * 0.6f
            val dl = 1f / sqrt(dx * dx + dy * dy + dz * dz)
            dx *= dl; dy *= dl; dz *= dl
            val through = (-(vx * dx + vy * dy + vz * dz)).coerceAtLeast(0f)
            val rim = (1f - ndv) * (1f - ndv)
            val sss = subsurface * (0.9f * through * through * through + 0.22f * rim) * (0.25f + 0.75f * h.coerceIn(0f, 1f))
            val lit = 0.24f + 0.74f * diffuse
            ar = ar * lit + gr * sss + spec
            ag = ag * lit + gg * sss + spec
            ab = ab * lit + gb * sss + spec
            val fog = haze * 0.9f * zn * sqrt(zn)
            ar = mix(ar, br * 0.6f + 0.08f, fog)
            ag = mix(ag, bg * 0.6f + 0.08f, fog)
            ab = mix(ab, bb * 0.6f + 0.08f, fog)
            val alpha = 1f - haze * smooth(0.4f, 1f, zn)
            return argb(alpha, ar, ag, ab)
        }

        companion object {
            /** The front wall's top edge: the front row's colour, in shade. */
            fun wallTop(front: Int, p: SpectrogramPalette): Int {
                fun ch(shift: Int, base: Float) = (((front ushr shift) and 0xFF) / 255f) * 0.18f + base * 0.05f * 0.82f
                return argb(1f, ch(16, p.base[0]), ch(8, p.base[1]), ch(0, p.base[2]))
            }

            /** The front wall's foot: the floor's colour, nearly black. */
            fun wallFoot(p: SpectrogramPalette): Int = argb(1f, p.base[0] * 0.05f, p.base[1] * 0.05f, p.base[2] * 0.05f)

            private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
            private fun smooth(e0: Float, e1: Float, x: Float): Float {
                val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }
        }
    }

    companion object {
        const val MAX_ROWS = 192
        /** The feed's bins run log-spaced from 20 Hz to 20 kHz. */
        const val BIN_BOTTOM_HZ = 20f
        const val BIN_TOP_HZ = 20_000f
        /**
         * The floor lies this far under the reference, and a full peak
         * [HEADROOM_DB] under it. Measured on dense music: half of all bands
         * lie about 22 dB under the loudest and a tenth within 13, so a wider
         * range put nearly everything near the top.
         */
        const val RANGE_DB = 28f
        const val HEADROOM_DB = 7f
        /** dB an octave of the feed's tilt taken back, about 1 kHz. */
        const val TILT_BACK_DB_PER_OCTAVE = 2.2f
        const val PITCH_KNEE_HZ = 600f
        /** The reference never falls under this, so silence is not raised into a landscape. */
        const val MIN_REFERENCE_DB = -78f
        const val REFERENCE_ATTACK_S = 0.12f
        const val REFERENCE_FALL_DB_S = 2f
        /** Past this far under the reference, it falls faster: [REFERENCE_FAR_RATE] dB/s more for each dB. */
        const val REFERENCE_NEAR_DB = 40f
        const val REFERENCE_FAR_RATE = 1f
        const val SILENT_DB = -200f
        /** World height of a full peak at peak height 1 (the field is 1 deep, about 2 wide). */
        const val HEIGHT_SCALE = 0.06f
        const val DISTANCE = 1.6f
        const val TARGET_Z = 0.5f
        /** The front edge's place in the box, and the back edge's, from its top. */
        const val FRONT_EDGE = 0.985f
        const val BACK_EDGE = 0.16f
        /** The field at the lowest camera is under this many front widths wide. */
        const val MAX_EXTENT = 1.95f
        /** Ring columns kept out of the history: the live one, its copy, and one between them and the oldest. */
        const val GUARD = 3
        /** How far under the front row the wall's texture coordinates run. */
        const val WALL_ROWS = 6f
        const val MAX_STEP_SECONDS = 0.1f
        /** How fast a row rises to a sound and falls from it, in seconds, at smoothness 0 and what smoothness 1 adds. */
        const val ATTACK_TAU_MIN = 0.02f
        const val ATTACK_TAU_RANGE = 0.1f
        const val RELEASE_TAU_MIN = 0.06f
        const val RELEASE_TAU_RANGE = 0.8f
        /** How much of what the pitch blur removed is put back. */
        const val DETAIL = 0.12f
        /** The share of the height the floor's ripple may take, and how far under the floor it is read from. */
        const val RIPPLE = 0.14f
        const val RIPPLE_DB = 36f
        /** Pitch blur, in rows of a 129-row grid, at smoothness 0 and what smoothness 1 adds. */
        const val BLUR_MIN = 0.7f
        const val BLUR_RANGE = 4f

        // Key light: above, toward the camera, a little to the left (unit vector).
        private const val KEY_X = -0.2998f
        private const val KEY_Y = 0.7995f
        private const val KEY_Z = -0.5197f
        // Toward the lamp behind the landscape.
        private const val BACK_X = 0.18f
        private const val BACK_Y = 0.32f
        private const val BACK_Z = 0.93f

        // By quality 1..4: the mesh's rows, texture rows per mesh row, columns
        // across the front edge, and texture columns per mesh column.
        private val MESH_ROWS = intArrayOf(33, 49, 65, 81)
        private val ROW_STRIDE = intArrayOf(2, 2, 2, 2)
        private val FRONT_COLUMNS = intArrayOf(128, 256, 384, 512)
        private val COL_STRIDE = intArrayOf(1, 2, 3, 3)

        fun frontColumnsFor(quality: Int): Int = FRONT_COLUMNS[quality.coerceIn(1, 4) - 1]

        /**
         * The pitch at depth [t] (0 the front, 1 the back) between [minHz] and
         * [maxHz]: even in ln(1 + f / [PITCH_KNEE_HZ]) (about the mel scale),
         * which is near even in Hz under the knee and by the octave over it. A note's harmonics,
         * evenly spaced in Hz, then lie as evenly spaced bars across the
         * low and middle of the landscape, where by the octave alone they
         * crowd into thin lines toward the back.
         */
        fun rowHz(t: Float, minHz: Float, maxHz: Float): Float {
            val a = ln(1f + minHz / PITCH_KNEE_HZ)
            val b = ln(1f + maxHz / PITCH_KNEE_HZ)
            return PITCH_KNEE_HZ * (exp(a + (b - a) * t) - 1f)
        }

        /**
         * A level [t] (0 at the floor, 1 at a full peak) as a height: nearly
         * flat by the floor, so a noise floor is a ripple and not a field of
         * bumps, and levelling off at the top, so a loud note is a plateau.
         */
        fun heightOf(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        private fun argb(a: Float, r: Float, g: Float, b: Float): Int {
            fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}
