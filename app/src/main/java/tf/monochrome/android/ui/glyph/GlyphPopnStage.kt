// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size as DecodeSize
import tf.monochrome.android.performance.LocalPerformanceProfile

/**
 * The room behind a pop'n field: the song's picture, blurred and dimmed,
 * over a dark stage lit from below where the buttons are. pop'n's cabinet
 * has no pad to show, so unlike [GlyphDanceStage] nothing stands in the
 * room; the buttons are the pad, drawn by the touch zones along the bottom.
 */
@Composable
fun GlyphPopnStage(
    modifier: Modifier = Modifier,
    /** The song's picture for the room; the plain room without one. */
    artUri: String? = null,
) {
    val context = LocalContext.current
    val blurArt = LocalPerformanceProfile.current.allowHazeBlur

    Box(modifier = modifier.fillMaxSize()) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val brush = Brush.radialGradient(
                        colorStops = STAGE_STOPS,
                        center = Offset(size.width / 2f, size.height),
                        radius = size.height * 0.9f,
                    )
                    onDrawBehind {
                        drawRect(STAGE_DARK)
                        drawRect(brush)
                    }
                },
        )
        if (artUri != null) {
            val request = remember(artUri, context) {
                ImageRequest.Builder(context)
                    .data(artUri)
                    .size(DecodeSize(ART_DECODE_PX, ART_DECODE_PX))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurArt) Modifier.blur(ART_BLUR, BlurredEdgeTreatment.Rectangle) else Modifier),
            )
            Spacer(modifier = Modifier.fillMaxSize().background(ART_SCRIM))
        }
    }
}

private const val ART_DECODE_PX = 160
private val ART_BLUR = 18.dp
private val ART_SCRIM = Color(0xFF120A1C).copy(alpha = 0.5f)

/** A warmer dark than the dance room's blue: pop'n's stage is a show, not a studio. */
private val STAGE_DARK = Color(0xFF140B22)
private val STAGE_STOPS = arrayOf(0f to Color(0xFF5B2A7A), 0.45f to Color(0xFF2A1442), 1f to STAGE_DARK)
