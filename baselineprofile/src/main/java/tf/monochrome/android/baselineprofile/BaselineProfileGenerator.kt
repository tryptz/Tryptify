package tf.monochrome.android.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records which classes and methods the app actually runs, so ART can compile
 * them ahead of time instead of interpreting them on first launch.
 *
 * `androidx.profileinstaller` has been a dependency for a while and the comment
 * beside it in `app/build.gradle.kts` claims it AOT-compiles hot Compose paths.
 * That was only half true: AGP merges profiles the AndroidX libraries ship, so
 * Compose's own runtime was covered, but nothing described *this* app's
 * startup, its nav host, or its list rows. That is the gap this fills.
 *
 * Run it against a connected device or emulator:
 *
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * The result lands in `app/src/<variant>/generated/baselineProfiles/` and is
 * meant to be committed — it is an input to the release build, not a build
 * artifact. Regenerate it when startup or the first screens change shape;
 * a stale profile is not wrong, just progressively less useful.
 *
 * Requires the `projectm` and `libusb` submodules, because it assembles a real
 * APK. See AGENTS.md.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Cold start, then the journeys a first session actually takes.
     *
     * Deliberately not just `startActivityAndWait()`. A profile covering only
     * startup leaves every list row to be JIT'd on the first scroll, which is
     * exactly the frame budget this work is about. Each step below is a path a
     * user hits in their first minute, and the profile is only as good as the
     * code it was watched executing.
     */
    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        // A few iterations, so a path missed once because of timing is still
        // captured. The plugin merges them.
        maxIterations = 5,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()

        // Let the first frame settle rather than racing it — a profile recorded
        // against a half-drawn tree misses the composables that had not run yet.
        device.waitForIdle()

        scrollFirstPage()
        swipeBetweenPages()
        openSearch()
    }

    /**
     * Scrolling is the point of the exercise. The row composables, the image
     * loader's decode path and the glass modifiers only appear in the profile
     * if they are on screen while it records.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollFirstPage() {
        val list = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT_MS) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()
    }

    /**
     * The pager between pages, which is the most common navigation in the app
     * and pulls in each page's own first composition.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.swipeBetweenPages() {
        repeat(2) {
            device.swipe(
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                device.displayWidth / 5,
                device.displayHeight / 2,
                10,
            )
            device.waitForIdle()
        }
    }

    /**
     * The search overlay, its filter chips and its result rows — a different
     * row shape from the library's, and the one a new user reaches for first.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openSearch() {
        val search = device.wait(Until.findObject(By.descContains("Search")), TIMEOUT_MS)
            ?: device.wait(Until.findObject(By.textContains("Search")), TIMEOUT_MS)
            ?: return
        search.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        // The applicationId, which is NOT the namespace: the package was
        // renamed and `tf.monochrome.android` is only the source package.
        const val PACKAGE = "tf.monotrypt.android"
        const val TIMEOUT_MS = 5_000L
    }
}
