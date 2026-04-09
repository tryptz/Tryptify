package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.ui.components.bounceClick

/**
 * FL Studio-styled channel strip with dark DAW colors.
 *
 * Uses [FLColors] for the authentic FL Studio look:
 *  - Dark strip background with subtle border
 *  - Green/yellow VU meters behind the fader
 *  - Lime green accent text for active elements
 *  - Red mute / yellow solo buttons
 *
 * Master strip is wider (72 dp) with dual VU meters.
 */
@Composable
fun FLChannelStrip(
    bus: BusConfig,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    levels: BusLevels = BusLevels(),
    onSelect: () -> Unit,
    onGainChange: (Float) -> Unit,
    onPanChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
) {
    val isMaster = bus.isMaster
    val stripWidth = if (isMaster) 72.dp else 56.dp
    val stripShape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier
            .width(stripWidth)
            .fillMaxHeight()
            .clip(stripShape)
            .background(if (isSelected) FLColors.stripBgSelected else FLColors.stripBg)
            .border(
                width = 1.dp,
                color = if (isSelected) FLColors.accent.copy(alpha = 0.4f) else FLColors.stripBorder,
                shape = stripShape
            )
            .bounceClick(onClick = onSelect)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── Channel number badge ───────────────────────────────────────
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) FLColors.accent.copy(alpha = 0.25f)
                    else FLColors.insertSlotBg
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isMaster) "M" else "${bus.index + 1}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) FLColors.textBright else FLColors.textWhite
            )
        }

        // ── Channel name ───────────────────────────────────────────────
        Text(
            text = bus.name,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) FLColors.textBright else FLColors.textActive,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        // ── Plugin count badge ─────────────────────────────────────────
        if (bus.plugins.isNotEmpty()) {
            Text(
                text = "${bus.plugins.size} fx",
                fontSize = 8.sp,
                color = FLColors.textDim
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Meter + Fader area ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            // VU meters behind — using actual audio levels
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center
            ) {
                VuMeter(
                    levelDb = levels.peakDbL,
                    muted = bus.muted,
                    modifier = Modifier.fillMaxHeight()
                )
                if (isMaster) {
                    Spacer(modifier = Modifier.width(1.dp))
                    VuMeter(
                        levelDb = levels.peakDbR,
                        muted = bus.muted,
                        modifier = Modifier.fillMaxHeight()
                    )
                }
            }

            // Fader overlaid
            VerticalFader(
                gainDb = bus.gainDb,
                onGainChange = onGainChange,
                accentColor = FLColors.accent,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── dB readout ─────────────────────────────────────────────────
        Text(
            text = if (bus.gainDb <= -60f) "-inf" else "%.1f".format(bus.gainDb),
            fontSize = 9.sp,
            color = FLColors.textActive,
            textAlign = TextAlign.Center
        )

        // ── Pan knob ──────────────────────────────────────────────────
        PanKnob(
            value = bus.pan,
            onValueChange = onPanChange
        )

        Spacer(modifier = Modifier.height(2.dp))

        // ── Mute / Solo buttons ────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // Mute
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (bus.muted) FLColors.muteActive else FLColors.muteInactive)
                    .bounceClick(onClick = onToggleMute),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "M",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bus.muted) Color.White else FLColors.textDim
                )
            }

            // Solo (not on master)
            if (!isMaster) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (bus.soloed) FLColors.soloActive else FLColors.soloInactive)
                        .bounceClick(onClick = onToggleSolo),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (bus.soloed) Color.Black else FLColors.textDim
                    )
                }
            }
        }
    }
}
