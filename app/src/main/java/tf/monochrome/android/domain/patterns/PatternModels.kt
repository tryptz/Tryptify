// SPDX-License-Identifier: GPL-3.0-or-later
//
// The pattern looper's domain model — what the UI edits and what the engine is
// fed. Deliberately free of Room and of Android: everything here is a plain
// data class, which is what lets the pattern operations in `PatternOps` be
// tested on the JVM without a device.

package tf.monochrome.android.domain.patterns

/** Hard ceilings, mirrored from `cpp/sampler/sampler_types.h`. */
object PatternLimits {
    const val MAX_CHANNELS = 16
    const val MAX_STEPS = 64
    const val MAX_PATTERNS = 64
    const val BANK_ROWS = 8

    /** Bank letters A…H, eight slots each. */
    val BANKS = listOf("A", "B", "C", "D", "E", "F", "G", "H")

    /** Slot 0 reads "A01", slot 9 reads "B02". */
    fun slotName(slot: Int): String {
        val safe = slot.coerceIn(0, MAX_PATTERNS - 1)
        val bank = BANKS[safe / BANK_ROWS]
        val index = safe % BANK_ROWS + 1
        return "$bank%02d".format(index)
    }
}

/**
 * Offered pattern lengths.
 *
 * The engine handles any length from 1 to 64 — [PatternLength] is only what
 * the picker shows. 12 is in the list because a pattern looper without a
 * three-against-four option is missing most of what makes odd lengths worth
 * having.
 */
enum class PatternLength(val steps: Int, val label: String) {
    FOUR(4, "4"),
    EIGHT(8, "8"),
    TWELVE(12, "12"),
    SIXTEEN(16, "16"),
    THIRTY_TWO(32, "32"),
    SIXTY_FOUR(64, "64");

    companion object {
        fun nearest(steps: Int): PatternLength =
            entries.minByOrNull { kotlin.math.abs(it.steps - steps) } ?: SIXTEEN
    }
}

/** How a queued pattern takes over from the one that is playing. */
enum class PatternSwitchMode { NEXT_LOOP, IMMEDIATE }

/**
 * One trig.
 *
 * [velocity] is the only lock v1 writes. The rest exist so the engine, the
 * schema and the UI all already agree on what a parameter lock is by the time
 * one is switched on — adding them later would have meant a migration, a JNI
 * change and a step-editor rewrite at once.
 */
data class Step(
    val enabled: Boolean = false,
    val velocity: Int = 100,
    val pitch: Int = 0,
    val probability: Int = 100,
    val pan: Int = 0,
    val sampleStart: Int = 0,
    val locks: Int = 0,
) {
    val isLocked: Boolean get() = locks != 0

    companion object {
        const val LOCK_PITCH = 1
        const val LOCK_PAN = 2
        const val LOCK_START = 4
        val OFF = Step()
    }
}

/**
 * A channel: one sample, its sound settings, and its row of trigs.
 *
 * [steps] is always [PatternLimits.MAX_STEPS] long regardless of the pattern's
 * length, so shortening a pattern and lengthening it again brings the trigs
 * back rather than destroying them. Only the first `length` entries sound.
 */
data class PatternChannel(
    val id: Long = 0,
    val name: String,
    val sampleId: Long? = null,
    val volume: Float = 1.0f,
    val pan: Float = 0.0f,
    val pitch: Float = 0.0f,
    val sampleStart: Float = 0.0f,
    val sampleEnd: Float = 1.0f,
    val attackMs: Float = 0.0f,
    val releaseMs: Float = 8.0f,
    val filterHz: Float = FILTER_OFF,
    val reverse: Boolean = false,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val enabled: Boolean = true,
    val colorArgb: Int = 0,
    val steps: List<Step> = List(PatternLimits.MAX_STEPS) { Step.OFF },
) {
    fun step(index: Int): Step = steps.getOrElse(index) { Step.OFF }

    fun withStep(index: Int, step: Step): PatternChannel {
        if (index !in 0 until PatternLimits.MAX_STEPS) return this
        return copy(steps = steps.toMutableList().also { it[index] = step })
    }

    fun clearedSteps(): PatternChannel = copy(steps = List(PatternLimits.MAX_STEPS) { Step.OFF })

    companion object {
        /** Anything at or above this reads as "no filter" to the engine. */
        const val FILTER_OFF = 22000f
    }
}

/**
 * A pattern: a loop of [lengthSteps] steps across its channels.
 *
 * [bankSlot] is the identity the engine and the bank buttons share; [id] is
 * only Room's.
 */
data class Pattern(
    val id: Long = 0,
    val bankSlot: Int,
    val name: String = PatternLimits.slotName(bankSlot),
    val lengthSteps: Int = 16,
    val stepsPerBeat: Int = 4,
    val beatsPerBar: Int = 4,
    val bpmOverride: Float? = null,
    val channels: List<PatternChannel> = emptyList(),
) {
    val barCount: Int
        get() {
            val perBar = (stepsPerBeat * beatsPerBar).coerceAtLeast(1)
            return ((lengthSteps + perBar - 1) / perBar).coerceAtLeast(1)
        }

    /** True when at least one channel has a trig on. */
    val hasContent: Boolean
        get() = channels.any { channel ->
            channel.steps.take(lengthSteps).any { it.enabled }
        }

    fun channelAt(index: Int): PatternChannel? = channels.getOrNull(index)

    fun withChannel(index: Int, transform: (PatternChannel) -> PatternChannel): Pattern {
        if (index !in channels.indices) return this
        return copy(channels = channels.toMutableList().also { it[index] = transform(it[index]) })
    }
}

/**
 * What the bank grid draws for a pattern it is not currently editing.
 *
 * The bank shows 64 buttons; loading every channel and trig behind each of
 * them to render a label would be a database read per button on every write.
 */
data class PatternSummary(
    val id: Long,
    val bankSlot: Int,
    val name: String,
    val lengthSteps: Int,
) {
    val slotLabel: String get() = PatternLimits.slotName(bankSlot)
}

/** The library's fixed shelves. Free tags sit on top of these, not instead. */
enum class SampleCategory(val id: String, val label: String) {
    KICKS("KICKS", "Kicks"),
    SNARES("SNARES", "Snares"),
    HATS("HATS", "Hats"),
    PERCUSSION("PERCUSSION", "Percussion"),
    VOCALS("VOCALS", "Vocals"),
    BASS("BASS", "Bass"),
    FX("FX", "FX"),
    LOOPS("LOOPS", "Loops"),
    USER("USER", "User");

    companion object {
        fun fromId(id: String?): SampleCategory =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: USER
    }
}

/** Where a capture was taken from in the audio pipeline. */
enum class CaptureSource(val id: String, val label: String) {
    /** Decoder output, before any Tryptify processing. */
    CLEAN("CLEAN", "Clean"),

    /** After the whole DSP chain — EQ, mix bus, Oxford, crossfeed. */
    PROCESSED("PROCESSED", "Processed");

    companion object {
        fun fromId(id: String?): CaptureSource =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLEAN
    }
}

/** A sample as the library and the browser see it. The PCM stays on disk. */
data class SampleRef(
    val id: Long,
    val name: String,
    val filePath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
    val category: SampleCategory,
    val tags: List<String> = emptyList(),
    val sourceTrackTitle: String? = null,
    val sourceArtist: String? = null,
    val sourceTimestampMs: Long? = null,
    val captureSource: CaptureSource? = null,
    val bpm: Float? = null,
    val musicalKey: String? = null,
    val gain: Float = 1.0f,
    val waveformPath: String? = null,
    val favorite: Boolean = false,
    /** One of the ids in `Stem` when this came out of the Stem Studio. */
    val stemType: String? = null,
    /** The sample this was separated from, if any. */
    val sourceSampleId: Long? = null,
    val createdAt: Long = 0,
)

/** Everything the transport bar shows, published from the audio clock. */
data class TransportState(
    val playing: Boolean = false,
    val recording: Boolean = false,
    val bpm: Float = 124f,
    val swing: Float = 0f,
    val metronome: Boolean = false,
    val countInBars: Int = 0,
    val countInRemaining: Int = 0,
    val currentStep: Int = 0,
    val currentPattern: Int = 0,
    val queuedPattern: Int = -1,
    val activeVoices: Int = 0,
    val switchMode: PatternSwitchMode = PatternSwitchMode.NEXT_LOOP,
    /** Steps per quantum for live recording; 0 means unquantized. */
    val recordQuantum: Int = 1,
)
