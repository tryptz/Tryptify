package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.domain.model.FrequencyPoint
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.PresetEar
import tf.monochrome.android.domain.model.PresetHeadphone

/**
 * What a preset keeps of the headphone picker, and what loading it shows.
 *
 * The bug this holds: a preset brought only its bands, so after loading the
 * Nothing Ear preset the picker still said Focal Bathys and the graph still
 * drew the Bathys' curve.
 */
class PresetHeadphonesTest {

    private val bathys = Headphone(id = "focal_bathys", name = "Focal Bathys")
    private val curveL = listOf(FrequencyPoint(20f, -7.88f), FrequencyPoint(1000f, 0f), FrequencyPoint(10000f, 3.25f))
    private val curveR = listOf(FrequencyPoint(20f, -6.5f), FrequencyPoint(1000f, 0.5f), FrequencyPoint(10000f, 2f))
    private val squig = AutoEqMeasurement(
        source = "Precogvision", target = "squiglink", path = "https://precog.squig.link",
        fileName = "Nothing Ear (2024)", host = "https://precog.squig.link",
    )
    private val band = EqBand(id = 0, freq = 1000f, gain = 2f)

    private fun preset(headphone: PresetHeadphone?, stereo: Boolean = false) = EqPreset(
        id = "p",
        name = "p",
        bands = listOf(band),
        bandsR = if (stereo) listOf(band.copy(gain = 1f)) else null,
        headphone = headphone,
    )

    @Test
    fun `nothing picked and nothing measured keeps nothing`() {
        assertNull(PresetHeadphones.capture(null, PresetEar(), null))
        assertNull(PresetHeadphones.capture(null, PresetEar(), PresetEar()))
    }

    @Test
    fun `a mono save keeps the headphone and its curve, no right ear`() {
        val saved = PresetHeadphones.capture(bathys, PresetEar(label = "Focal Bathys · oratory1990", curve = curveL), null)!!
        assertEquals("focal_bathys", saved.id)
        assertEquals("Focal Bathys", saved.name)
        assertEquals(curveL, saved.left.curve)
        assertNull(saved.right)
    }

    @Test
    fun `a curve from a file is kept without a headphone`() {
        val saved = PresetHeadphones.capture(null, PresetEar(label = "imported", curve = curveL), null)!!
        assertEquals("", saved.name)
        assertEquals(curveL, saved.left.curve)
    }

    @Test
    fun `loading a preset shows its own headphone and curve`() {
        val saved = PresetHeadphones.capture(bathys, PresetEar(label = "Focal Bathys · oratory1990", curve = curveL), null)
        val shown = PresetHeadphones.restore(preset(saved), stereoAfterLoad = false)
        assertEquals(bathys, shown.headphone)
        assertEquals("Focal Bathys · oratory1990", shown.left.label)
        assertEquals(curveL, shown.left.curve)
        // 2-channel off and a mono preset: the stored right ear is left alone.
        assertNull(shown.right)
    }

    @Test
    fun `a preset saved before headphones were kept clears the picker`() {
        val shown = PresetHeadphones.restore(preset(null), stereoAfterLoad = false)
        assertNull(shown.headphone)
        assertEquals(PresetEar(), shown.left)
        assertNull(shown.right)
    }

    @Test
    fun `an old preset with 2-channel on clears the right ear too`() {
        // Its bands drive both ears, so neither ear may keep another headphone's curve.
        val shown = PresetHeadphones.restore(preset(null), stereoAfterLoad = true)
        assertEquals(PresetEar(), shown.right)
    }

    @Test
    fun `a stereo preset brings both ears and the sample stepper`() {
        val saved = PresetHeadphones.capture(
            Headphone(id = "nothing_ear", name = "Nothing Ear (2024)"),
            PresetEar(label = "Nothing Ear (2024) L1 · Precogvision", measurement = squig, sample = "L1", curve = curveL),
            PresetEar(label = "Nothing Ear (2024) R1 · Precogvision", measurement = squig, sample = "R1", curve = curveR),
        )
        val shown = PresetHeadphones.restore(preset(saved, stereo = true), stereoAfterLoad = true)
        assertEquals("Nothing Ear (2024)", shown.headphone?.name)
        assertEquals(curveL, shown.left.curve)
        assertEquals(curveR, shown.right?.curve)
        assertEquals("R1", shown.right?.sample)
        assertEquals(squig, shown.right?.measurement)
    }

    @Test
    fun `a stereo preset saved with no right curve clears the right ear`() {
        val saved = PresetHeadphones.capture(bathys, PresetEar(curve = curveL), PresetEar())
        val shown = PresetHeadphones.restore(preset(saved, stereo = true), stereoAfterLoad = true)
        assertEquals(PresetEar(), shown.right)
    }

    @Test
    fun `a mono preset with 2-channel on shows its curve in both ears`() {
        val saved = PresetHeadphones.capture(bathys, PresetEar(label = "Bathys", curve = curveL), null)
        val shown = PresetHeadphones.restore(preset(saved), stereoAfterLoad = true)
        assertEquals(curveL, shown.right?.curve)
        assertEquals("Bathys", shown.right?.label)
    }

    @Test
    fun `a headphone saved without an id is still named`() {
        val shown = PresetHeadphones.restore(
            preset(PresetHeadphone(id = "", name = "My IEM", left = PresetEar(curve = curveL))),
            stereoAfterLoad = false,
        )
        assertEquals(Headphone(id = "My IEM", name = "My IEM"), shown.headphone)
    }
}
