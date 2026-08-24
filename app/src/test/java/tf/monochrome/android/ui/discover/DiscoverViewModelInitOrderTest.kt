package tf.monochrome.android.ui.discover

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiscoverViewModel's init blocks must be the last things in the class.
 *
 * This is a source-level check because the failure it prevents is a source
 * ordering one, invisible to the compiler and to any test that builds the
 * ViewModel: Kotlin runs initializers in declaration order, so an init block
 * placed mid-class sees null for every property declared below it.
 *
 * The reason that reaches a discovery feed at all is that [rebuild] starts its
 * work with `viewModelScope.launch`, and a launch is not a promise to run
 * later. viewModelScope dispatches on Dispatchers.Main.immediate and a
 * ViewModel is constructed on the main thread, so the body runs *inline inside
 * the constructor* until its first real suspension point. Every field it reads
 * before suspending is read mid-construction.
 *
 * That took the tab down on cold launch once already. The read of
 * _selectedGenreId, a field declared 500 lines below the init, sat in a branch
 * the initial build never entered; moving it to the top of the coroutine body
 * was enough to dereference null on every launch. Nothing about that edit
 * looked dangerous, which is exactly why the rule is checked here rather than
 * written down and hoped for.
 */
class DiscoverViewModelInitOrderTest {

    private val source = File("src/main/java/tf/monochrome/android/ui/discover/DiscoverViewModel.kt")

    @Test
    fun `no property is declared after an init block`() {
        val lines = source.readLines()
        // The FIRST init block, not the last: a property declared after any of
        // them is null while that one runs, and checking only the last would
        // wave through exactly the layout that crashed.
        val firstInit = lines.indexOfFirst { it.trimEnd() == "    init {" }
        assertTrue("DiscoverViewModel has no top-level init block any more", firstInit >= 0)

        // Class-body declarations only: one indent level, and not inside the
        // companion object, whose members are initialized before the instance
        // and so are always safe.
        val companion = lines.indexOfFirst { it.trimEnd() == "    private companion object {" }
        val end = if (companion >= 0) companion else lines.size

        val offenders = (firstInit until end).filter { i ->
            val line = lines[i]
            line.startsWith("    private val ") ||
                line.startsWith("    private var ") ||
                line.startsWith("    val ") ||
                line.startsWith("    var ")
        }.map { i -> "${i + 1}: ${lines[i].trim()}" }

        assertTrue(
            "Properties are declared after DiscoverViewModel's first init block, so they are " +
                "still null while that init runs. Move them above it, or move the init below " +
                "them:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
