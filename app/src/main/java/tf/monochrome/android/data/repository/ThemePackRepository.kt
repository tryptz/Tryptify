package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.ThemeColours
import tf.monochrome.android.domain.model.ThemeCrt
import tf.monochrome.android.domain.model.ThemeMaps
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePackCodec
import tf.monochrome.android.domain.model.ThemePart
import tf.monochrome.android.domain.model.ThemeVisualizer
import tf.monochrome.android.ui.theme.BundledFonts
import tf.monochrome.android.ui.theme.themeDisplayNames
import tf.monochrome.android.visualizer.VisualizerBlendMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The listener's look as a theme pack, and a theme pack put on as the look.
 *
 * Applying always keeps the listener's personal settings (their lyric font,
 * Bluetooth delay, quality and tint choices) and first saves the whole look
 * as it was, so one tap on Undo puts everything back — whichever parts the
 * pack touched.
 */
@Singleton
class ThemePackRepository @Inject constructor(
    private val preferences: PreferencesManager,
    private val musicLinks: MusicLinksRepository,
) {
    private val lock = Mutex()

    /** Whether there is a look to go back to. */
    val canUndo: Flow<Boolean> = preferences.themePackUndo.map { it != null }

    /** The undo's name: the pack that replaced it. */
    val undoName: Flow<String?> = preferences.themePackUndo.map { text ->
        text?.let { ThemePackCodec.decode(it)?.name }
    }

    /** What theme ids, fonts and colours a pack may name on this build. */
    fun decode(text: String?): ThemePack? =
        ThemePackCodec.decode(text, themeDisplayNames.keys, ::isBundledFont)

    /** The current look, every part, as a pack named [name]. */
    suspend fun capture(name: String = ""): ThemePack {
        val p = preferences
        val ambient = p.ambientVisualizer.first()
        val font = p.customFontUri.first()
        return ThemePack(
            name = name,
            colours = ThemeColours(
                theme = p.theme.first(),
                paper = p.themePaper.first(),
                custom = p.customThemeEnabled.first(),
                accent = p.customAccentColor.first(),
                background = p.customBackgroundColor.first(),
            ),
            lyrics = p.lyricsFx.first(),
            playerGlass = p.playerGlass.first(),
            panelGlass = p.miniPlayerGlass.first(),
            // An imported font is a file on this phone; it cannot travel, so
            // a pack made with one simply carries no font.
            font = when {
                font.isNullOrBlank() -> ""
                isBundledFont(font) -> font
                else -> null
            },
            visualizer = ThemeVisualizer(
                waterfall = p.spectrumWaterfall.first(),
                waveCandy = p.waveCandy.first(),
                spectrogram3d = p.spectrogram3d.first(),
                ambientOn = ambient.enabled,
                ambientOpacity = ambient.opacityPercent,
                ambientBlackPoint = ambient.blackPointPercent,
                ambientBlend = ambient.blend.id,
                ambientHideCover = ambient.hideCover,
            ),
            maps = ThemeMaps(globe = p.globeFx.first(), galaxy = p.galaxyVisuals.first()),
            links = musicLinks.visualLinks.first(),
            crt = ThemeCrt(
                look = p.appTvShader.first().id,
                tuning = p.appTvTuning.first().clamped().encode(),
                target = if (p.appTvOnLyrics.first()) ThemeCrt.TARGET_LYRICS else ThemeCrt.TARGET_APP,
            ),
        )
    }

    /**
     * Puts on the [keep] parts of [pack]. Saves the look as it was first, as
     * the undo. Personal settings are the listener's own throughout.
     */
    suspend fun apply(pack: ThemePack, keep: Set<ThemePart>) = lock.withLock {
        val before = capture(name = pack.name)
        preferences.setThemePackUndo(ThemePackCodec.encode(before))
        write(pack.only(keep).cleaned(themeDisplayNames.keys, ::isBundledFont))
    }

    /** Puts back the look from before the last pack. False when there was none. */
    suspend fun undo(): Boolean = lock.withLock {
        val saved = decode(preferences.themePackUndo.first()) ?: return@withLock false
        write(saved)
        preferences.setThemePackUndo(null)
        true
    }

    private suspend fun write(pack: ThemePack) {
        val p = preferences
        pack.colours?.let { c ->
            p.setTheme(c.theme)
            p.setThemePaper(c.paper)
            p.setCustomAccentColor(c.accent)
            p.setCustomBackgroundColor(c.background)
            p.setCustomThemeEnabled(c.custom)
        }
        pack.lyrics?.let { p.setLyricsFx(it.withPersonalFrom(p.lyricsFx.first()).clamped()) }
        pack.playerGlass?.let { p.setPlayerGlass(it.withPersonalFrom(p.playerGlass.first()).clamped()) }
        pack.panelGlass?.let { p.setMiniPlayerGlass(it.withPersonalFrom(p.miniPlayerGlass.first()).clamped()) }
        pack.font?.let { p.setCustomFontUri(it.ifEmpty { null }) }
        pack.visualizer?.let { v ->
            v.waterfall?.let { p.setSpectrumWaterfall(it) }
            v.waveCandy?.let { p.setWaveCandy(it) }
            // The look only: this phone keeps its own quality and preview switch.
            v.spectrogram3d?.let { p.setSpectrogram3d(it.withPersonalFrom(p.spectrogram3d.first()).clamped()) }
            v.ambientOn?.let { p.setAmbientVisualizerEnabled(it) }
            v.ambientOpacity?.let { p.setAmbientVisualizerOpacity(it) }
            v.ambientBlackPoint?.let { p.setAmbientVisualizerBlackPoint(it) }
            v.ambientBlend?.let { p.setAmbientVisualizerBlend(VisualizerBlendMode.fromId(it)) }
            v.ambientHideCover?.let { p.setAmbientVisualizerHideCover(it) }
        }
        pack.maps?.let { m ->
            m.globe?.let { p.setGlobeFx(it) }
            m.galaxy?.let { p.setGalaxyVisuals(it) }
        }
        pack.links?.let { musicLinks.replaceAll(it) }
        pack.crt?.let { c ->
            c.look?.let { p.setAppTvShader(tf.monochrome.android.glyph.engine.GlyphTvShader.fromId(it)) }
            c.tuning?.let { p.setAppTvTuning(tf.monochrome.android.glyph.engine.GlyphTvTuning.decode(it).clamped()) }
            c.target?.let { p.setAppTvOnLyrics(it == ThemeCrt.TARGET_LYRICS) }
        }
    }

    private fun isBundledFont(id: String): Boolean = BundledFonts.byId(id) != null
}
