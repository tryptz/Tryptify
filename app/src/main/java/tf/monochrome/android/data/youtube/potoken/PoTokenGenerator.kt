// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from NewPipe (TeamNewPipe/NewPipe), GPL-3.0-or-later:
// app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenGenerator.kt (master, 00acf2f3).
package tf.monochrome.android.data.youtube.potoken

import android.content.Context
import org.schabi.newpipe.extractor.downloader.Downloader
import java.io.Closeable
import java.time.Instant

/**
 * Something that mints PO tokens from one BotGuard run. NewPipe keeps it an
 * interface so that a WebView is not the only possible way; here it also
 * lets the provider be tested without one.
 */
internal interface PoTokenGenerator : Closeable {
    /**
     * A PO token for [identifier] (a video id, or visitor data for the
     * streaming token), minted with the integrity token BotGuard handed out
     * when this generator was made. May be called many times, in parallel.
     */
    suspend fun generatePoToken(identifier: String): String

    /** Whether the integrity token ran out, making every new token worthless. */
    fun isExpired(): Boolean

    /** Releases the generator. Callable from any thread. */
    override fun close()

    fun interface Factory {
        /**
         * Loads BotGuard, runs it and fetches an integrity token, which takes
         * a few network round trips; [downloader] makes them.
         */
        suspend fun newPoTokenGenerator(context: Context, downloader: Downloader): PoTokenGenerator
    }
}

/**
 * When an integrity token that lasts [lifetimeSeconds] from [now] should be
 * treated as expired: ten minutes early, as NewPipe does, so a token is
 * never minted from one that runs out before YouTube checks it.
 */
internal fun integrityTokenExpiry(now: Instant, lifetimeSeconds: Long): Instant =
    now.plusSeconds(lifetimeSeconds - EXPIRY_MARGIN_SECONDS)

internal const val EXPIRY_MARGIN_SECONDS = 600L
