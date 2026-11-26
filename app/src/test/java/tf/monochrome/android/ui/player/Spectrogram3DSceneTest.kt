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

/**
 * The cover's 3D spectrogram ([Spectrogram3DScene]): it keeps time by the
 * clock, not by frames; bass is at the front and the newest sound at the
 * right; the mesh is drawn back to front within drawVertices' limits; and its
 * settings travel with a theme without the listener's own quality.
 */
class Spectrogram3DSceneTest {
    private val settings = Spectrogram3DSettings()
    private val silence = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { -60f }

    /** Loud below [hz] and silent above it, in the analyzer's units. */
    private fun bassOnly(hz: Float = 120f) = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { i ->
        val f = 20f * Math.pow(1000.0, i / (SpectrumAnalyzerTap.OUTPUT_BINS - 1.0)).toFloat()
        if (f < hz) 30f else -60f
    }

    @Test
    fun `heights run from the floor to a full peak`() {
        assertEquals(0f, Spectrogram3DScene.heightOf(Spectrogram3DScene.FLOOR_DB - 10f), 0f)
        assertEquals(0f, Spectrogram3DScene.heightOf(Spectrogram3DScene.FLOOR_DB), 0f)
        assertEquals(1f, Spectrogram3DScene.heightOf(Spectrogram3DScene.TOP_DB + 10f), 0f)
        var last = -1f
        for (db in -30..40) {
            val h = Spectrogram3DScene.heightOf(db.toFloat())
            assertTrue(h >= last)
            last = h
        }
        // A sample that is not a number lies on the floor, never in the mesh.
        val scene = Spectrogram3DScene()
        scene.advance(FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { Float.NaN }, settings, 0.5f)
        for (j in 0 until scene.rows) assertEquals(0f, scene.liveHeight(j), 0f)
    }

    @Test
    fun `it keeps time by the clock, so every frame rate scrolls alike`() {
        val seconds = 3f
        val results = listOf(30f, 60f, 120f, 165f).map { fps ->
            val scene = Spectrogram3DScene()
            val frames = (seconds * fps).toInt()
            repeat(frames) { scene.advance(silence, settings, 1f / fps) }
            scene.filled to scene.phase
        }
        val expected = (seconds / Spectrogram3DScene().columnSeconds(settings)).toInt()
        results.forEach { (filled, _) -> assertTrue("$results", kotlin.math.abs(filled - expected) <= 1) }
        // And the speed setting is seconds across the front edge.
        val scene = Spectrogram3DScene()
        scene.advance(silence, settings, 0f)
        assertEquals(settings.secondsAcross, scene.columnSeconds(settings) * scene.baseColumns, 1e-4f)
    }

    @Test
    fun `a long gap is not caught up on`() {
        val scene = Spectrogram3DScene()
        scene.advance(silence, settings, 60f)
        val perStep = (Spectrogram3DScene.MAX_STEP_SECONDS / scene.columnSeconds(settings)).toInt()
        assertTrue(scene.filled <= perStep + 1)
    }

    @Test
    fun `bass stands at the front, and the newest sound enters at the right`() {
        val scene = Spectrogram3DScene()
        repeat(60) { scene.advance(bassOnly(), settings, 1f / 60) }
        assertTrue(scene.liveHeight(0) > 0.5f)
        assertTrue(scene.liveHeight(scene.rows - 1) < 0.05f)
        // Then silence: the bass moves into the history, older the further left.
        repeat(30) { scene.advance(silence, settings, 1f / 60) }
        assertTrue(scene.liveHeight(0) < 0.2f)
        val newest = scene.historyHeight(0, 0)
        val older = (1 until scene.filled).maxOf { scene.historyHeight(it, 0) }
        assertTrue(older > newest)
    }

    @Test
    fun `the mesh is drawn back to front and fits drawVertices`() {
        for (quality in 1..4) {
            val s = settings.copy(quality = quality, angleDeg = Spectrogram3DSettings.MIN_ANGLE_DEG)
            val scene = Spectrogram3DScene()
            // A full history: the widest field there is.
            repeat(3000) { scene.advance(bassOnly(), s, 1f / 60) }
            scene.build(1080f, 432f, s, 0xFF3355FF.toInt(), 0xFFFF3344.toInt())
            assertTrue(scene.vertexCount in 1 until 32_768)
            assertEquals(0, scene.indexCount % 3)
            for (i in 0 until scene.indexCount) {
                val v = scene.indices[i].toInt()
                assertTrue(v in 0 until scene.vertexCount)
            }
            val rows = scene.rows
            // The first triangle is on the back strip, the last on the front one.
            val firstRows = (0 until 3).map { scene.indices[it] % rows }.toSet()
            val lastRows = (scene.indexCount - 3 until scene.indexCount).map { scene.indices[it] % rows }.toSet()
            assertEquals(setOf(rows - 2, rows - 1), firstRows)
            assertEquals(setOf(0, 1), lastRows)
        }
    }

    @Test
    fun `the front edge sits on the box's bottom and the field reaches its sides at the back`() {
        for (angle in listOf(5f, 18f, 35f, 60f)) {
            val cam = Spectrogram3DScene.Camera.of(angle, 1080f, 432f)
            val out = FloatArray(2)
            cam.project(0f, 0f, 0f, out, 0)
            assertEquals(432f * Spectrogram3DScene.FRONT_EDGE, out[1], 0.5f)
            cam.project(cam.frontHalfWidth, 0f, 0f, out, 0)
            assertEquals(1080f, out[0], 1f)
            cam.project(cam.fieldHalfWidth, 0f, 1f, out, 0)
            assertTrue("$angle", out[0] >= 1079f)
            // The back is above the front, and a full peak stays inside the box.
            cam.project(0f, 0f, 1f, out, 0)
            assertTrue(out[1] < 432f * Spectrogram3DScene.FRONT_EDGE)
            cam.project(0f, Spectrogram3DScene.HEIGHT_SCALE, 1f, out, 0)
            assertTrue("$angle: ${out[1]}", out[1] >= -1f)
        }
    }

    @Test
    fun `haze fades the back into the cover and leaves the front solid`() {
        val scene = Spectrogram3DScene()
        val hazy = settings.copy(haze = 1f)
        repeat(120) { scene.advance(bassOnly(), hazy, 1f / 60) }
        scene.build(1080f, 432f, hazy, 0xFF3355FF.toInt(), 0xFFFF3344.toInt())
        val rows = scene.rows
        val frontAlpha = scene.colors[0] ushr 24
        val backAlpha = scene.colors[rows - 1] ushr 24
        assertEquals(255, frontAlpha)
        assertTrue(backAlpha < 8)
        // Without haze the back is solid too.
        scene.build(1080f, 432f, settings.copy(haze = 0f), 0xFF3355FF.toInt(), 0xFFFF3344.toInt())
        assertEquals(255, scene.colors[rows - 1] ushr 24)
    }

    @Test
    fun `a new song clears the landscape`() {
        val scene = Spectrogram3DScene()
        repeat(120) { scene.advance(bassOnly(), settings, 1f / 60) }
        scene.clear()
        assertEquals(0, scene.filled)
        assertEquals(0f, scene.liveHeight(0), 0f)
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
        // Saved and synced as the other settings blobs are.
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(mine, json.decodeFromString<Spectrogram3DSettings>(json.encodeToString(mine)))
        assertEquals(Spectrogram3DSettings.DEFAULT, json.decodeFromString<Spectrogram3DSettings>("{}"))
        // Published: the look without the personal settings.
        val pack = ThemePack(name = "Wax", visualizer = ThemeVisualizer(spectrogram3d = mine))
        val shared = ThemePackCodec.decode(ThemePackCodec.encode(ThemePackCodec.forSharing(pack)))!!
        val look = shared.visualizer!!.spectrogram3d!!
        assertEquals(40f, look.angleDeg, 0f)
        assertEquals(0.9f, look.gloss, 0f)
        assertEquals(Spectrogram3DSettings.DEFAULT.quality, look.quality)
        assertTrue(look.preview)
        // Installed over someone's own: their quality and preview stay.
        val theirs = Spectrogram3DSettings(quality = 1, preview = false)
        val applied = look.withPersonalFrom(theirs).clamped()
        assertEquals(40f, applied.angleDeg, 0f)
        assertEquals(1, applied.quality)
        assertFalse(applied.preview)
    }
}
