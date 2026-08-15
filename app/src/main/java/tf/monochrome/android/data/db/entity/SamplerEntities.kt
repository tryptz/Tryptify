// SPDX-License-Identifier: GPL-3.0-or-later
//
// Room storage for the sampler and the pattern looper.

package tf.monochrome.android.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved sample.
 *
 * The PCM itself lives on disk under the app's files directory — only the
 * metadata is in Room, so opening the browser never pulls audio into memory.
 * [waveformPath] points at a small pre-rendered peak file so a list of two
 * hundred samples can draw every thumbnail without decoding anything.
 *
 * `source*` records where a captured sample came from. It is provenance, not a
 * link: deleting the track it was taken from must not orphan or delete the
 * sample, and re-capture is never attempted from it.
 */
@Entity(
    tableName = "sampler_samples",
    indices = [Index("category"), Index("createdAt"), Index("nameKey")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Lower-cased [name], so the browser's search is an indexed range scan. */
    val nameKey: String = name.lowercase(),
    val filePath: String,
    val durationMs: Long = 0,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val frameCount: Long = 0,
    /** One of the ids in `SampleCategory`. */
    val category: String = "USER",
    /** Comma-separated free tags, on top of the category. */
    val tags: String = "",
    val sourceTrackTitle: String? = null,
    val sourceArtist: String? = null,
    val sourceTimestampMs: Long? = null,
    /** "CLEAN" or "PROCESSED" for captures; null for imports. */
    val captureSource: String? = null,
    val bpm: Float? = null,
    val musicalKey: String? = null,
    /** Linear playback trim applied on load, 1.0 = as recorded. */
    val gain: Float = 1.0f,
    val waveformPath: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A pattern: a short loop, addressed in the bank by [bankSlot].
 *
 * [bankSlot] is what the engine indexes by, and what the UI shows as A01…H08.
 * It is unique so a slot always names exactly one pattern, which is the whole
 * premise of tapping a bank button to switch.
 */
@Entity(
    tableName = "sampler_patterns",
    indices = [Index(value = ["bankSlot"], unique = true)],
)
data class PatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankSlot: Int,
    val name: String,
    val lengthSteps: Int = 16,
    val stepsPerBeat: Int = 4,
    val beatsPerBar: Int = 4,
    /** Null follows the global transport tempo. */
    val bpmOverride: Float? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One channel of one pattern.
 *
 * Channels are per-pattern rather than global on purpose: duplicating a
 * pattern and swapping one channel's sample is the core of the FL-style
 * workflow, and a shared channel list would make that impossible.
 *
 * [sampleId] is deliberately *not* a foreign key. Deleting a sample that a
 * pattern still references must leave the pattern intact with an empty
 * channel — a cascade would silently delete the user's patterns along with a
 * sample they cleared out.
 */
@Entity(
    tableName = "sampler_pattern_channels",
    foreignKeys = [
        ForeignKey(
            entity = PatternEntity::class,
            parentColumns = ["id"],
            childColumns = ["patternId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("patternId"), Index("sampleId")],
)
data class PatternChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patternId: Long,
    val position: Int,
    val name: String,
    val sampleId: Long? = null,
    val volume: Float = 1.0f,
    val pan: Float = 0.0f,
    val pitch: Float = 0.0f,
    val sampleStart: Float = 0.0f,
    val sampleEnd: Float = 1.0f,
    val attackMs: Float = 0.0f,
    val releaseMs: Float = 8.0f,
    /** >= 20000 means the filter is off. */
    val filterHz: Float = 22000.0f,
    val reverse: Boolean = false,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val enabled: Boolean = true,
    /** ARGB accent used by the trig row, 0 = derive from the theme. */
    val colorArgb: Int = 0,
)

/**
 * One trig.
 *
 * Only enabled steps are stored. A 64-step 16-channel pattern with four hits
 * in it is four rows, not 1024 — which matters because these are read on every
 * pattern switch.
 *
 * [pitch], [probability], [pan] and [sampleStart] are the parameter-lock
 * fields. [locks] is a bitmask saying which of them override the channel;
 * v1 writes velocity only and leaves the mask at zero, so turning locks on
 * later is a UI change rather than a schema change.
 */
@Entity(
    tableName = "sampler_pattern_steps",
    primaryKeys = ["channelId", "stepIndex"],
    foreignKeys = [
        ForeignKey(
            entity = PatternChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("channelId")],
)
data class PatternStepEntity(
    val channelId: Long,
    val stepIndex: Int,
    val enabled: Boolean = true,
    val velocity: Int = 100,
    val pitch: Int = 0,
    val probability: Int = 100,
    val pan: Int = 0,
    val sampleStart: Int = 0,
    val locks: Int = 0,
)
