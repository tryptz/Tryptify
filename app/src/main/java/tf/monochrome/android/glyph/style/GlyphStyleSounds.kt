// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import kotlin.random.Random

/**
 * The sounds an imported StepMania style brings to a run: its theme's hit and
 * miss, and its announcer's voice at the moments a run has. Nothing plays
 * without one chosen (Trypt Mania › Themes); the app's own theme is silent
 * there, as it has always been.
 *
 * Game sounds, as the metronome's: `USAGE_GAME`, following the media volume
 * and ducking under a call. They play on Android's output, not through an
 * exclusive USB DAC, which carries the song alone (see `UsbGameAudio`).
 * Main thread.
 */
class GlyphStyleSounds(context: Context) {
    private val appContext = context.applicationContext
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Hits come many a second: a SoundPool, its clips decoded ahead.
    private var pool: SoundPool? = null
    private var hit = 0
    private var miss = 0
    private var themeDir: File? = null

    private var announcerDir: File? = null
    private var voice: MediaPlayer? = null

    /** The theme whose hit and miss sounds play, or null for none. */
    fun useTheme(dir: File?) {
        if (dir == themeDir) return
        themeDir = dir
        pool?.release()
        pool = null
        hit = 0
        miss = 0
        val sounds = dir?.let { File(it, "sounds") }?.listFiles().orEmpty()
        if (sounds.isEmpty()) return
        val soundPool = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attributes).build()
        hit = sounds.firstOrNull { it.nameWithoutExtension == "hit" }?.let { soundPool.load(it.path, 1) } ?: 0
        miss = sounds.firstOrNull { it.nameWithoutExtension == "miss" }?.let { soundPool.load(it.path, 1) } ?: 0
        pool = soundPool
    }

    /** The announcer who speaks, or null for none. */
    fun useAnnouncer(dir: File?) {
        if (dir == announcerDir) return
        announcerDir = dir
        stopVoice()
    }

    /** A note judged: the theme's hit, or its miss. */
    fun judged(isHit: Boolean) {
        val id = if (isHit) hit else miss
        if (id != 0) pool?.play(id, HIT_VOLUME, HIT_VOLUME, 1, 0, 1f)
    }

    /** One of the announcer's clips for [event], picked at random as StepMania picks; nothing without one. */
    fun announce(event: AnnouncerEvent) {
        val clips = announcerDir?.let { File(it, event.key) }?.listFiles()?.filter { it.isFile }.orEmpty()
        if (clips.isEmpty()) return
        stopVoice()
        voice = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(clips[Random.nextInt(clips.size)].path)
                setOnCompletionListener { player -> player.release(); if (voice === player) voice = null }
                setOnErrorListener { player, _, _ -> player.release(); if (voice === player) voice = null; true }
                prepare()
                start()
            }
        }.getOrNull()
    }

    fun stopVoice() {
        voice?.let { runCatching { it.stop() }; it.release() }
        voice = null
    }

    fun release() {
        stopVoice()
        pool?.release()
        pool = null
    }

    private companion object {
        const val MAX_STREAMS = 6
        const val HIT_VOLUME = 0.8f
    }
}
