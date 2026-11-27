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
        // 14 dB quieter, once the held note has died: part of the height.
        // The reference has drifted 3 dB down meanwhile, so the sound is
        // about 11 dB under it, 12 over the floor, of the 20.5 between floor
        // and full peak.
        run(flat(-44f), 1.5f)
        assertEquals(loudest - 3f, scene.referenceDb, 0.3f)
        val under = scene.referenceDb - (-44f)
        val t = (Spectrogram3DScene.RANGE_DB - under) / (Spectrogram3DScene.RANGE_DB - Spectrogram3DScene.HEADROOM_DB)
        val expected = Spectrogram3DScene.RIPPLE + (1f - Spectrogram3DScene.RIPPLE) * Spectrogram3DScene.heightOf(t)
        assertEquals(expected, scene.liveHeight(40), 0.05f)
        assertTrue("$expected", expected in 0.3f..0.85f)
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
    fun `a note is there as fast as attack says and stands as long as release says`() {
        fun heightAfter(s: Spectrogram3DSettings, vararg steps: Pair<FloatArray, Int>): Float {
            val scene = Spectrogram3DScene()
            for ((bins, frames) in steps) repeat(frames) { scene.advance(bins, s, 1f / 60) }
            return scene.liveHeight(40)
        }
        // As shipped: a tenth of a second after it stopped, about half of it still stands...
        val held = heightAfter(settings, flat(-30f) to 60, silence to 6)
        assertTrue("$held", held in 0.4f..0.7f)
        // ...and a tenth of a second after it starts again it is back.
        assertTrue(heightAfter(settings, flat(-30f) to 60, silence to 120, flat(-31f) to 6) > 0.9f)
        // Release 0 drops it in a frame; a long release holds it.
        val instant = settings.copy(attackMs = 0f, releaseMs = 0f)
        assertTrue(heightAfter(instant, flat(-30f) to 60, silence to 1) < 0.02f)
        assertTrue(heightAfter(settings.copy(releaseMs = Spectrogram3DSettings.MAX_RELEASE_MS), flat(-30f) to 60, silence to 6) > 0.9f)
        // Attack 0 has it at full height in a frame; the slowest attack is still rising then.
        assertTrue(heightAfter(instant, flat(-30f) to 30, silence to 30, flat(-30f) to 1) > 0.95f)
        val slow = settings.copy(attackMs = Spectrogram3DSettings.MAX_ATTACK_MS, releaseMs = 0f)
        assertTrue(heightAfter(slow, flat(-30f) to 30, silence to 30, flat(-30f) to 1) < 0.3f)
        assertEquals(1f, Spectrogram3DScene.follow(1f / 120, 0f), 0f)
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
                // drawVertices reads its indices as unsigned 16-bit numbers:
                // up to 65,535 points, the upper half of them negative as
                // Kotlin sees a Short.
                fun index(i: Int) = scene.indices[i].toInt() and 0xFFFF
                assertTrue("$quality: ${scene.vertexCount}", scene.vertexCount in 1 until 65_536)
                assertEquals(0, scene.indexCount % 3)
                for (i in 0 until scene.indexCount) assertTrue(index(i) in 0 until scene.vertexCount)
                val rows = (scene.vertexCount - scene.meshColumns * 2) / scene.meshColumns
                val surface = scene.meshColumns * rows
                // The first triangle is on the back strip, the surface's last on
                // the front one, and the front wall comes after all of it.
                val end = scene.surfaceIndexCount
                assertEquals(setOf(rows - 2, rows - 1), (0 until 3).map { index(it) % rows }.toSet())
                assertEquals(setOf(0, 1), (end - 3 until end).map { index(it) % rows }.toSet())
                for (i in end until scene.indexCount) assertTrue(index(i) >= surface)
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
    fun `the ground starts at the box's bottom, fills it side to side, and takes more of it the higher the camera`() {
        var lastBack = 432f
        for (angle in listOf(5f, 18f, 35f, 60f)) {
            val cam = Spectrogram3DScene.Camera.of(angle, 1080f, 432f)
            val out = FloatArray(2)
            cam.project(0f, 0f, 0f, out, 0)
            assertEquals(432f * Spectrogram3DScene.FRONT_EDGE, out[1], 0.5f)
            cam.project(cam.frontHalfWidth, 0f, 0f, out, 0)
            assertEquals(1080f, out[0], 1f)
            cam.project(cam.fieldHalfWidth, 0f, 1f, out, 0)
            assertTrue("$angle", out[0] >= 1079f)
            // The back of the ground climbs with the angle: the slider is a
            // camera that moves, not one fitted back to the same picture.
            cam.project(0f, 0f, 1f, out, 0)
            assertEquals(432f * (Spectrogram3DScene.FRONT_EDGE - Spectrogram3DScene.groundShare(angle)), out[1], 0.5f)
            assertTrue("$angle: ${out[1]} is not well above $lastBack", out[1] < lastBack - 15f)
            assertTrue("$angle", out[1] > 0f)
            lastBack = out[1]
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
    fun `the lyric lamp stands where the words are across the picture, and Low walks for no shadows`() {
        val scene = Spectrogram3DScene()
        repeat(60) { scene.advance(flat(-30f), settings, 1f / 60) }
        val lamp = SpectrogramLamp()
        fun lampAt(x: Float, width: Float): Float {
            lamp.x = x; lamp.width = width; lamp.strength = 1f
            scene.build(1080f, 432f, settings, palette, lit = false, lamp = lamp)
            return scene.lampX
        }
        // The middle of the picture is the middle of the field; left is left.
        assertEquals(0f, lampAt(540f, 200f), 1e-4f)
        assertTrue(lampAt(200f, 200f) < -0.1f)
        assertEquals(-lampAt(200f, 200f), lampAt(880f, 200f), 1e-4f)
        // A point of the world at the lamp's height and depth is drawn back where the words are.
        val cam = Spectrogram3DScene.Camera.of(settings.angleDeg, 1080f, 432f)
        val out = FloatArray(2)
        cam.project(lampAt(310f, 200f), Spectrogram3DScene.LAMP_Y, Spectrogram3DScene.LAMP_Z, out, 0)
        assertEquals(310f, out[0], 0.5f)
        // A wider word is a wider lamp, and a single letter is never a pin.
        lampAt(540f, 600f)
        val wide = scene.lampHalfWidth
        lampAt(540f, 4f)
        assertTrue(wide > scene.lampHalfWidth)
        assertEquals(Spectrogram3DScene.MIN_LAMP_HALF_WIDTH, scene.lampHalfWidth, 0f)
        // No lamp given: the middle, as the sun alone lights it.
        scene.build(1080f, 432f, settings, palette, lit = false)
        assertEquals(0f, scene.lampX, 0f)
        // The walk toward the light: none at Low (which the shader does not draw), longer with quality, within the shader's loop.
        assertEquals(0f, Spectrogram3DScene.shadowSteps(1), 0f)
        assertTrue(Spectrogram3DScene.shadowSteps(2) < Spectrogram3DScene.shadowSteps(3))
        assertTrue(Spectrogram3DScene.shadowSteps(4) <= 16f)
        assertTrue(SPECTROGRAM_3D_SHADER.contains("i < 16;"))
        // The sun is a direction: behind the field, above it.
        val sun = Spectrogram3DScene.SUN
        assertEquals(1f, sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2], 0.01f)
        assertTrue(sun[1] > 0.2f && sun[2] > 0.5f)
    }

    @Test
    fun `the grid is fine enough for a phone's width, and the app can say which way it draws`() {
        // At Medium, as shipped, on a phone 1440 px wide: a column of the
        // history is under 4 px at the front and a mesh column under 8, and
        // a row of the history under 5 px on average. The first sizes were a
        // third of these and drew blocks.
        val scene = Spectrogram3DScene()
        repeat(60) { scene.advance(flat(-30f), settings, 1f / 60) }
        scene.build(1440f, 576f, settings, palette, lit = false)
        assertEquals(2, settings.quality)
        assertTrue(1440f / scene.frontColumns < 4f)
        assertTrue(576f * Spectrogram3DScene.groundShare(settings.angleDeg) / scene.texRows < 5f)
        var lastRows = 0
        var lastColumns = 0
        for (quality in 1..4) {
            val s = settings.copy(quality = quality)
            scene.build(1440f, 576f, s, palette, lit = false)
            assertTrue(scene.texRows >= lastRows && scene.frontColumns >= lastColumns)
            assertTrue(scene.texRows <= Spectrogram3DScene.MAX_ROWS)
            lastRows = scene.texRows
            lastColumns = scene.frontColumns
        }
        // Which way a phone draws it: the shader from Android 13 unless
        // Quality is Low or the phone refused it, and the reason otherwise.
        assertEquals(SpectrogramRenderer.SHADER, SpectrogramRenderer.of(2, 33, shaderCompiled = true))
        assertEquals(SpectrogramRenderer.SHADER, SpectrogramRenderer.of(4, 36, shaderCompiled = true))
        assertEquals(SpectrogramRenderer.MESH_QUALITY_LOW, SpectrogramRenderer.of(1, 36, shaderCompiled = true))
        assertEquals(SpectrogramRenderer.MESH_SHADER_REFUSED, SpectrogramRenderer.of(3, 36, shaderCompiled = false))
        assertEquals(SpectrogramRenderer.MESH_ANDROID_BELOW_13, SpectrogramRenderer.of(3, 32, shaderCompiled = false))
    }

    @Test
    fun `sliders saved for the first renderer start again from the look as shipped`() {
        val json = Json { ignoreUnknownKeys = true }
        // What the owner's phone had saved when it looked nothing like the demo: no "look" in it.
        val old = """{"secondsAcross":2.79,"angleDeg":16.4,"peakHeight":1.85,"gloss":0.09,"smoothness":0.99,""" +
            """"shadows":0.22,"minHz":20.0,"maxHz":19999.992,"quality":4,"preview":false}"""
        assertTrue(Spectrogram3DSettings.savedForAnOlderLook(old))
        val now = Spectrogram3DSettings.fromSaved(old, json.decodeFromString<Spectrogram3DSettings>(old)).clamped()
        assertEquals(Spectrogram3DSettings.DEFAULT.copy(quality = 4, preview = false), now)
        // Whatever is saved from now on says which look it is for, defaults included, and is kept as it is.
        val mine = Spectrogram3DSettings(smoothness = 0.9f, gloss = 0.1f)
        val saved = json.encodeToString(mine)
        assertTrue(saved, saved.contains("\"look\":${Spectrogram3DSettings.LOOK}"))
        assertTrue(json.encodeToString(Spectrogram3DSettings.DEFAULT).contains("\"look\""))
        assertFalse(Spectrogram3DSettings.savedForAnOlderLook(saved))
        assertEquals(mine, Spectrogram3DSettings.fromSaved(saved, json.decodeFromString<Spectrogram3DSettings>(saved)))
    }

    @Test
    fun `the band under the lyrics is drawn across a margin to the screen's edge, never across what stands beside it`() {
        // Expanded lyrics: 18 px of page margin either side, a navigation bar under it.
        assertEquals(18, EdgeBleed.gap(18f, 144))
        assertEquals(96, EdgeBleed.gap(96.4f, 144))
        // Already at the edge, or past it while the player slides: nothing.
        assertEquals(0, EdgeBleed.gap(0f, 144))
        assertEquals(0, EdgeBleed.gap(-30f, 144))
        // The controls beside it in the side-by-side layout, or under it in the ordinary one: not a margin.
        assertEquals(0, EdgeBleed.gap(900f, 144))
        assertEquals(0, EdgeBleed.gap(145f, 144))
    }

    @Test
    fun `the shader has nothing in it a phone's compiler refuses for a half`() {
        // The owner's phone refused the whole shader, in every build that
        // had one, for `(uHi.eval(p).a * 65280.0 + ...) / 65535.0`: "value is
        // out of range for type 'half': 65535". A child's sample is a half
        // and a literal beside it is typed as one. The desktop Skia the
        // check tool compiles with let it pass, so the phone drew the simple
        // version and nobody knew. Comments aside: no literal a half cannot
        // hold, and every sample goes into a float before it is used.
        val code = SPECTROGRAM_3D_SHADER.lines().joinToString("\n") { it.substringBefore("//") }
        for (number in Regex("""(?<![\w.])\d+(?:\.\d+)?""").findAll(code)) {
            assertTrue("a literal a half cannot hold: ${number.value}", number.value.toDouble() < 32_768.0)
        }
        val samples = code.lines().filter { ".eval(" in it }
        assertTrue(samples.isNotEmpty())
        for (line in samples) {
            assertTrue(
                "a sample used before it is a float: ${line.trim()}",
                Regex("""\s*float \w+ = u\w+\.eval\(\w+\)\.a;""").matches(line),
            )
        }
        // The two bytes' weights are 65280 and 255 over 65535, and add up to one.
        assertTrue(code.contains("hi * 0.99610895 + lo * 0.0038910506"))
        assertEquals(1.0, 0.99610895 + 0.0038910506, 1e-8)
        assertEquals(65280.0 / 65535.0, 0.99610895, 1e-8)
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
        // The view the listener left on is saved by its name and read back;
        // nothing saved, or a name from another build, is the first view.
        for (visual in CoverVisual.entries) assertEquals(visual, CoverVisual.named(visual.name))
        assertEquals(CoverVisual.SPECTRUM, CoverVisual.named(null))
        assertEquals(CoverVisual.SPECTRUM, CoverVisual.named("HOLOGRAM"))
    }

    @Test
    fun `settings are clamped, and a pitch range keeps two octaves`() {
        val wild = Spectrogram3DSettings(
            secondsAcross = 999f, angleDeg = -5f, peakHeight = Float.NaN, gloss = 7f,
            subsurface = -1f, smoothness = Float.POSITIVE_INFINITY, haze = 2f,
            minHz = 1500f, maxHz = 1600f, quality = 9,
            attackMs = -3f, releaseMs = 1e9f, ripples = 4f, shadows = Float.NaN, lyricLight = -2f,
        ).clamped()
        assertEquals(0f, wild.attackMs, 0f)
        assertEquals(Spectrogram3DSettings.MAX_RELEASE_MS, wild.releaseMs, 0f)
        assertEquals(1f, wild.ripples, 0f)
        assertEquals(Spectrogram3DSettings.DEFAULT.shadows, wild.shadows, 0f)
        assertEquals(0f, wild.lyricLight, 0f)
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
        val mine = Spectrogram3DSettings(
            angleDeg = 40f, gloss = 0.9f, subsurface = 0.2f, quality = 4, preview = false,
            attackMs = 0f, releaseMs = 40f, ripples = 0.1f, shadows = 0.3f, lyricLight = 0.25f, secondsAcross = 0.5f,
            fftSize = 512, overlapPct = 80f, rangeDb = 40f, background = true,
        )
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(mine, json.decodeFromString<Spectrogram3DSettings>(json.encodeToString(mine)))
        assertEquals(Spectrogram3DSettings.DEFAULT, json.decodeFromString<Spectrogram3DSettings>("{}"))
        // On the cover unless the listener (or a theme) puts it behind the player.
        assertFalse(Spectrogram3DSettings.DEFAULT.background)
        assertTrue(SPECTROGRAM_BACKGROUND_SHARE in 0.3f..0.5f)
        val pack = ThemePack(name = "Wax", visualizer = ThemeVisualizer(spectrogram3d = mine))
        val shared = ThemePackCodec.decode(ThemePackCodec.encode(ThemePackCodec.forSharing(pack)))!!
        val look = shared.visualizer!!.spectrogram3d!!
        assertEquals(40f, look.angleDeg, 0f)
        assertEquals(0.9f, look.gloss, 0f)
        // The response, the water, the shadows and the lyrics' light are the look too.
        assertEquals(mine.copy(quality = look.quality, preview = look.preview), look)
        assertEquals(Spectrogram3DSettings.DEFAULT.quality, look.quality)
        assertTrue(look.preview)
        val theirs = Spectrogram3DSettings(quality = 1, preview = false)
        val applied = look.withPersonalFrom(theirs).clamped()
        assertEquals(40f, applied.angleDeg, 0f)
        assertEquals(1, applied.quality)
        assertFalse(applied.preview)
    }
}
