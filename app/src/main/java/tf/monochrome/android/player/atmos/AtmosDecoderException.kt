package tf.monochrome.android.player.atmos

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException

@UnstableApi
class AtmosDecoderException : DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
