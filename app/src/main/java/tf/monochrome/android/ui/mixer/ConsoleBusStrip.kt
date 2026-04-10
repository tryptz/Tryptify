package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * FL Studio-layout mixing console channel strip with liquid-glass styling.
 *
 * Narrow vertical strip featuring:
 *  - Channel number badge
 *  - Channel name label
 *  - Plugin count badge
 *  - Integrated VU meter + fader area (meters behind, fader overlaid)
 *  - dB readout
 *  - Pan knob
 *  - Mute / Solo buttons
 *
 * Normal bus width: 56 dp.  Master bus width: 72 dp.
 * Uses [liquidGlass] and MaterialTheme colours for the app's glass aesthetic.
 */
@Composable
fun ConsoleBusStrip(
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
    val isMaster   = bus.isMaster
    val stripWidth = if (isMaster) 72.dp else 56.dp
    val accentColor = if (isMaster) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary
    val borderAlpha = if (isSelected) MonoDimens.glassBorderAlpha * 3f
    else MonoDimens.glassBorderAlpha

    Column(
        modifier = modifier
            .width(stripWidth)
            .fillMaxHeight()
            .liquidGlass(
                shape       = MonoDimens.shapeSm,
                borderAlpha = borderAlpha
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
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = if (isMaster) "M" else "${bus.index + 1}",
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold,
                color      = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }

        // ── Channel name ───────────────────────────────────────────────
        Text(
            text       = bus.name,
            style      = MaterialTheme.typography.labelSmall,
            fontSize   = 8.sp,
            fontWeight = FontWeight.Medium,
            color      = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            textAlign  = TextAlign.Center,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.fillMaxWidth()
        )

        // ── Plugin count badge ─────────────────────────────────────────
        if (bus.plugins.isNotEmpty()) {
            Text(
                text     = "${bus.plugins.size} fx",
                style    = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Meter + Fader area (fills remaining height) ────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            // VU meters behind — using actual audio levels ─────────────
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center
            ) {
                VuMeter(
                    levelDb  = levels.peakDbL,
                    muted    = bus.muted,
                    modifier = Modifier.fillMaxHeight()
                )
                if (isMaster) {
                    Spacer(modifier = Modifier.width(1.dp))
                    VuMeter(
                        levelDb  = levels.peakDbR,
                        muted    = bus.muted,
                        modifier = Modifier.fillMaxHeight()
                    )
                }
            }

            // Fader overlaid ────────────────────────────────────────────
            VerticalFader(
                gainDb       = bus.gainDb,
                onGainChange = onGainChange,
                accentColor  = accentColor,
                modifier     = Modifier.fillMaxSize()
            )
        }

        // ── dB readout ─────────────────────────────────────────────────
        Text(
            text      = if (bus.gainDb <= -60f) "-inf" else "%.1f dB".format(bus.gainDb),
            style     = MaterialTheme.typography.labelSmall,
            fontSize  = 9.sp,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // ── Pan knob ──────────────────────────────────────────────────
        PanKnob(
            value         = bus.pan,
            onValueChange = onPanChange
        )

        Spacer(modifier = Modifier.height(MonoDimens.spacingXs))

        // ── Mute / Solo buttons ────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Mute
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (bus.muted) Color(0xFFE53935)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .bounceClick(onClick = onToggleMute),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = "M",
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color      = if (bus.muted) Color.White
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            // Solo (not shown on master)
            if (!isMaster) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (bus.soloed) Color(0xFFFFC107)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .bounceClick(onClick = onToggleSolo),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = "S",
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color      = if (bus.soloed) Color.Black
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
