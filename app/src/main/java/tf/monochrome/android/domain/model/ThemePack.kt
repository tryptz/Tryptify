package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.glyph.engine.GlyphTvShader
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The parts of the app's look a theme pack can carry, in the order the store
 * lists them. Each is optional in a pack, and each can be left out when one is
 * imported.
 */
enum class ThemePart {
    COLOURS,
    LYRICS,
    PLAYER_GLASS,
    PANELS,
    FONT,
    VISUALIZER,
    MAPS,
    LINKS,
    CRT,
}

/** The app's colours: a named theme and its paper, or the listener's own two. */
@Serializable
data class ThemeColours(
    val theme: String = "monochrome_dark",
    /** "crisp" or "warm": what the light themes are printed on. */
    val paper: String = "crisp",
    /** True: [accent] and [background] replace the named theme. */
    val custom: Boolean = false,
    val accent: Int = 0,
    val background: Int = 0,
) {
    /** Known values only, and opaque colours: a see-through ground has nothing under it. */
    fun cleaned(knownThemes: Set<String>?): ThemeColours = copy(
        theme = if (knownThemes == null || theme in knownThemes) theme else "monochrome_dark",
        paper = if (paper == "warm") "warm" else "crisp",
        accent = accent or OPAQUE,
        background = background or OPAQUE,
    )

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
    }
}

/** The player's visualizers: the spectrum waterfall, Wave Candy and the ambient MilkDrop layer. */
@Serializable
data class ThemeVisualizer(
    val waterfall: SpectrumWaterfallSettings? = null,
    val waveCandy: WaveCandySettings? = null,
    /** The cover's 3D spectrogram, without the listener's own quality and preview switch. */
    val spectrogram3d: Spectrogram3DSettings? = null,
    val ambientOn: Boolean? = null,
    val ambientOpacity: Int? = null,
    val ambientBlackPoint: Int? = null,
    /** A [tf.monochrome.android.visualizer.VisualizerBlendMode] name. */
    val ambientBlend: String? = null,
    val ambientHideCover: Boolean? = null,
)

/** The look of the two map screens: the globe and the genre galaxy. */
@Serializable
data class ThemeMaps(
    val globe: GlobeFxSettings? = null,
    val galaxy: GalaxyVisualSettings? = null,
)

/**
 * A theme pack: some or all of the app's look, as one shareable document.
 *
 * Every part is the app's own settings blob, so a pack holds exactly what the
 * Studio and Settings already save, and nothing a pack does can reach further
 * than a slider can. It never holds anything personal: [ThemePackCodec.capture]
 * strips the lyric font file, the Bluetooth delay and the quality knobs before
 * a pack leaves the device, and applying one keeps the listener's own (see
 * `withPersonalFrom`). A pack carries no pictures either; its previews are
 * drawn from these settings by the real renderers.
 */
/**
 * The CRT effect: its look, its sliders, and whether it draws over the whole
 * app or the lyrics only. Each as the app stores it, so a pack carries exactly
 * what Settings › Appearance › CRT effect shows; the look "off" is the effect
 * switched off.
 */
@Serializable
data class ThemeCrt(
    /** [GlyphTvShader.id]: `crt`, `royale`, … or `off`. */
    val look: String? = null,
    /** The sliders as [GlyphTvTuning.encode] writes them, the line a `.crt.json` carries. */
    val tuning: String? = null,
    /** `app` or `lyrics`: where the effect draws. */
    val target: String? = null,
) {
    /** A look this build has (else off), the sliders held to their ranges, and one of the two targets. */
    fun cleaned(): ThemeCrt = ThemeCrt(
        look = look?.let { GlyphTvShader.fromId(it).id },
        tuning = tuning?.let { GlyphTvTuning.decode(it).clamped().encode() },
        target = target?.let { if (it == TARGET_LYRICS) TARGET_LYRICS else TARGET_APP },
    )

    companion object {
        const val TARGET_APP = "app"
        const val TARGET_LYRICS = "lyrics"
    }
}

@Serializable
data class ThemePack(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String = "",
    val colours: ThemeColours? = null,
    val lyrics: LyricsFxSettings? = null,
    val playerGlass: PlayerGlassSettings? = null,
    /** The UI panels' glass: the mini player, the tab bar and every floating pane. */
    val panelGlass: PlayerGlassSettings? = null,
    /** A bundled font's id (`asset:fonts/…`), or "" for the app's own Inter. Imported fonts never travel. */
    val font: String? = null,
    val visualizer: ThemeVisualizer? = null,
    val maps: ThemeMaps? = null,
    /** The visual music links. */
    val links: List<VisualLink>? = null,
    /** The CRT effect, over the whole app or the lyrics. */
    val crt: ThemeCrt? = null,
) {
    /** The parts this pack actually holds. */
    val parts: Set<ThemePart>
        get() = buildSet {
            if (colours != null) add(ThemePart.COLOURS)
            if (lyrics != null) add(ThemePart.LYRICS)
            if (playerGlass != null) add(ThemePart.PLAYER_GLASS)
            if (panelGlass != null) add(ThemePart.PANELS)
            if (font != null) add(ThemePart.FONT)
            if (visualizer != null) add(ThemePart.VISUALIZER)
            if (maps != null) add(ThemePart.MAPS)
            if (links != null) add(ThemePart.LINKS)
            if (crt != null) add(ThemePart.CRT)
        }

    /** This pack with only [keep] left in it. */
    fun only(keep: Set<ThemePart>): ThemePack = copy(
        colours = colours.takeIf { ThemePart.COLOURS in keep },
        lyrics = lyrics.takeIf { ThemePart.LYRICS in keep },
        playerGlass = playerGlass.takeIf { ThemePart.PLAYER_GLASS in keep },
        panelGlass = panelGlass.takeIf { ThemePart.PANELS in keep },
        font = font.takeIf { ThemePart.FONT in keep },
        visualizer = visualizer.takeIf { ThemePart.VISUALIZER in keep },
        maps = maps.takeIf { ThemePart.MAPS in keep },
        links = links.takeIf { ThemePart.LINKS in keep },
        crt = crt.takeIf { ThemePart.CRT in keep },
    )

    /**
     * Every part inside its own contract: the blobs through their own
     * `clamped()`, the links through the link codec, the font only if it is
     * one the app ships, the name trimmed. What a decoded pack always goes
     * through, so a hand-made file is held to what a slider allows.
     */
    fun cleaned(knownThemes: Set<String>? = null, isBundledFont: (String) -> Boolean = { true }): ThemePack = copy(
        format = FORMAT,
        name = name.trim().take(MAX_NAME),
        colours = colours?.cleaned(knownThemes),
        lyrics = lyrics?.clamped(),
        playerGlass = playerGlass?.clamped(),
        panelGlass = panelGlass?.clamped(),
        font = font?.let { if (it.isEmpty() || isBundledFont(it)) it else null },
        visualizer = visualizer?.copy(
            waterfall = visualizer.waterfall?.clamped(),
            waveCandy = visualizer.waveCandy?.clamped(),
            spectrogram3d = visualizer.spectrogram3d?.clamped(),
            ambientOpacity = visualizer.ambientOpacity?.coerceIn(0, 100),
            ambientBlackPoint = visualizer.ambientBlackPoint?.coerceIn(0, MAX_AMBIENT_BLACK_POINT),
        ),
        maps = maps?.copy(globe = maps.globe?.clamped(), galaxy = maps.galaxy?.clamped()),
        links = links?.let { VisualLinkCodec.sanitize(it) },
        crt = crt?.cleaned(),
    )

    companion object {
        const val FORMAT = "tryptheme"
        const val VERSION = 1
        const val MAX_NAME = 40

        /** [tf.monochrome.android.visualizer.AmbientVisualizerSettings.MAX_BLACK_POINT]. */
        const val MAX_AMBIENT_BLACK_POINT = 25
    }
}

/** A theme pack as text: what the store keeps, and what a share code would hold. */
object ThemePackCodec {
    /** The most a pack may weigh. A whole look is a few kilobytes; this leaves room and stops abuse. */
    const val MAX_BYTES = 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        // Omitted fields take their defaults on the way in, so a pack only
        // has to say what differs and stays small on the wire.
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(pack: ThemePack): String = json.encodeToString(pack)

    /**
     * Reads a pack, or null when [text] is not one: too big, not JSON, or
     * another format. A newer version is read as far as this one understands
     * it, and the result is always [ThemePack.cleaned].
     */
    fun decode(
        text: String?,
        knownThemes: Set<String>? = null,
        isBundledFont: (String) -> Boolean = { true },
    ): ThemePack? {
        if (text.isNullOrBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val pack = runCatching { json.decodeFromString<ThemePack>(text) }.getOrNull() ?: return null
        if (pack.format != ThemePack.FORMAT) return null
        return pack.cleaned(knownThemes, isBundledFont)
    }

    /**
     * What may leave the device: [pack] with every personal setting put back
     * to the default — the lyric font file and its path, the Bluetooth delay,
     * the quality and anti-aliasing knobs, the glass tint and preview colours
     * the listener picked for themselves. The receiving side keeps its own
     * anyway; this is so they are never published at all.
     */
    fun forSharing(pack: ThemePack): ThemePack = pack.copy(
        lyrics = pack.lyrics?.withPersonalFrom(LyricsFxSettings.DEFAULT),
        playerGlass = pack.playerGlass?.withPersonalFrom(PlayerGlassSettings.DEFAULT),
        panelGlass = pack.panelGlass?.withPersonalFrom(PlayerGlassSettings.DEFAULT),
        visualizer = pack.visualizer?.let { v ->
            v.copy(spectrogram3d = v.spectrogram3d?.withPersonalFrom(Spectrogram3DSettings.DEFAULT))
        },
    )
}
