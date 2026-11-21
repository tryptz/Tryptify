package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.atmos.AtmosNative
import tf.monochrome.android.audio.dsp.BinauralRoomNative
import tf.monochrome.android.audio.dsp.ChannelLayout
import tf.monochrome.android.audio.dsp.model.FxTapFrame
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.domain.model.CustomRoom
import tf.monochrome.android.domain.model.RoomShape
import tf.monochrome.android.ui.mixer.getParamDefs
import tf.monochrome.android.ui.settings.AtmosRoom3d
import tf.monochrome.android.ui.settings.BUILT_IN_ROOM_DECAY
import kotlin.math.pow

/**
 * The Binaural Room's picture: the spatial renderer's room, drawn by the same
 * [AtmosRoom3d], because it is the same room (`snapins/binaural_room.h` runs
 * the renderer's own `VirtualRoom`). The room is the built-in one, with its
 * 28 speakers where the renderer has them ([AtmosNative.nativeRoomLayout]).
 *
 * What is drawn is what is heard: the speakers that light are the ones the
 * stereo pair stands at for the Angle set ([FxVisualMath.roomPair], the
 * effect's own sharing between two of the ring's speakers, on the speakers
 * the effect itself names through [BinauralRoomNative.ring]), as bright as
 * the signal going in, and the room's air holds as much as Room lets in.
 * Bypassed, nothing plays in it. Drag sideways to walk round it.
 *
 * On the master of a stream wider than stereo ([LocalRoomBedChannels]) the
 * room has every channel, each at its own speaker, and so does the picture:
 * the speakers the effect itself names for each
 * ([BinauralRoomNative.roleSpeakers]). They all light as bright as the
 * master's level, the one reading there is at that slot, not each as loud as
 * its own channel.
 *
 * With My room on, the room drawn is that one: its width, depth and height,
 * and the time it takes to die away ([FxVisualMath.roomDecaySeconds], the
 * render's own reckoning).
 */
@Composable
internal fun BinauralRoomVisual(
    plugin: PluginInstance,
    modifier: Modifier = Modifier,
    slotIndex: Int = -1,
    live: FxTapFrame? = null,
) {
    val type = plugin.type ?: return
    val defs = remember(type) { getParamDefs(type) }
    fun value(i: Int) = plugin.parameters[i] ?: defs[i].default
    val layout = remember { roomLayout() }
    val ring = remember { BinauralRoomNative.ring }
    val count = layout.size / AtmosNative.ROOM_FIELDS
    val bedChannels = LocalRoomBedChannels.current
    // The rest of a surround mix's channels: where each plays from.
    val bed = remember(bedChannels) {
        val roles = if (bedChannels in 3..ChannelLayout.MAX_CHANNELS) ChannelLayout.roles(bedChannels) else IntArray(0)
        // As the mixer: a stream with a channel it cannot place has no bed.
        if (roles.isEmpty() || roles.any { it < 0 }) emptyList()
        else roles.drop(2).flatMap { BinauralRoomNative.roleSpeakers(it) }
    }

    // Each speaker's level, in the layout's order: null when nothing plays.
    val speakers: FloatArray? = if (ring == null || count == 0 || plugin.bypassed || live == null || slotIndex < 0) {
        null
    } else {
        val level = dbToLevel(live.inDb(slotIndex))
        val (inner, gInner, gOuter) = FxVisualMath.roomPair(value(ANGLE), ring.degrees)
        FloatArray(count).also { out ->
            for (side in arrayOf(ring.left, ring.right)) {
                // +=: at 0 degrees both sides are the one centre speaker.
                side.getOrNull(inner)?.takeIf { it in 0 until count }?.let { out[it] += level * gInner }
                side.getOrNull(inner + 1)?.takeIf { it in 0 until count }?.let { out[it] += level * gOuter }
            }
            for (tap in bed) if (tap.speaker in 0 until count) out[tap.speaker] += level * tap.gain
            for (i in out.indices) out[i] = out[i].coerceAtMost(1f)
        }
    }
    val myRoom = value(MY_ROOM) >= 0.5f
    val width = value(WIDTH)
    val depth = value(DEPTH)
    val height = value(HEIGHT)
    val damping = value(DAMPING)
    val shape = remember(myRoom, width, depth, height) {
        if (myRoom) RoomShape.of(CustomRoom(width = width, depth = depth, height = height)) else RoomShape.BUILT_IN
    }
    AtmosRoom3d(
        layout = layout,
        speakers = speakers,
        objects = null,
        shape = shape,
        roomAmount = (value(ROOM) / 100f).coerceIn(0f, 1f),
        decaySeconds = if (myRoom) FxVisualMath.roomDecaySeconds(width, depth, height, damping / 100f) else BUILT_IN_ROOM_DECAY,
        modifier = modifier.fillMaxWidth().height(ROOM_HEIGHT),
        sound = speakers != null,
        // The bus meter is not the renderer's thirty readings a second: the
        // room draws its own frames between them so the grain moves smoothly.
        slowLevels = true,
    )
}

/**
 * How many channels the stream has when this card is on the master of one
 * wider than stereo, where the room takes them all; 0 anywhere else.
 */
internal val LocalRoomBedChannels = androidx.compose.runtime.compositionLocalOf { 0 }

private const val ROOM = 0
private const val ANGLE = 2
private const val MY_ROOM = 4
private const val WIDTH = 5
private const val DEPTH = 6
private const val HEIGHT = 7
private const val DAMPING = 8
private val ROOM_HEIGHT = 220.dp

/** The meter's floor: at or under it nothing is playing. */
private const val SILENT_DB = -59.5f

/** A peak in dB as the room's speakers take it, 0 to 1. */
internal fun dbToLevel(db: Float): Float =
    if (db <= SILENT_DB) 0f else 10f.pow(db / 20f).coerceIn(0f, 1f)

/** The renderer's room layout, or an empty one where its library is missing. */
private fun roomLayout(): FloatArray = runCatching {
    val buf = FloatArray(AtmosNative.ROOM_MAX_SPEAKERS * AtmosNative.ROOM_FIELDS)
    val n = AtmosNative.nativeRoomLayout(buf)
    buf.copyOf(n.coerceAtLeast(0) * AtmosNative.ROOM_FIELDS)
}.getOrDefault(FloatArray(0))
