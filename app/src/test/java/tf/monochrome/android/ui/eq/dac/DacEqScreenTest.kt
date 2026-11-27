package tf.monochrome.android.ui.eq.dac

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.chronosauros.contour.core.DeviceTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.domain.model.FilterType
import java.io.File

/**
 * The DAC EQ page drawn for real, on a simulated DAC: what it shows with
 * nothing plugged in, with a DAC it has read, and with an EQ ready to send.
 *
 * With DAC_EQ_SHOTS set to a folder, each state is also saved there as a
 * picture, to look the layout over without a phone or a DAC.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h1500dp-xhdpi")
class DacEqScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private val tenPeaks = listOf(
        31f to 4.5f, 62f to 3.0f, 125f to -1.5f, 250f to -2.5f, 500f to 1.0f,
        1000f to -1.0f, 2000f to 2.5f, 4000f to -4.0f, 8000f to 3.0f, 12000f to -2.0f,
    ).mapIndexed { i, (f, g) -> EqBand(i, FilterType.PEAKING, f, g, 1.41f) }

    private val sources = listOf(DacEqSource(EqPreset(id = "a", name = "HD 600", bands = tenPeaks, isCustom = true), parametric = false))

    private fun DacEqSession.settled(what: (DacEqUiState) -> Boolean) = runBlocking {
        withTimeout(30_000) { state.first { it.busy == DacEqBusy.IDLE && what(it) } }
    }

    private fun show(page: DacEqSession) = compose.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            DacEqContent(session = page, sources = sources, onChoosePreset = {}, onBack = {})
        }
    }

    /** The window as drawn, copied the way a screenshot is (Robolectric draws it with real Skia). */
    private fun shot(name: String) {
        val folder = System.getenv("DAC_EQ_SHOTS") ?: return
        compose.waitForIdle()
        val window = compose.activity.window
        val picture = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, picture, {}, Handler(Looper.getMainLooper()))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        File(folder).mkdirs()
        File(folder, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        init {
            // PixelCopy through the hardware renderer, which is what draws Compose's layers.
            System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        }
    }

    @Test
    fun `with no DAC the page says what it is for and which DACs it knows`() {
        show(DacEqSession(SimulatedDac(DeviceTarget.KA15), scope))
        compose.onNodeWithText("No DAC connected").assertIsDisplayed()
        compose.onNodeWithText("FIIO KA15", substring = true).assertExists()
        compose.onNodeWithText("Hold to send").assertDoesNotExist()
        shot("1-no-dac")
    }

    @Test
    fun `a DAC waiting for permission offers Allow and nothing else`() {
        val dac = SimulatedDac(DeviceTarget.MICRO)
        dac.plugIn(allowed = false)
        show(DacEqSession(dac, scope))
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithText("Hold to send").assertDoesNotExist()
        shot("2-needs-permission")
    }

    @Test
    fun `an EQ fitted to the DAC is shown with the button that sends it`() {
        val dac = SimulatedDac(DeviceTarget.MICRO)
        val page = DacEqSession(dac, scope)
        dac.plugIn()
        page.settled { it.held != null }
        page.load("HD 600", tenPeaks, emptyList(), emptyList())
        val loaded = page.settled { it.fit != null }
        page.selectBand(loaded.bands[2].id)
        show(page)
        compose.onNodeWithText("CrinEar Protocol Micro").assertIsDisplayed()
        compose.onNodeWithText("Fitted again for this DAC", substring = true).assertExists()
        compose.onNodeWithText("Hold to send").performScrollTo().assertIsDisplayed()
        shot("3-ready-to-send")
    }

    @Test
    fun `after a send the page says the DAC holds the EQ`() {
        val dac = SimulatedDac(DeviceTarget.KA15)
        val page = DacEqSession(dac, scope)
        dac.plugIn()
        page.settled { it.held != null }
        page.load("HD 600", tenPeaks.take(6), emptyList(), emptyList())
        page.settled { it.fit != null }
        page.send()
        page.settled { it.outcome != null }
        show(page)
        compose.onNodeWithText("Sent and verified", substring = true).performScrollTo().assertIsDisplayed()
        shot("4-sent")
    }
}
