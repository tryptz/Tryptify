package tf.monochrome.android.audio.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.LoudnessReading

/**
 * The Loudness stage holds the panel's one rule like every other stage: no
 * reading is a dash, never a plausible number — and silence, which *is* a
 * reading, says so instead of printing the meter's −120 floor as a level.
 */
class LoudnessPipelineTest {

    private fun loudness(reading: LoudnessReading?) =
        buildAudioPipelineSnapshot(AudioPipelineInputs(loudness = reading))
            .sections.first { it.stage == PipelineStage.LOUDNESS }

    private fun PipelineSection.value(label: String) = fields.first { it.label == label }.display

    @Test
    fun `loudness sits between the DSP and the output, in signal order`() {
        val stages = buildAudioPipelineSnapshot(AudioPipelineInputs()).sections.map { it.stage }
        assertEquals(stages.indexOf(PipelineStage.DSP) + 1, stages.indexOf(PipelineStage.LOUDNESS))
        assertEquals(stages.indexOf(PipelineStage.LOUDNESS) + 1, stages.indexOf(PipelineStage.OUTPUT))
    }

    @Test
    fun `before any audio every field is a dash and the note says why`() {
        val section = loudness(null)
        section.fields.forEach { assertEquals("${it.label} invented a value", EM_DASH, it.display) }
        assertNotNull(section.note)
        assertFalse(section.engaged)
    }

    @Test
    fun `readings are printed with their units`() {
        val section = loudness(LoudnessReading(-12.34f, -14.2f, -13.96f, 6.04f, -0.71f))
        assertEquals("-12.3 LUFS", section.value("Momentary"))
        assertEquals("-14.2 LUFS", section.value("Short-term"))
        assertEquals("-14.0 LUFS", section.value("Integrated"))
        assertEquals("6.0 LU", section.value("Loudness Range"))
        assertEquals("-0.7 dBTP", section.value("True Peak"))
        assertTrue(section.engaged)
    }

    @Test
    fun `a window that has not filled yet is a dash, not a guess`() {
        // 400 ms in: Momentary exists, the 3 s windows and the gated value do not.
        val section = loudness(LoudnessReading(-20f, null, null, null, -6f))
        assertEquals("-20.0 LUFS", section.value("Momentary"))
        assertEquals(EM_DASH, section.value("Short-term"))
        assertEquals(EM_DASH, section.value("Integrated"))
        assertEquals(EM_DASH, section.value("Loudness Range"))
    }

    @Test
    fun `silence is called silence and does not light the stage`() {
        val s = LoudnessReading.SILENCE
        val section = loudness(LoudnessReading(s, s, null, null, s))
        assertEquals("Silence", section.value("Momentary"))
        assertEquals("Silence", section.value("True Peak"))
        assertFalse(section.engaged)
    }

    @Test
    fun `numbers use a point whatever the phone's locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("-14.2 LUFS", loudness(LoudnessReading(-14.2f, null, null, null, null)).value("Momentary"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
