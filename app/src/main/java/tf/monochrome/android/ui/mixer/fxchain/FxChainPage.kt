package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/** One effect in the frozen visual snapshot. [uid] is a stable, unique LazyColumn key. */
private class ChainItem(val uid: Long, val plugin: PluginInstance)

/**
 * Serum-style vertical FX chain for the selected bus. Replaces the old node
 * canvas as the swipe-up page. Signal flows top→bottom: IN cap → effect cards
 * → OUT cap. Cards can be reordered by dragging their handle; the reorder is
 * committed to the engine once, at drag end.
 */
@Composable
fun FxChainPage(
    buses: List<BusConfig>,
    selectedBusIndex: Int,
    enabled: Boolean,
    busAccent: (Int) -> Color,
    onSelectBus: (Int) -> Unit,
    onAddEffect: () -> Unit,
    onBypass: (busIndex: Int, slotIndex: Int) -> Unit,
    onRemove: (busIndex: Int, slotIndex: Int) -> Unit,
    onDryWet: (busIndex: Int, slotIndex: Int, dryWet: Float) -> Unit,
    onParam: (busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float) -> Unit,
    onMove: (busIndex: Int, from: Int, to: Int) -> Unit,
) {
    val bus = buses.getOrNull(selectedBusIndex)
    val plugins = bus?.plugins ?: emptyList()
    val accent = busAccent(selectedBusIndex)

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Frozen visual snapshot: reconciled from `plugins` by position, reusing
    // existing uids so keys stay stable across param edits. Reset per bus.
    val visualChain = remember(selectedBusIndex) { mutableStateListOf<ChainItem>() }
    var nextUid by remember(selectedBusIndex) { mutableStateOf(0L) }
    var expandedUids by remember(selectedBusIndex) { mutableStateOf(setOf<Long>()) }

    val dragState = rememberFxChainDragState(
        listState = listState,
        scope = scope,
        indexOfKey = { uid -> visualChain.indexOfFirst { it.uid == uid }.takeIf { it >= 0 } },
        onMove = { from, to -> if (from in visualChain.indices) visualChain.add(to, visualChain.removeAt(from)) },
        onCommit = { from, to -> onMove(selectedBusIndex, from, to) },
    )

    // Reconcile on bus switch or plugin-list change, and never mid-drag (keeps
    // the list frozen during a gesture so a StateFlow emission can't reshuffle
    // it). Keyed on selectedBusIndex too: two buses with structurally-equal
    // plugin lists would otherwise be seen as the same key and skip the sync.
    LaunchedEffect(selectedBusIndex, plugins) {
        if (dragState.isDragging) return@LaunchedEffect
        val reconciled = plugins.mapIndexed { i, p ->
            val existing = visualChain.getOrNull(i)
            ChainItem(uid = existing?.uid ?: nextUid++, plugin = p)
        }
        visualChain.clear()
        visualChain.addAll(reconciled)
        expandedUids = expandedUids.intersect(visualChain.map { it.uid }.toSet())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(if (enabled) 1f else 0.55f)
    ) {
        BusSelectorRow(buses, selectedBusIndex, busAccent, onSelectBus)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(MonoDimens.spacingSm),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)
        ) {
            item(key = "in") {
                ChainEndCap(
                    label = "IN — ${bus?.name ?: "Bus"}",
                    accent = accent,
                    modifier = Modifier.then(if (!dragState.isDragging) Modifier.animateItem() else Modifier)
                )
            }

            itemsIndexed(visualChain, key = { _, it -> it.uid }) { index, item ->
                val isDragged = dragState.draggingKey == item.uid
                val cardAccent = item.plugin.type?.category
                    ?.let { FxChainColors.categoryColor(it) }
                    ?: MaterialTheme.colorScheme.primary
                FxCard(
                    position = index + 1,
                    plugin = item.plugin,
                    accent = cardAccent,
                    expanded = item.uid in expandedUids,
                    dragging = isDragged,
                    dragHandle = Modifier.pointerInput(item.uid) {
                        detectDragGestures(
                            onDragStart = {
                                expandedUids = emptySet()
                                dragState.onDragStart(item.uid)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragState.onDrag(dragAmount.y)
                            },
                            onDragEnd = { dragState.onDragEnd() },
                            onDragCancel = { dragState.onDragEnd() },
                        )
                    },
                    onToggleExpand = {
                        expandedUids = if (item.uid in expandedUids) expandedUids - item.uid
                                       else expandedUids + item.uid
                    },
                    onBypass = { onBypass(selectedBusIndex, index) },
                    onRemove = { onRemove(selectedBusIndex, index) },
                    onDryWet = { dw -> onDryWet(selectedBusIndex, index, dw) },
                    onParam = { pi, v -> onParam(selectedBusIndex, index, pi, v) },
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = dragState.translationFor(item.uid)
                            val s = if (isDragged) 1.02f else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .then(if (!isDragged) Modifier.animateItem() else Modifier)
                )
            }

            item(key = "out") {
                ChainEndCap(
                    label = if (bus?.isMaster == true) "OUT — Device" else "OUT — Master",
                    accent = accent,
                    isOutput = true,
                    modifier = Modifier.then(if (!dragState.isDragging) Modifier.animateItem() else Modifier)
                )
            }

            item(key = "add") {
                AddEffectBar(
                    count = plugins.size,
                    max = DspEngineManager.MAX_PLUGINS_PER_BUS,
                    accent = accent,
                    onClick = onAddEffect,
                    modifier = Modifier.then(if (!dragState.isDragging) Modifier.animateItem() else Modifier)
                )
            }
        }
    }
}

@Composable
private fun BusSelectorRow(
    buses: List<BusConfig>,
    selectedBusIndex: Int,
    busAccent: (Int) -> Color,
    onSelectBus: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingXs),
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs)
    ) {
        buses.forEachIndexed { index, bus ->
            val selected = index == selectedBusIndex
            val accent = busAccent(index)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MonoDimens.shapePill)
                    .liquidGlass(
                        shape = MonoDimens.shapePill,
                        tintAlpha = if (selected) 0.22f else 0.08f
                    )
                    .clickable { onSelectBus(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = if (selected) 1f else 0.5f))
                    )
                    Text(
                        text = bus.name,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainEndCap(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    isOutput: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeSm)
            .liquidGlass(shape = MonoDimens.shapeSm, tintAlpha = 0.06f)
            .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)
    ) {
        Icon(
            Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = accent.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun AddEffectBar(
    count: Int,
    max: Int,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val atMax = count >= max
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(MonoDimens.shapeMd)
            .liquidGlass(
                shape = MonoDimens.shapeMd,
                tintAlpha = if (atMax) 0.04f else 0.10f
            )
            .clickable(enabled = !atMax, onClick = onClick)
            .alpha(if (atMax) 0.5f else 1f)
            .padding(horizontal = MonoDimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = if (atMax) "Chain full" else "Add Effect",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp)
        )
        Text(
            text = "  ($count/$max)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
