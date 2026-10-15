package tf.monochrome.android.player

import androidx.media3.common.MediaItem

/** A library track's media id: "local_" and its `local_tracks` row. */
private const val LOCAL_PREFIX = "local_"

/** The `local_tracks` row [mediaItem] plays, or null for anything that is not a library track. */
internal fun localRowIdOf(mediaItem: MediaItem?): Long? =
    mediaItem?.mediaId?.takeIf { it.startsWith(LOCAL_PREFIX) }?.removePrefix(LOCAL_PREFIX)?.toLongOrNull()
