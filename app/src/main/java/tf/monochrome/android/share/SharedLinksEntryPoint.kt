// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Shared links, for the app's root, which is no Hilt view model: it plays them. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SharedLinksEntryPoint {
    fun sharedLinks(): SharedLinks
}
