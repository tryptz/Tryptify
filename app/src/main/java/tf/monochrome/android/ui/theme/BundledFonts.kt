package tf.monochrome.android.ui.theme

/**
 * A typeface that ships inside the APK, as opposed to one the user imported.
 *
 * Bundled fonts are addressed by an `asset:` id rather than a filesystem path,
 * which is what keeps them distinguishable from imports everywhere the active
 * font is stored or compared — they live in the APK, so they can't be deleted
 * and there is no file to point at.
 */
data class BundledFont(
    val assetPath: String,
    val displayName: String,
    /** One line on why this one is here, shown under the name in the picker. */
    val note: String,
)

/**
 * The fonts Tryptify ships with.
 *
 * All ten are variable fonts under the SIL Open Font Licence, taken unmodified
 * from github.com/google/fonts; the full licence text travels with them in
 * `assets/fonts/LICENSES.txt`. Variable means one file covers the whole weight
 * range, so the set costs about 2.3 MB rather than the ~10 MB the same coverage
 * would take as static cuts.
 *
 * The selection leans technical rather than decorative, because this is a
 * hi-fi player: most of these are grotesques and geometric sans that hold up at
 * list sizes and in the player's glass chrome. Fraunces and JetBrains Mono are
 * the two deliberate outliers — one serif, one monospace — for people who want
 * the interface to read as something other than another sans-serif app.
 */
object BundledFonts {

    /** Marks a stored font id as pointing into assets rather than at a file. */
    const val ASSET_PREFIX = "asset:"

    val ALL: List<BundledFont> = listOf(
        BundledFont(
            "fonts/space_grotesk.ttf",
            "Space Grotesk",
            "Technical grotesque with odd, memorable letterforms — reads like equipment.",
        ),
        BundledFont(
            "fonts/instrument_sans.ttf",
            "Instrument Sans",
            "Contemporary grotesque, tight and neutral. Closest in feel to the default.",
        ),
        BundledFont(
            "fonts/chivo.ttf",
            "Chivo",
            "Sturdy grotesque with more contrast than Inter — good at small sizes.",
        ),
        BundledFont(
            "fonts/manrope.ttf",
            "Manrope",
            "Semi-rounded and warm. Softens the interface without losing precision.",
        ),
        BundledFont(
            "fonts/outfit.ttf",
            "Outfit",
            "Geometric and even-widthed. Clean under the player's glass.",
        ),
        BundledFont(
            "fonts/sora.ttf",
            "Sora",
            "Geometric with a squarer skeleton — quietly futuristic.",
        ),
        BundledFont(
            "fonts/ibm_plex_sans.ttf",
            "IBM Plex Sans",
            "Engineered rather than styled. Suits the mixer and the measurement screens.",
        ),
        BundledFont(
            "fonts/bricolage_grotesque.ttf",
            "Bricolage Grotesque",
            "The expressive one. Lots of character, best if you like a loud interface.",
        ),
        BundledFont(
            "fonts/fraunces.ttf",
            "Fraunces",
            "A soft, wonky serif. The one real departure from a sans-serif app.",
        ),
        BundledFont(
            "fonts/jetbrains_mono.ttf",
            "JetBrains Mono",
            "Monospace throughout. Every number lines up; titles get a terminal look.",
        ),
    )

    fun isBundled(fontId: String?): Boolean =
        fontId != null && fontId.startsWith(ASSET_PREFIX)

    /** The `assets/` path inside a bundled font's id. */
    fun assetPathOf(fontId: String): String = fontId.removePrefix(ASSET_PREFIX)

    /** The stored id for [font]. */
    fun idOf(font: BundledFont): String = ASSET_PREFIX + font.assetPath

    fun byId(fontId: String?): BundledFont? {
        if (!isBundled(fontId)) return null
        val path = assetPathOf(fontId!!)
        return ALL.firstOrNull { it.assetPath == path }
    }

    /**
     * Human-readable name for whatever is selected — a bundled font's proper
     * name, an imported file's stem, or null for the built-in default.
     */
    fun displayNameOf(fontId: String?): String? = when {
        fontId.isNullOrBlank() -> null
        isBundled(fontId) -> byId(fontId)?.displayName
        else -> fontId.substringAfterLast('/').substringBeforeLast('.')
    }
}
