// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * Plays a hit sound kit ([GlyphHitKit]) on each press.
 *
 * A [SoundPool]: Android's player for short game sounds, made for this —
 * several at once, mixed, started with as little delay as the phone allows.
 * A kit's sounds are rendered once ([GlyphHitSynth]), written to the cache as
 * WAV files and loaded; until they are, a press is silent rather than late.
 *
 * A game sound, as the metronome and the wheel's: it follows the media
 * volume and ducks under a call. A pool that cannot be made leaves the game
 * silent, never broken.
 */
class GlyphHitSoundPlayer(private val context: Context) {

    private val pool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }.onFailure { Log.w(TAG, "no sound pool: ${it.message}") }.getOrNull()

    private val loader = Executors.newSingleThreadExecutor { Thread(it, "GlyphHitSounds").apply { isDaemon = true } }

    @Volatile private var kit: GlyphHitKit = GlyphHitKit.OFF
    /** The kit's sounds by voice; a voice is in [ready] once loaded. */
    @Volatile private var sounds: IntArray = IntArray(0)
    private val ready = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** A voice to play as soon as it has loaded: the kit just picked, heard once. */
    @Volatile private var previewSound: Int = 0
    @Volatile private var previewVolume: Float = 1f

    init {
        pool?.setOnLoadCompleteListener { _, soundId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            ready.add(soundId)
            playPreviewIfReady()
        }
    }

    /** Loads [next]'s sounds, dropping the last kit's; [preview] plays its first once loaded. */
    fun setKit(next: GlyphHitKit, preview: Boolean = false, volume: Float = 1f) {
        val pool = pool ?: return
        if (next == kit && sounds.isNotEmpty()) {
            if (preview) play(GlyphLane.DOWN, CourseType.DANCE, volume)
            return
        }
        kit = next
        loader.execute {
            val old = sounds
            sounds = IntArray(0)
            old.forEach { pool.unload(it); ready.remove(it) }
            if (next == GlyphHitKit.OFF) return@execute
            val dir = File(context.cacheDir, "hitsounds").apply { mkdirs() }
            val loaded = GlyphHitSynth.voices(next).mapIndexed { index, samples ->
                val file = File(dir, "${next.id}_$index.wav")
                runCatching {
                    file.writeBytes(GlyphHitSynth.wav(samples))
                    pool.load(file.absolutePath, 1)
                }.getOrElse { Log.w(TAG, "could not load ${file.name}: ${it.message}"); 0 }
            }.toIntArray()
            if (preview) {
                previewVolume = volume
                previewSound = loaded.getOrElse(GlyphHitSynth.voiceFor(next, GlyphLane.DOWN, CourseType.DANCE)) { 0 }
                // It may have loaded already, before the preview was asked for.
                playPreviewIfReady()
            }
            // Only now the kit plays: a press against half-loaded ids is silent.
            if (kit == next) sounds = loaded else loaded.forEach { pool.unload(it) }
        }
    }

    /** Plays the preview once, whichever of the load and the request comes last. */
    private fun playPreviewIfReady() {
        val pool = pool ?: return
        val id = synchronized(this) {
            val wanted = previewSound
            if (wanted == 0 || wanted !in ready) return
            previewSound = 0
            wanted
        }
        pool.play(id, previewVolume, previewVolume, 1, 0, 1f)
    }

    /** The sound of a press of [lane] in [course], at [volume] (0 to 1). */
    fun play(lane: GlyphLane, course: CourseType, volume: Float) {
        val pool = pool ?: return
        val current = kit
        if (current == GlyphHitKit.OFF || volume <= 0f) return
        val id = sounds.getOrElse(GlyphHitSynth.voiceFor(current, lane, course)) { 0 }
        if (id == 0 || id !in ready) return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        runCatching { loader.shutdownNow() }
        runCatching { pool?.release() }
    }

    private companion object {
        const val TAG = "GlyphHitSounds"
        /** Enough for a fast stream on nine buttons to overlap without cutting off. */
        const val MAX_STREAMS = 8
    }
}
