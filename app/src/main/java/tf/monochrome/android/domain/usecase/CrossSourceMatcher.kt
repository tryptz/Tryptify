package tf.monochrome.android.domain.usecase

import java.text.Normalizer
import kotlin.math.abs

/** How two tracks from different sources are judged to be the same recording. */
object CrossSourceMatcher {
    fun normalizeForMatching(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFC)
    }

    fun fuzzyMatch(
        title1: String, artist1: String, duration1: Int,
        title2: String, artist2: String, duration2: Int
    ): Boolean {
        val normalTitle1 = normalizeForMatching(title1)
        val normalTitle2 = normalizeForMatching(title2)
        val normalArtist1 = normalizeForMatching(artist1)
        val normalArtist2 = normalizeForMatching(artist2)

        return normalTitle1 == normalTitle2 &&
            normalArtist1 == normalArtist2 &&
            abs(duration1 - duration2) < 3
    }
}
