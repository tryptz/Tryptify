package tf.monochrome.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Where the app sends someone who wants to chip in.
 *
 * Held in one place because two screens offer them now — Settings › Support and
 * the tip bar on Home — and a donation URL is the worst kind of string to have
 * two copies of: editing one and not the other sends people's money somewhere
 * the author does not control, and nothing about the app would look wrong.
 */
object SupportLinks {
    const val KO_FI = "https://ko-fi.com/trypt"
    const val PATREON = "https://www.patreon.com/tryptz"
}

/**
 * Opens an external donation URL in the browser (or a Custom Tab, if the user's
 * default browser supports one). Wrapped so a device with no browser cannot
 * crash the app — it surfaces a Toast instead.
 */
internal fun openDonationUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open the link", Toast.LENGTH_SHORT).show()
    }
}
