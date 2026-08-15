package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.components.toggleSemantics
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerDesignTokens
import tf.monochrome.android.ui.player.playerFrostTint
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.player.rememberLiquidGlassAvailable

/** Fixed fader travel so strips stay compact instead of stretching the whole
 *  screen height; the strip is centred in its row and the meters match it. */
private val FaderTravel = 280.dp

/**
 * Compact DAW channel strip cut from the app's own liquid glass: the AGSL
 * `playerGlass` slab, theme-driven accents, a weighted fader cap and clean VU
 * metering.
 *
 * The material is the **UI panels** blob from the Player Visuals Studio
 * ([LocalMiniPlayerGlass]) — the same glass the mini player, the search bars and
 * the map panels are cut from — so a listener who tunes that one material gets
 * the mixer with it instead of a console that stayed opaque while every other
 * floating panel turned to glass. It is published as [LocalPlayerGlass] for the
 * shader (and anything inside the strip that reads it), exactly as `GlassPanel`
 * does with the settings it is handed.
 *
 * All three layers of the app's glass are here, per `docs/ui-invariants.md`:
 * the **haze** blurs [hazeState] — the mixer's backdrop, which is the blurred
 * album art when that switch is on — the **frost** tints that blur by the shared
 * [playerFrostTint] recipe, and the **shader slab** is drawn at full tint
 * opacity and relit by `playerGlass`. The frost is thin on purpose: most of what
 * reads through a strip should be the artwork behind it.
 *
 * Three tiers:
 *  - shader available → haze + frost + the **solid** slab. Solid is the point:
 *    the shader builds its bevel and rim from the alpha heightfield beneath it,
 *    and body opacity is what makes the strip see-through.
 *  - glass on but no shader (below API 33, or a driver that will not compile it)
 *    → the app's plain glassmorphism, blurring the same backdrop, over the
 *    near-opaque channel gradient — the look this strip had before.
 *  - glass switched off → that gradient alone, so a low-tier device still gets a
 *    readable strip rather than controls floating on nothing.
 */
@Composable
fun FLChannelStrip(
    bus: BusConfig,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    levels: BusLevels = BusLevels(),
    accentColor: Color = MaterialTheme.colorScheme.primary,
    /**
     * The backdrop to frost. Null when the caller has none, and the strip then
     * takes the plain translucent glass rather than asking haze to blur a
     * source that was never fed — which paints its base colour and reads as a
     * solid container.
     */
    hazeState: HazeState? = null,
    /**
     * The glass material. The UI-panels settings by default: this strip is a
     * floating panel like the rest, not player chrome, so reading
     * [LocalPlayerGlass] here would hand it the untouched defaults on the mixer
     * route and nothing tuned in the Studio would ever reach it.
     */
    glass: PlayerGlassSettings = LocalMiniPlayerGlass.current,
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

    // Fallback panel for the two tiers without the shader: near-opaque so the
    // album glow sits BEHIND the strip instead of washing through it, with a
    // faint accent tint up top to give each channel life.
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

    // The pane's own colour. A tint chosen in the Studio wins, as it does on
    // every other panel; with none set the channel accent tints it — that
    // colour is most of what tells five identical strips apart at a glance.
    //
    // Pulled well back toward the surface rather than used neat. The mini
    // player wears its accent at full strength across a 64dp bar; a channel
    // strip is a 60x600dp column, and the same colour over that much area
    // stopped reading as tinted glass and started reading as a painted plastic
    // panel — five of them side by side, each a different flat colour. The
    // selected one carries more of it, which is the same "this one is live"
    // signal its badge and border already give.
    val paneAccent = lerp(colors.surfaceContainerHigh, accent, if (isSelected) 0.5f else 0.3f)
    val tint = if (glass.tintColor != 0) Color(glass.tintColor) else paneAccent
    val flat = LocalLowPerformance.current.disableLiquidGlass
    val allowHaze = LocalPerformanceProfile.current.allowHazeBlur
    val frostBg = colors.background
    val isDark = frostBg.luminance() <= 0.5f

    CompositionLocalProvider(LocalPlayerGlass provides glass) {
    // Asks whether the shader is really coming (it reads the settings provided
    // just above), so the slab is never drawn faint "just in case" — a hedged
    // fill leaves the shader almost no heightfield to bevel and comes out a
    // smudge, while an unshaded solid fill would be an opaque rectangle.
    val shaded = rememberLiquidGlassAvailable()

    Box(
        modifier = modifier
            .width(stripWidth)
            .shadow(
                elevation = if (isSelected) 20.dp else 10.dp,
                shape = stripShape,
                clip = false
            )
            .clip(stripShape)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accent.copy(alpha = 0.70f) else colors.outline.copy(alpha = 0.14f),
                shape = stripShape
            )
            // On the pane rather than on the controls, so a tap presses the
            // whole sheet of glass and not just the labels standing on it. Still
            // an ancestor of the fader and the knob, exactly as before, so their
            // drags claim the gesture first.
            .bounceClick(onClick = onSelect)
    ) {
        // ── The pane, behind the controls ─────────────────────────────────
        // Its own node so the glass is relit on its own layer and the fader,
        // meters and labels on top stay crisp instead of being refracted.
        if (shaded) {
            // Haze first: the real gaussian blur of the backdrop, which is what
            // you see *through* the strip. Without it the artwork reads sharp
            // through a 0.2-opacity body and fights the fader and the labels.
            if (hazeState != null && allowHaze && glass.hazeBlurDp > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = frostBg,
                                blurRadius = glass.hazeBlurDp.dp,
                                // The app's one frost recipe, scaled by the
                                // listener's Backdrop tint. Thin on purpose:
                                // most of what reads through should be the art.
                                tints = listOf(HazeTint(playerFrostTint(glass, isDark))),
                                noiseFactor = 0f,
                            )
                        )
                )
            }
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .playerGlass(tint = tint)
            ) {
                val r = PlayerDesignTokens.GlassCornerSmall.toPx()
                drawRoundRect(color = tint, cornerRadius = CornerRadius(r, r))
            }
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(stripBrush, stripShape)
                    .then(
                        // liquidGlass drops itself on LOW-tier devices; the
                        // `flat` check is the listener's own "off" switch.
                        //
                        // Deliberately NOT handed the haze source. This tier
                        // stands on the near-opaque channel gradient, and a
                        // blur pane draws OVER that fill rather than through
                        // it — the gradient would be hidden and the strip left
                        // with only frost to be legible against. The blurred
                        // artwork belongs to the shader tier, where the slab is
                        // see-through by design.
                        if (flat) {
                            Modifier
                        } else {
                            Modifier.liquidGlass(
                                shape = stripShape,
                                tintAlpha = PlayerDesignTokens.GlassTintSoft,
                                borderAlpha = if (isSelected) 0.16f else 0.06f,
                                showRefraction = isSelected
                            )
                        }
                    )
            )
        }

    // The sizing child: the pane layers above match ITS height, so the glass is
    // exactly as tall as the controls standing on it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                color = if (isSelected) onAccent else colors.onSurfaceVariant
            )
        }

        // ── Channel name ──────────────────────────────────────────────────
        Text(
            text = bus.name,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            // Theme color (was hardcoded white → invisible on the light theme).
            color = colors.onSurface.copy(alpha = if (isSelected) 1f else 0.82f),
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
        // Stacked, not side by side, and that is the only arrangement that
        // fits. Each button carries the 48dp accessibility minimum, so a row of
        // two wants 100dp; a bus strip is 60dp wide and offers 52dp of content.
        // Row measures its children in order against what is left, so Mute took
        // 48, the gap took 4, and Solo was measured against nothing and never
        // drawn — the mixer had no way to solo a bus at all, and it read as
        // deliberate because master legitimately shows one button. The tell was
        // that master's circle sat centred while every bus's sat left of centre,
        // parked at the start of a 52dp band.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (bus.muted) colors.error else inactiveButton)
                    .border(
                        width = 1.dp,
                        color = if (bus.muted) colors.error.copy(alpha = 0.75f) else colors.outline.copy(alpha = 0.12f),
                        shape = CircleShape
                    )
                    .bounceClick(onClick = onToggleMute)
                    .toggleSemantics(label = "Mute", checked = bus.muted),
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
                        .minimumInteractiveComponentSize()
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (bus.soloed) accent else inactiveButton)
                        .border(
                            width = 1.dp,
                            color = if (bus.soloed) accent.copy(alpha = 0.78f) else colors.outline.copy(alpha = 0.12f),
                            shape = CircleShape
                        )
                        .bounceClick(onClick = onToggleSolo)
                        .toggleSemantics(label = "Solo", checked = bus.soloed),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (bus.soloed) onAccent else colors.onSurfaceVariant
                    )
                }
            } else {
                // Master has nothing to solo, but it still holds the space, so
                // all five strips are the same height and their faders stay
                // level. Same modifiers as the button rather than a hardcoded
                // height, so it reserves exactly what the button would measure
                // whether or not the touch-target minimum is being enforced.
                Spacer(modifier = Modifier.minimumInteractiveComponentSize().size(26.dp))
            }
        }
    }
    }
    }
}
