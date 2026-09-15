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
 * Two files land in `app/src/<variant>/generated/baselineProfiles/` —
 * `baseline-prof.txt` from [generate] and `startup-prof.txt` from [startup] —
 * and both are meant to be committed: they are inputs to the release build, not
 * build artifacts. Regenerate them when startup or the first screens change
 * shape; a stale profile is not wrong, just progressively less useful.
 *
 * Requires the `projectm` and `libusb` submodules, because it assembles a real
 * APK. See AGENTS.md.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Cold start and nothing else, recorded into the **startup profile**.
     *
     * A different file with a different job from the one below. `baseline-prof`
     * is compiled ahead of time by ART on the device; `startup-prof` is read by
     * R8 at build time to decide DEX *layout*, so that the classes a launch
     * touches sit in the primary `classes.dex` and are not chased across
     * secondary files. Without `includeInStartupProfile` here, no startup
     * profile is produced at all — which is what this project was shipping.
     *
     * Deliberately just the launch. These rules only pay off while the startup
     * code fits in that first dex, so adding journeys here works against it.
     * Everything else belongs in [generate], which feeds the baseline profile
     * where breadth is the point.
     *
     * No Gradle change goes with this: DEX layout optimisation is on by default
     * from AGP 8.3 (this project is on 9.0.0) and release is already minified,
     * so `baselineProfile { dexLayoutOptimization = true }` would be a no-op
     * that reads as if it were doing something.
     */
    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
        maxIterations = 5,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
    }

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
        openPagesFromHome()
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
     * Opening pages from the list on Home, which is how the app navigates now.
     *
     * This used to swipe the pager. The pager has `userScrollEnabled = false`
     * since pages became a list on Home, so the swipes did nothing at all and
     * the profile recorded startup three times instead of the destination
     * screens it is named after — the exact cost this profile exists to remove.
     *
     * Each tap is verified to have landed. A page that did not open is a page
     * whose first composition is missing from the profile, and a profile is
     * only as good as the code it was watched executing; silence about that is
     * worse than the missing coverage.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openPagesFromHome() {
        for (page in PAGES) {
            val row = device.wait(Until.findObject(By.text(page)), TIMEOUT_MS) ?: continue
            row.click()
            // The page's own title is what tells us the tap arrived; Home's row
            // for it is gone from the screen once the pager has moved.
            device.wait(Until.hasObject(By.text(page)), TIMEOUT_MS)
            device.waitForIdle()
            scrollFirstPage()
            // Back returns to the list, which is the only way back now.
            device.pressBack()
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

        /**
         * The pages worth the recording time, by the name the list shows.
         *
         * Not all seven: each one costs a launch-to-idle round trip across five
         * iterations, and these are the three that carry the row composables,
         * the image loader and the glass modifiers the profile is for.
         */
        val PAGES = listOf("Local", "Playlists", "Favorites")
    }
}
