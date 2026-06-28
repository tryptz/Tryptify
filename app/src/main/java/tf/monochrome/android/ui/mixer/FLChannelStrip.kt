package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.player.PlayerDesignTokens

/** Fixed fader travel so strips stay compact instead of stretching the whole
 *  screen height; the strip is centred in its row and the meters match it. */
private val FaderTravel = 280.dp

/**
 * Compact DAW channel strip styled to match the main player: a solid glass
 * panel, theme-driven accents, a weighted fader cap and clean VU metering.
 */
@Composable
fun FLChannelStrip(
    bus: BusConfig,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    levels: BusLevels = BusLevels(),
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onSelect: () -> Unit,
    onGainChange: (Float) -> Unit,
    onPanChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isMaster = bus.isMaster
    val stripWidth = if (isMaster) 78.dp else 60.dp
    val stripShape = RoundedCornerShape(PlayerDesignTokens.GlassCornerSmall)
    val accent = if (isMaster) colors.primary else accentColor
    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White

    // Near-opaque panel so the album glow sits BEHIND the strip instead of
    // washing through it. A faint accent tint up top gives each channel life.
    val panelTop = lerp(colors.surfaceContainerHigh, accent, if (isSelected) 0.16f else 0.07f)
    val panelBottom = lerp(colors.surface, Color.Black, 0.28f)
    val stripBrush = Brush.verticalGradient(
        colors = listOf(
            panelTop.copy(alpha = 0.97f),
            colors.surfaceContainerHigh.copy(alpha = 0.95f),
            panelBottom.copy(alpha = 0.97f)
        )
    )
    val meterWidth = 7.dp
    val inactiveButton = colors.surfaceContainerHighest.copy(alpha = 0.88f)

    Column(
        modifier = modifier
            .width(stripWidth)
            .shadow(
                elevation = if (isSelected) 20.dp else 10.dp,
                shape = stripShape,
                clip = false
            )
            .background(stripBrush, stripShape)
            .liquidGlass(
                shape = stripShape,
                tintAlpha = PlayerDesignTokens.GlassTintSoft,
                borderAlpha = if (isSelected) 0.16f else 0.06f,
                showRefraction = isSelected
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accent.copy(alpha = 0.70f) else colors.outline.copy(alpha = 0.14f),
                shape = stripShape
            )
            .bounceClick(onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Channel number badge ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (isSelected) accent else colors.surfaceContainerHighest.copy(alpha = 0.85f))
                .then(
                    if (isSelected) Modifier
                    else Modifier.border(1.5.dp, accent.copy(alpha = 0.85f), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isMaster) "M" else "${bus.index + 1}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) onAccent else Color.White
            )
        }

        // ── Channel name ──────────────────────────────────────────────────
        Text(
            text = bus.name,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = Color.White.copy(alpha = if (isSelected) 1f else 0.82f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        // ── FX count ──────────────────────────────────────────────────────
        if (bus.plugins.isNotEmpty()) {
            Text(
                text = "${bus.plugins.size} fx",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.95f)
            )
        } else {
            Spacer(modifier = Modifier.height(11.dp))
        }

        // ── Meter ▏ Fader ▏ Meter ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .height(FaderTravel)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
        ) {
            if (isMaster) {
                VuMeter(levelDb = levels.peakDbL, muted = bus.muted, accentColor = accent)
            } else {
                Spacer(modifier = Modifier.width(meterWidth))
            }

            VerticalFader(
                gainDb = bus.gainDb,
                onGainChange = onGainChange,
                accentColor = accent,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            VuMeter(
                levelDb = if (isMaster) levels.peakDbR else levels.peakDbL,
                muted = bus.muted,
                accentColor = accent
            )
        }

        // ── dB readout ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (bus.gainDb <= -60f) "-inf" else "%.1f".format(bus.gainDb),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.90f),
                textAlign = TextAlign.Center
            )
        }

        PanKnob(
            value = bus.pan,
            onValueChange = onPanChange,
            accentColor = accent
        )

        // ── Mute / Solo ───────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (bus.muted) colors.error else inactiveButton)
                    .border(
                        width = 1.dp,
                        color = if (bus.muted) colors.error.copy(alpha = 0.75f) else colors.outline.copy(alpha = 0.12f),
                        shape = CircleShape
                    )
                    .bounceClick(onClick = onToggleMute),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "M",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bus.muted) colors.onError else colors.onSurfaceVariant
                )
            }

            if (!isMaster) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (bus.soloed) accent else inactiveButton)
                        .border(
                            width = 1.dp,
                            color = if (bus.soloed) accent.copy(alpha = 0.78f) else colors.outline.copy(alpha = 0.12f),
                            shape = CircleShape
                        )
                        .bounceClick(onClick = onToggleSolo),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (bus.soloed) onAccent else colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}
