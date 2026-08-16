// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.sampler.stems.BackendKind
import tf.monochrome.android.audio.sampler.stems.BackendStatus
import tf.monochrome.android.audio.sampler.stems.StemModelManager
import tf.monochrome.android.ui.patterns.PanelLabel
import tf.monochrome.android.ui.patterns.PatternPanel
import tf.monochrome.android.ui.patterns.PatternPill
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.roundToInt

/**
 * Tryptify AI — what is separating your audio, and how to change it.
 *
 * The brief is explicit that a fallback must never be silent, and this panel is
 * where that promise is kept. A separation running on the CPU instead of the
 * NPU takes many times longer, and someone watching a progress bar crawl is
 * owed the reason rather than left to assume the app is broken.
 *
 * It is also deliberately not a download manager. The panel's normal state is
 * one line saying what is running; the install card only appears when there is
 * something to install, and the progress bar only while something is
 * downloading. Nothing here nags a user who is happy with the built-in
 * separator — which works, needs no model, and is what makes the download
 * genuinely optional rather than a gate in front of the feature.
 */
@Composable
fun StemAiPanel(
    ai: SamplerViewModel.AiState,
    accent: Color,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    PatternPanel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelLabel("Tryptify AI")
            BackendBadge(ai, accent)
        }

        Spacer(Modifier.height(8.dp))

        when {
            ai.installing -> InstallingState(ai, accent, onCancelInstall)
            ai.installed != null -> ReadyState(ai, accent, onCheckForUpdate, onDelete)
            ai.available != null -> OfferState(ai, accent, onInstall)
            else -> BuiltInState(ai, accent, onCheckForUpdate)
        }

        // The full picture, only when something better exists than what is
        // running. Showing every backend all the time would be noise; showing
        // none of them when a faster one is sitting there unavailable is the
        // silent fallback the brief rules out.
        if (ai.degraded && ai.backends.size > 1) {
            Spacer(Modifier.height(10.dp))
            PanelLabel("Backends")
            Spacer(Modifier.height(6.dp))
            BackendStatusList(
                statuses = ai.backends,
                active = ai.activeBackend,
                accent = accent,
            )
        }

        ai.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The status dot and the name of whatever is actually running.
 *
 * Accent when accelerated, plain when not — a colour difference someone
 * notices without reading, which is the point of putting it in the header.
 */
@Composable
private fun BackendBadge(ai: SamplerViewModel.AiState, accent: Color) {
    val kind = ai.activeBackend ?: BackendKind.DSP
    val colour = if (kind.isAccelerated) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colour),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = kind.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colour,
        )
    }
}

/** Nothing installed and nothing on offer: the built-in path, stated plainly. */
@Composable
private fun BuiltInState(
    ai: SamplerViewModel.AiState,
    accent: Color,
    onCheckForUpdate: () -> Unit,
) {
    Text(
        text = "Separating with the built-in processor. No model needed and " +
            "nothing to download.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // The reason, when there is one worth giving. A model that exists but will
    // not run on this device is more useful to say than to hide.
    ai.blockedReason?.let { reason ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    PatternPill(
        label = if (ai.checking) "Checking…" else "Check for AI model",
        active = false,
        accent = accent,
        enabled = !ai.checking,
        modifier = Modifier.fillMaxWidth(),
        onClick = onCheckForUpdate,
    )
}

/**
 * A model is available. The user has to ask for it — the brief says not to
 * silently pull hundreds of megabytes, and the size is on the button so the
 * decision is informed rather than a surprise on a mobile connection.
 */
@Composable
private fun OfferState(
    ai: SamplerViewModel.AiState,
    accent: Color,
    onInstall: () -> Unit,
) {
    val model = ai.available ?: return
    Text(
        text = model.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = "${model.stems.size} stems · ${StemModelManager.formatBytes(model.sizeBytes)} · " +
            (if (model.backend.isAccelerated) "Snapdragon NPU" else "CPU"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    PatternPill(
        label = "Download ${StemModelManager.formatBytes(model.sizeBytes)}",
        active = true,
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
        onClick = onInstall,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Downloads once. Separation then works offline.",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InstallingState(
    ai: SamplerViewModel.AiState,
    accent: Color,
    onCancel: () -> Unit,
) {
    val progress = ai.progress
    val fraction by animateFloatAsState(
        targetValue = progress?.fraction ?: 0f,
        animationSpec = tween(220),
        label = "modelInstall",
    )

    Text(
        text = progress?.stage ?: "Starting",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(MonoDimens.shapeSm)
            .background(accent.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(MonoDimens.shapeSm)
                .background(accent),
        )
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = if (progress != null) {
            "${(fraction * 100).roundToInt()}% · " +
                "${StemModelManager.formatBytes(progress.bytesDownloaded)} of " +
                StemModelManager.formatBytes(progress.totalBytes)
        } else {
            ""
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    PatternPill(
        label = "Cancel",
        active = false,
        accent = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
        onClick = onCancel,
    )
    Spacer(Modifier.height(6.dp))
    // Says the thing that stops a cancel feeling expensive.
    Text(
        text = "Cancelling keeps what has downloaded. Resuming picks up where " +
            "it stopped.",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadyState(
    ai: SamplerViewModel.AiState,
    accent: Color,
    onCheckForUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val model = ai.installed ?: return

    Text(
        text = model.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = "v${model.version} · ${model.stems.size} stems · " +
            StemModelManager.formatBytes(ai.storageBytes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The upstream terms, when there are any worth showing. Displayed, not
    // enforced — see MODEL_CARD.md.
    if (model.license.restricted) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = model.license.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (ai.updateAvailable != null) {
        Spacer(Modifier.height(10.dp))
        PatternPill(
            label = "Update to v${ai.updateAvailable.version}",
            active = true,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCheckForUpdate,
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PatternPill(
            label = if (ai.checking) "Checking…" else "Check for update",
            active = false,
            accent = accent,
            enabled = !ai.checking,
            modifier = Modifier.weight(2f),
            onClick = onCheckForUpdate,
        )
        PatternPill(
            label = "Delete",
            active = false,
            accent = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
            onClick = onDelete,
        )
    }
}

/**
 * Every backend and why it is or is not running.
 *
 * Unavailable rows stay in the list on purpose. "Snapdragon NPU — model not
 * installed" is something a person can act on; an absent row reads as "your
 * phone cannot do this", which is a different and usually wrong message.
 */
@Composable
fun BackendStatusList(
    statuses: List<BackendStatus>,
    active: BackendKind?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        statuses.forEach { status ->
            val running = status.kind == active
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                running -> accent
                                status.available -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (running) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status.unavailableReason ?: if (running) "Running" else "Ready",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
