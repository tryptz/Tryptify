package tf.monochrome.android.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.util.WeakHashMap

/**
 * A piece of window state a screen borrows while it is up and gives back when
 * it goes: the orientation, the colour of the status bar's icons.
 *
 * Saving the value on the way in and putting it back on the way out breaks when
 * two screens borrowing the same thing overlap, and a navigation overlaps them:
 * the screen coming in is composed before the one going out is disposed. Going
 * from the genre galaxy to the library galaxy, the library saved the genre
 * map's value as its "before", the genre map then put back its own "before"
 * under the library, and leaving the library put the genre map's value back
 * app-wide, so every screen turned with the phone despite rotation lock.
 *
 * So the value from before anyone borrowed it is kept once, here, and only the
 * newest borrower gives it back. One that leaves after another has taken over
 * changes nothing.
 */
internal class WindowLoan<T : Any> {
    private var holder: Any? = null
    private var original: T? = null

    /**
     * [by] takes the value over. [current] is read only when nobody holds it,
     * so what is kept is the value from before any borrower.
     */
    fun borrow(by: Any, current: () -> T) {
        if (holder == null) original = current()
        holder = by
    }

    /**
     * [by] gives the value back: what to restore, or null when a later
     * borrower has taken it over, and keeps it.
     */
    fun giveBack(by: Any): T? {
        if (holder !== by) return null
        holder = null
        return original.also { original = null }
    }
}

/** The loans of each activity's window, one per kind of state. Main thread only. */
private object WindowLoans {
    private val orientation = WeakHashMap<Activity, WindowLoan<Int>>()
    private val lightStatusBars = WeakHashMap<Activity, WindowLoan<Boolean>>()

    fun orientation(activity: Activity) = orientation.getOrPut(activity) { WindowLoan() }
    fun lightStatusBars(activity: Activity) = lightStatusBars.getOrPut(activity) { WindowLoan() }
}

/** Holds the activity at [orientation] while this is in the composition. */
@Composable
internal fun BorrowOrientation(orientation: Int) {
    val activity = LocalView.current.context as? Activity ?: return
    DisposableEffect(activity, orientation) {
        val loan = WindowLoans.orientation(activity)
        val me = Any()
        loan.borrow(me) { activity.requestedOrientation }
        activity.requestedOrientation = orientation
        onDispose { loan.giveBack(me)?.let { activity.requestedOrientation = it } }
    }
}

/** Holds the status bar's icons light ([light]) or dark while this is in the composition. */
@Composable
internal fun BorrowLightStatusBars(light: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return
    DisposableEffect(activity, light) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val loan = WindowLoans.lightStatusBars(activity)
        val me = Any()
        loan.borrow(me) { controller.isAppearanceLightStatusBars }
        controller.isAppearanceLightStatusBars = light
        onDispose { loan.giveBack(me)?.let { controller.isAppearanceLightStatusBars = it } }
    }
}
