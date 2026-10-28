package tf.monochrome.android.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.domain.model.LyricsFxSettings

/**
 * The god rays' light colour. The player hands the light its colour fade as a
 * lambda, so a track change no longer builds a new light every frame; the
 * colour has to come out exactly as it did from a fixed accent.
 */
class LyricRayLightColorTest {

    private fun light(accent: () -> Color) = LyricRayLight(
        baseFx = LyricsFxSettings.DEFAULT, accent = accent, moving = false,
        time = mutableStateOf(0f), tilt = mutableStateOf(Offset.Zero),
        pulse = null, band = { null }, lettersBox = { null }, scale = 1080f,
    )

    @Test
    fun `a fixed accent gives the colour it always did`() {
        val accent = Color(0xFF3D7BFF)
        val fixed = LyricRayLight(
            baseFx = LyricsFxSettings.DEFAULT, accent = accent, moving = false,
            time = mutableStateOf(0f), tilt = mutableStateOf(Offset.Zero),
            pulse = null, band = { null }, lettersBox = { null }, scale = 1080f,
        )
        assertEquals(lerp(accent, Color.White, 0.35f), fixed.color)
    }

    @Test
    fun `a fading accent moves the colour of the same light`() {
        val fade = mutableStateOf(Color(0xFF3D7BFF))
        val ray = light { fade.value }
        assertEquals(lerp(Color(0xFF3D7BFF), Color.White, 0.35f), ray.color)
        fade.value = Color(0xFFFF4D6A)
        assertEquals(lerp(Color(0xFFFF4D6A), Color.White, 0.35f), ray.color)
    }
}
