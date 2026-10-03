package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tf.monochrome.android.audio.eq.LoudnessNative
import tf.monochrome.android.audio.eq.LoudnessReading
import tf.monochrome.android.ui.components.bounceClick
import java.util.Locale
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * The master strip's loudness: Short-term large, Integrated under it, in LUFS
 * (EBU R128). A tap starts Integrated over, the way a mastering meter's reset
 * does. Shown in the slot the master keeps for the Solo button it does not
 * have, so the strip stays exactly as tall as the others.
 *
 * The meter runs only while this is on screen (see [LoudnessNative]), and it
 * is read ten times a second — the rate EBU Tech 3341 sets for a Momentary
 * display — from a state local to this readout, so nothing else recomposes.
 */
@Composable
internal fun MasterLoudnessReadout(accent: Color, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        LoudnessNative.acquire()
        onDispose { LoudnessNative.release() }
    }
    val reading by produceState<LoudnessReading?>(initialValue = null) {
        while (true) {
            value = LoudnessNative.read()
            delay(READ_INTERVAL_MS)
        }
    }
    val shortTerm = reading?.shortTerm ?: reading?.momentary
    val integrated = reading?.integrated
    val description = stringResource(
        R.string.mixer_loudness_label,
        (shortTerm?.let { stringResource(R.string.mixer_loudness_short, number(it)) }
            ?: stringResource(R.string.mixer_loudness_measuring)) +
            (integrated?.let { stringResource(R.string.mixer_loudness_integrated, number(it)) } ?: ""),
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.30f))
            .bounceClick(onClick = { LoudnessNative.reset() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-2).dp),
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            Text(
                text = shortTerm?.let(::number) ?: "—",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
            )
            Text(
                text = "LUFS",
                fontSize = 7.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
            Text(
                text = "I " + (integrated?.let(::number) ?: "—"),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.95f),
                maxLines = 1,
            )
        }
    }
}

/** One decimal, and silence as −∞ rather than the meter's floor. */
private fun number(lufs: Float): String =
    if (lufs <= LoudnessReading.SILENCE + 0.05f) "−∞" else String.format(Locale.ROOT, "%.1f", lufs)

private const val READ_INTERVAL_MS = 100L
