// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A listening party invite opened from outside the app: the website's link
 * (`https://tryptify.com/party/<code>`), or an older app's on the rooms server,
 * which the manifest claims.
 * Only its code is kept, and the party screen joins it on this phone's own
 * rooms server, so a link names a party and nothing more.
 */
@Singleton
class PartyInvites @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)

    /** The code an invite is waiting to join, until the party screen takes it. */
    val code: StateFlow<String?> = _code.asStateFlow()

    /** Keeps [uri]'s code when it is an invite; false for any other link. */
    fun offer(uri: Uri): Boolean {
        val code = PartyProtocol.inviteCode(uri.scheme, uri.path) ?: return false
        _code.value = code
        return true
    }

    fun clear() {
        _code.value = null
    }
}
