package tf.monochrome.android.ui.player

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.Spectrogram3DSettings
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePackCodec
import tf.monochrome.android.domain.model.ThemeVisualizer
import kotlin.math.abs

/**
 * The cover's 3D spectrogram ([Spectrogram3DScene]): it keeps time by the
 * clock, not by frames; bass is at the front and the newest sound at the
 * right; quiet lies low and silence is flat; its textures say what its
 * history holds; the mesh is drawn back to front within drawVertices' limits;
 * and its settings travel with a theme without the listener's own quality.
 */
class Spectrogram3DSceneTest {
    private val settings = Spectrogram3DSettings()
    private val palette = SpectrogramPalette.of(0xFF6A6CFF.toInt(), 0xFFE5334F.toInt())
    private val silence = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { -140f }

    private fun hzOf(bin: Int) = 20f * Math.pow(1000.0, bin / (SpectrumAnalyzerTap.OUTPUT_BINS - 1.0)).toFloat()

    /** [level] dB below [hz] and silent above it, in the feed's units. */
    private fun bassOnly(level: Float = -30f, hz: Float = 120f) =
        FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { i -> if (hzOf(i) < hz) level else -140f }

    /**
     * A sound every row reads as [level]: the scene takes back some of the
     * feed's tilt (bass counts for more than treble), so the feed is given
     * that much tilt here.
     */
    private fun flat(level: Float) = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { i ->
        level + Spectrogram3DScene.TILT_BACK_DB_PER_OCTAVE * (Math.log(hzOf(i) / 1000.0) / Math.log(2.0)).toFloat()
    }

    @Test
    fun `heights are flat by the floor and level off at a full peak`() {
        assertEquals(0f, Spectrogram3DScene.heightOf(-1f), 0f)
        assertEquals(0f, Spectrogram3DScene.heightOf(0f), 0f)
        assertEquals(1f, Spectrogram3DScene.heightOf(1f), 0f)
        assertEquals(1f, Spectrogram3DScene.heightOf(3f), 0f)
        assertTrue(Spectrogram3DScene.heightOf(0.2f) < 0.11f)
        assertTrue(Spectrogram3DScene.heightOf(0.9f) > 0.97f)
        var last = -1f
        for (i in 0..100) {
            val h = Spectrogram3DScene.heightOf(i / 100f)
            assertTrue(h >= last)
            last = h
        }
        // A sample that is not a number lies on the floor, never in the mesh.
        val scene = Spectrogram3DScene()
        repeat(30) { scene.advance(FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { Float.NaN }, settings, 1f / 60) }
        for (j in 0 until scene.texRows) assertEquals(0f, scene.liveHeight(j), 0f)
    }

    @Test
    fun `it keeps time by the clock, so every frame rate scrolls alike`() {
        val seconds = 3f
        val pushed = listOf(30f, 60f, 120f, 165f).map { fps ->
            val scene = Spectrogram3DScene()
            repeat((seconds * fps).toInt()) { scene.advance(silence, settings, 1f / fps) }
            scene.pushes
        }
        val expected = (seconds / Spectrogram3DScene().apply { prepare(settings) }.columnSeconds(settings)).toLong()
        pushed.forEach { assertTrue("$pushed against $expected", abs(it - expected) <= 1) }
        // And the speed setting is seconds across the front edge.
        val scene = Spectrogram3DScene().apply { prepare(settings) }
        assertEquals(settings.secondsAcross, scene.columnSeconds(settings) * scene.frontColumns, 1e-4f)
    }

    @Test
    fun `a long gap is not caught up on`() {
        val scene = Spectrogram3DScene()
        scene.advance(silence, settings, 60f)
        val perStep = (Spectrogram3DScene.MAX_STEP_SECONDS / scene.columnSeconds(settings)).toInt()
        assertTrue(scene.pushes <= perStep + 1)
    }

    @Test
    fun `bass stands at the front, and the newest sound enters at the right`() {
        val scene = Spectrogram3DScene()
        repeat(60) { scene.advance(bassOnly(), settings, 1f / 60) }
        assertTrue(scene.liveHeight(0) > 0.5f)
        assertTrue(scene.liveHeight(scene.texRows - 1) < 0.02f)
        // Then silence (a second: a note is held as it dies): the bass moves
        // into the history, older the further left.
        repeat(60) { scene.advance(silence, settings, 1f / 60) }
        assertTrue(scene.liveHeight(0) < 0.2f)
        val newest = scene.historyHeight(0, 0)
        val older = (1..60).maxOf { scene.historyHeight(it, 0) }
        assertTrue(older > newest)
    }

    @Test
    fun `quiet lies low, silence is flat, and the level is found again after either`() {
        val scene = Spectrogram3DScene()
        fun run(bins: FloatArray, seconds: Float) = repeat((seconds * 60).toInt()) { scene.advance(bins, settings, 1f / 60) }
        run(flat(-30f), 2f)
        // The loudest row sets the reference: the level, within the dB a
        // row's own width of tilt adds.
        val loudest = scene.referenceDb
        assertEquals(-30f, loudest, 1f)
        assertTrue(scene.liveHeight(40) > 0.95f)
        // 22 dB quieter, once the held note has died: under half the height.
        // The reference has drifted 3 dB down meanwhile, so the sound is
        // about 19 dB under it, 9 over the floor, of the 21 between floor
        // and full peak.
        run(flat(-52f), 1.5f)
        assertEquals(loudest - 3f, scene.referenceDb, 0.3f)
        val under = scene.referenceDb - (-52f)
        val t = (Spectrogram3DScene.RANGE_DB - under) / (Spectrogram3DScene.RANGE_DB - Spectrogram3DScene.HEADROOM_DB)
        val expected = Spectrogram3DScene.RIPPLE + (1f - Spectrogram3DScene.RIPPLE) * Spectrogram3DScene.heightOf(t)
        assertEquals(expected, scene.liveHeight(40), 0.05f)
        assertTrue("$expected", expected in 0.3f..0.6f)
        // Under the floor it is a ripple, no more.
        run(flat(-70f), 1.5f)
        assertTrue(scene.liveHeight(40) in 0.02f..Spectrogram3DScene.RIPPLE)
        // Silence: flat, and a rest only lets the reference drift (2 dB a second).
        val beforeRest = scene.referenceDb
        run(silence, 3f)
        assertTrue(scene.liveHeight(40) < 0.01f)
        assertEquals(beforeRest - 6f, scene.referenceDb, 0.2f)
        // Never under the bottom: silence stays flat for ever.
        run(silence, 40f)
        assertEquals(Spectrogram3DScene.MIN_REFERENCE_DB, scene.referenceDb, 0.01f)
        assertTrue(scene.liveHeight(40) < 0.01f)
        // And the first sound after it sets the level at once.
        scene.advance(flat(-20f), settings, 1f / 60)
        assertEquals(-20f, scene.referenceDb, 1f)
        // Sound far under the reference pulls it down faster than the drift: a
        // far quieter song is found in seconds.
        run(flat(-20f), 1f)
        run(flat(-70f), 1f)
        assertTrue(scene.referenceDb < -25f)
    }

    @Test
    fun `a note is there at once and held as it dies`() {
        val scene = Spectrogram3DScene()
        fun run(bins: FloatArray, seconds: Float) = repeat((seconds * 60).toInt()) { scene.advance(bins, settings, 1f / 60) }
        run(flat(-30f), 1f)
        run(silence, 0.1f)
        // A tenth of a second after it stopped, most of it still stands...
        val held = scene.liveHeight(40)
        assertTrue("$held", held > 0.6f)
        // ...and a tenth of a second after it starts again it is nearly back.
        run(silence, 2f)
        run(flat(-31f), 0.1f)
        assertTrue(scene.liveHeight(40) > 0.8f)
    }

    @Test
    fun `pitch is spread near evenly in Hz in the bass and by the octave above`() {
        val lo = 30f; val hi = 5000f
        assertEquals(lo, Spectrogram3DScene.rowHz(0f, lo, hi), 0.01f)
        assertEquals(hi, Spectrogram3DScene.rowHz(1f, lo, hi), 0.5f)
        var last = 0f
        for (i in 0..20) {
            val hz = Spectrogram3DScene.rowHz(i / 20f, lo, hi)
            assertTrue(hz > last)
            last = hz
        }
        // Three harmonics of 110 Hz are near evenly spaced in depth...
        fun depth(hz: Float): Float {
            var a = 0f; var b = 1f
            repeat(40) { val m = (a + b) / 2f; if (Spectrogram3DScene.rowHz(m, lo, hi) < hz) a = m else b = m }
            return a
        }
        val d1 = depth(220f) - depth(110f)
        val d2 = depth(330f) - depth(220f)
        assertTrue(d2 / d1 in 0.7f..1f)
        // ...where by the octave the second gap would be 0.58 of the first.
        // And the top octave takes far less room than an even spread would give it.
        assertTrue(depth(5000f) - depth(2500f) < 0.35f)
    }

    @Test
    fun `the textures hold the history, 16 bits a height, with the live column and its copy at the right`() {
        val scene = Spectrogram3DScene()
        repeat(90) { scene.advance(bassOnly(-30f, 400f), settings, 1f / 60) }
        assertEquals(scene.texColumns * scene.texRows, scene.texHi.size)
        assertEquals(0, scene.texColumns % 4)
        val live = scene.liveSlot
        val copy = (live - 1 + scene.texColumns) % scene.texColumns
        val newest = (live + 1) % scene.texColumns
        for (j in 0 until scene.texRows) {
            assertEquals(scene.liveHeight(j), scene.textureHeight(live, j), 1f / 65535f)
            assertEquals(scene.liveHeight(j), scene.textureHeight(copy, j), 1f / 65535f)
            assertEquals(scene.historyHeight(0, j), scene.textureHeight(newest, j), 1f / 65535f)
        }
        // The shader's decode is linear in the two bytes, so the GPU's
        // filtering between two texels is the mean of their heights.
        val a = 0x12F0; val b = 0x1310
        val mean = ((a shr 8) + (b shr 8)) / 2f * 256f + ((a and 255) + (b and 255)) / 2f
        assertEquals((a + b) / 2f, mean, 0f)
    }

    @Test
    fun `the mesh is drawn back to front, a point rides its column, and it fits drawVertices`() {
        for (quality in 1..4) {
            val s = settings.copy(quality = quality, angleDeg = Spectrogram3DSettings.MIN_ANGLE_DEG)
            val scene = Spectrogram3DScene()
            repeat(600) { scene.advance(bassOnly(), s, 1f / 60) }
            for (lit in listOf(false, true)) {
                scene.build(1080f, 432f, s, palette, lit)
                assertTrue(scene.vertexCount in 1 until 32_768)
                assertEquals(0, scene.indexCount % 3)
                for (i in 0 until scene.indexCount) assertTrue(scene.indices[i].toInt() in 0 until scene.vertexCount)
                val rows = (scene.vertexCount - scene.meshColumns * 2) / scene.meshColumns
                val surface = scene.meshColumns * rows
                // The first triangle is on the back strip, the surface's last on
                // the front one, and the front wall comes after all of it.
                val end = scene.surfaceIndexCount
                assertEquals(setOf(rows - 2, rows - 1), (0 until 3).map { scene.indices[it] % rows }.toSet())
                assertEquals(setOf(0, 1), (end - 3 until end).map { scene.indices[it] % rows }.toSet())
                for (i in end until scene.indexCount) assertTrue(scene.indices[i] >= surface)
                // Every point says where in the history it is: a row's middle,
                // and for the wall's foot a place under the front row.
                for (v in 0 until surface) {
                    val row = scene.texs[v * 2 + 1]
                    assertEquals(0.5f, row - row.toInt(), 0f)
                    assertTrue(row < scene.texRows)
                }
                assertTrue(scene.texs[(surface + 1) * 2 + 1] < 0f)
            }
        }
        // A point rides its column of sound: after a push, the columns of
        // the history that were mesh columns are mesh columns still (but for
        // the ends), so nothing swims as the landscape scrolls.
        val scene = Spectrogram3DScene()
        val s = settings.copy(quality = 3)
        repeat(300) { scene.advance(silence, s, 1f / 60) }
        fun slots(): Set<Int> {
            scene.build(1080f, 432f, s, palette, lit = false)
            val rows = (scene.vertexCount - scene.meshColumns * 2) / scene.meshColumns
            return (2 until scene.meshColumns - 2).map { c ->
                (scene.texs[c * rows * 2] - 0.5f).toInt() % scene.texColumns
            }.toSet()
        }
        val before = slots()
        val pushes = scene.pushes
        while (scene.pushes == pushes) scene.advance(silence, s, 1f / 480)
        val after = slots()
        assertTrue((before intersect after).size >= before.size - 3)
    }

    @Test
    fun `the ground runs from the box's bottom to near its top and fills it side to side`() {
        for (angle in listOf(5f, 18f, 35f, 60f)) {
            val cam = Spectrogram3DScene.Camera.of(angle, 1080f, 432f)
            val out = FloatArray(2)
            cam.project(0f, 0f, 0f, out, 0)
            assertEquals(432f * Spectrogram3DScene.FRONT_EDGE, out[1], 0.5f)
            cam.project(cam.frontHalfWidth, 0f, 0f, out, 0)
            assertEquals(1080f, out[0], 1f)
            cam.project(cam.fieldHalfWidth, 0f, 1f, out, 0)
            assertTrue("$angle", out[0] >= 1079f)
            // The back of the ground is near the top, whatever the angle.
            cam.project(0f, 0f, 1f, out, 0)
            assertEquals(432f * Spectrogram3DScene.BACK_EDGE, out[1], 0.5f)
            assertTrue(cam.fieldHalfWidth / cam.frontHalfWidth < Spectrogram3DScene.MAX_EXTENT)
        }
    }

    @Test
    fun `without the shader, haze fades the back into the cover and leaves the front solid`() {
        val scene = Spectrogram3DScene()
        val hazy = settings.copy(haze = 1f, quality = 1)
        repeat(120) { scene.advance(bassOnly(), hazy, 1f / 60) }
        scene.build(1080f, 432f, hazy, palette, lit = true)
        val rows = (scene.vertexCount - scene.meshColumns * 2) / scene.meshColumns
        assertEquals(255, scene.colors[0] ushr 24)
        assertTrue((scene.colors[rows - 1] ushr 24) < 8)
        scene.build(1080f, 432f, hazy.copy(haze = 0f), palette, lit = true)
        assertEquals(255, scene.colors[rows - 1] ushr 24)
    }

    @Test
    fun `the palette keeps the album's hues and makes them fit to draw with`() {
        // A pale pastel and a near-black: a saturated floor, tops at full strength.
        val pale = SpectrogramPalette.of(0xFFD0BCFF.toInt(), 0xFF140A0A.toInt())
        val floor = pale.base
        assertTrue(floor.max() - floor.min() > 0.3f)
        assertTrue(floor.max() in 0.6f..0.88f)
        assertEquals(1f, pale.glow.max(), 1e-4f)
        // A grey second colour has no hue: the tops take one apart from the floor's.
        val grey = SpectrogramPalette.of(0xFF3355FF.toInt(), 0xFF202020.toInt())
        assertTrue(grey.glow[0] > grey.glow[2] || grey.glow[1] > grey.glow[2])
        // A red stays red.
        val red = SpectrogramPalette.of(0xFF3355FF.toInt(), 0xFFE5334F.toInt())
        assertTrue(red.glow[0] > red.glow[1] && red.glow[0] > red.glow[2])
    }

    @Test
    fun `a new song clears the landscape`() {
        val scene = Spectrogram3DScene()
        repeat(120) { scene.advance(bassOnly(), settings, 1f / 60) }
        scene.clear()
        assertEquals(0L, scene.pushes)
        assertEquals(0f, scene.liveHeight(0), 0f)
        assertEquals(0f, scene.historyHeight(3, 0), 0f)
        assertEquals(Spectrogram3DScene.MIN_REFERENCE_DB, scene.referenceDb, 0f)
        assertTrue(scene.texHi.all { it == 0.toByte() })
    }

    @Test
    fun `the cover's button walks Spectrum, Wave Candy, the 3D spectrogram, and skips what cannot show`() {
        assertEquals(CoverVisual.WAVE_CANDY, CoverVisual.SPECTRUM.next(true, true))
        assertEquals(CoverVisual.SPECTROGRAM_3D, CoverVisual.WAVE_CANDY.next(true, true))
        assertEquals(CoverVisual.SPECTRUM, CoverVisual.SPECTROGRAM_3D.next(true, true))
        assertEquals(CoverVisual.SPECTROGRAM_3D, CoverVisual.SPECTRUM.next(false, true))
        assertEquals(CoverVisual.SPECTRUM, CoverVisual.WAVE_CANDY.next(true, false))
        assertEquals(CoverVisual.SPECTRUM, CoverVisual.SPECTRUM.next(false, false))
    }

    @Test
    fun `settings are clamped, and a pitch range keeps two octaves`() {
        val wild = Spectrogram3DSettings(
            secondsAcross = 999f, angleDeg = -5f, peakHeight = Float.NaN, gloss = 7f,
            subsurface = -1f, smoothness = Float.POSITIVE_INFINITY, haze = 2f,
            minHz = 1500f, maxHz = 1600f, quality = 9,
        ).clamped()
        assertEquals(Spectrogram3DSettings.MAX_SECONDS, wild.secondsAcross, 0f)
        assertEquals(Spectrogram3DSettings.MIN_ANGLE_DEG, wild.angleDeg, 0f)
        assertEquals(Spectrogram3DSettings.DEFAULT.peakHeight, wild.peakHeight, 0f)
        assertEquals(1f, wild.gloss, 0f)
        assertEquals(0f, wild.subsurface, 0f)
        assertEquals(Spectrogram3DSettings.DEFAULT.smoothness, wild.smoothness, 0f)
        assertEquals(1f, wild.haze, 0f)
        assertTrue(wild.maxHz >= wild.minHz * Spectrogram3DSettings.MIN_RANGE_RATIO)
        assertEquals(Spectrogram3DSettings.MAX_QUALITY, wild.quality)
        assertEquals(Spectrogram3DSettings.DEFAULT, Spectrogram3DSettings.DEFAULT.clamped())
    }

    @Test
    fun `the look travels with a theme, the quality and the preview switch stay on the phone`() {
        val mine = Spectrogram3DSettings(angleDeg = 40f, gloss = 0.9f, subsurface = 0.2f, quality = 4, preview = false)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(mine, json.decodeFromString<Spectrogram3DSettings>(json.encodeToString(mine)))
        assertEquals(Spectrogram3DSettings.DEFAULT, json.decodeFromString<Spectrogram3DSettings>("{}"))
        val pack = ThemePack(name = "Wax", visualizer = ThemeVisualizer(spectrogram3d = mine))
        val shared = ThemePackCodec.decode(ThemePackCodec.encode(ThemePackCodec.forSharing(pack)))!!
        val look = shared.visualizer!!.spectrogram3d!!
        assertEquals(40f, look.angleDeg, 0f)
        assertEquals(0.9f, look.gloss, 0f)
        assertEquals(Spectrogram3DSettings.DEFAULT.quality, look.quality)
        assertTrue(look.preview)
        val theirs = Spectrogram3DSettings(quality = 1, preview = false)
        val applied = look.withPersonalFrom(theirs).clamped()
        assertEquals(40f, applied.angleDeg, 0f)
        assertEquals(1, applied.quality)
        assertFalse(applied.preview)
    }
}
