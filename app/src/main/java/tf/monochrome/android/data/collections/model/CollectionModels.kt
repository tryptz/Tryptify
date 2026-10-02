package tf.monochrome.android.data.collections.model

import kotlinx.serialization.Serializable

/**
 * One image of a collection track, album or artist, as stored in the
 * entities' `imagesJson` columns. The rest of the manifest format went with
 * collection import; collections already on a device are still read.
 */
@Serializable
data class ManifestImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)
