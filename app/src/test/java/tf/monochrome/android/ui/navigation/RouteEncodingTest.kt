package tf.monochrome.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Routes are percent-encoded, never form-encoded.
 *
 * `java.net.URLEncoder` is `application/x-www-form-urlencoded`, where **a space
 * becomes `+`**. Navigation percent-decodes a route argument once on the way
 * into the destination, and `+` is not percent-encoding, so it arrives as a
 * literal plus.
 *
 * Two screens built the folder route by hand with it, so every folder whose
 * name contained a space opened a page for a path that did not exist:
 * `/storage/emulated/0/Monochrome ` was queried as `/storage/emulated/0/Monochrome+`,
 * and both the subfolder and the track query came back empty. `/Music` has no
 * space in it, which is why it was the one folder that worked.
 *
 * `Screen.FolderBrowser.createRoute` uses `android.net.Uri.encode` and has said
 * so in a comment since it was written — the comment just could not stop anyone
 * from building the string themselves. This can.
 *
 * A source scan rather than a call to `createRoute`, because `Uri.encode` is
 * framework code and throws "not mocked" off-device; there is no Robolectric in
 * this module. The API clients under `data/api` genuinely do want form encoding
 * for query strings, so the rule is scoped to the UI.
 */
class RouteEncodingTest {

    @Test
    fun `no route is built with URLEncoder`() {
        val ui = File("src/main/java/tf/monochrome/android/ui")
        assertTrue("UI sources not found at ${ui.absolutePath}", ui.isDirectory)

        val offenders = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "URLEncoder.encode(" in it.readText() }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(
            "Use Screen.<destination>.createRoute(), which percent-encodes; " +
                "URLEncoder turns a space into '+' and the destination gets that plus.",
            emptyList<String>(),
            offenders,
        )
    }
}
