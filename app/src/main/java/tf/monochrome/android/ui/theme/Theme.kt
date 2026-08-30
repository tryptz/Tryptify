package tf.monochrome.android.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

val MonochromeDarkScheme = darkColorScheme(
    primary = MonoWhite,
    onPrimary = MonoBlack,
    primaryContainer = MonoSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = MonoCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = MonoCard,
    onTertiaryContainer = MonoWhite,
    background = MonoBlack,
    onBackground = MonoWhite,
    surface = MonoSurface,
    onSurface = MonoWhite,
    surfaceVariant = MonoSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = MonoOutline,
    outlineVariant = MonoSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val OceanDarkScheme = darkColorScheme(
    primary = OceanPrimary,
    onPrimary = MonoBlack,
    primaryContainer = OceanSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = OceanCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = OceanCard,
    onTertiaryContainer = MonoWhite,
    background = OceanBackground,
    onBackground = MonoWhite,
    surface = OceanSurface,
    onSurface = MonoWhite,
    surfaceVariant = OceanSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = OceanOutline,
    outlineVariant = OceanSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val MidnightDarkScheme = darkColorScheme(
    primary = MidnightPrimary,
    onPrimary = MonoWhite,
    primaryContainer = MidnightSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = MidnightCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = MidnightCard,
    onTertiaryContainer = MonoWhite,
    background = MidnightBackground,
    onBackground = MonoWhite,
    surface = MidnightSurface,
    onSurface = MonoWhite,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = MidnightOutline,
    outlineVariant = MidnightSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val CrimsonDarkScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = MonoWhite,
    primaryContainer = CrimsonSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = CrimsonCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = CrimsonCard,
    onTertiaryContainer = MonoWhite,
    background = CrimsonBackground,
    onBackground = MonoWhite,
    surface = CrimsonSurface,
    onSurface = MonoWhite,
    surfaceVariant = CrimsonSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = CrimsonOutline,
    outlineVariant = CrimsonSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val ForestDarkScheme = darkColorScheme(
    primary = ForestPrimary,
    onPrimary = MonoBlack,
    primaryContainer = ForestSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = ForestCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = ForestCard,
    onTertiaryContainer = MonoWhite,
    background = ForestBackground,
    onBackground = MonoWhite,
    surface = ForestSurface,
    onSurface = MonoWhite,
    surfaceVariant = ForestSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = ForestOutline,
    outlineVariant = ForestSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val SunsetDarkScheme = darkColorScheme(
    primary = SunsetPrimary,
    onPrimary = MonoBlack,
    primaryContainer = SunsetSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = SunsetCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = SunsetCard,
    onTertiaryContainer = MonoWhite,
    background = SunsetBackground,
    onBackground = MonoWhite,
    surface = SunsetSurface,
    onSurface = MonoWhite,
    surfaceVariant = SunsetSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = SunsetOutline,
    outlineVariant = SunsetSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val CyberpunkDarkScheme = darkColorScheme(
    primary = CyberpunkPrimary,
    onPrimary = MonoWhite,
    primaryContainer = CyberpunkSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = CyberpunkCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = CyberpunkCard,
    onTertiaryContainer = MonoWhite,
    background = CyberpunkBackground,
    onBackground = MonoWhite,
    surface = CyberpunkSurface,
    onSurface = MonoWhite,
    surfaceVariant = CyberpunkSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = CyberpunkOutline,
    outlineVariant = CyberpunkSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val NordDarkScheme = darkColorScheme(
    primary = NordPrimary,
    onPrimary = MonoBlack,
    primaryContainer = NordSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = NordCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = NordCard,
    onTertiaryContainer = MonoWhite,
    background = NordBackground,
    onBackground = MonoWhite,
    surface = NordSurface,
    onSurface = MonoWhite,
    surfaceVariant = NordSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = NordOutline,
    outlineVariant = NordSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val GruvboxDarkScheme = darkColorScheme(
    primary = GruvboxPrimary,
    onPrimary = MonoBlack,
    primaryContainer = GruvboxSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = GruvboxCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = GruvboxCard,
    onTertiaryContainer = MonoWhite,
    background = GruvboxBackground,
    onBackground = MonoWhite,
    surface = GruvboxSurface,
    onSurface = MonoWhite,
    surfaceVariant = GruvboxSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = GruvboxOutline,
    outlineVariant = GruvboxSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val DraculaDarkScheme = darkColorScheme(
    primary = DraculaPrimary,
    onPrimary = MonoBlack,
    primaryContainer = DraculaSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = DraculaCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = DraculaCard,
    onTertiaryContainer = MonoWhite,
    background = DraculaBackground,
    onBackground = MonoWhite,
    surface = DraculaSurface,
    onSurface = MonoWhite,
    surfaceVariant = DraculaSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = DraculaOutline,
    outlineVariant = DraculaSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val SolarizedDarkScheme = darkColorScheme(
    primary = SolarizedPrimary,
    onPrimary = MonoBlack,
    primaryContainer = SolarizedSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = SolarizedCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = SolarizedCard,
    onTertiaryContainer = MonoWhite,
    background = SolarizedBackground,
    onBackground = MonoWhite,
    surface = SolarizedSurface,
    onSurface = MonoWhite,
    surfaceVariant = SolarizedSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = SolarizedOutline,
    outlineVariant = SolarizedSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val LavenderDarkScheme = darkColorScheme(
    primary = LavenderPrimary,
    onPrimary = MonoBlack,
    primaryContainer = LavenderSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = LavenderCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = LavenderCard,
    onTertiaryContainer = MonoWhite,
    background = LavenderBackground,
    onBackground = MonoWhite,
    surface = LavenderSurface,
    onSurface = MonoWhite,
    surfaceVariant = LavenderSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = LavenderOutline,
    outlineVariant = LavenderSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val GoldDarkScheme = darkColorScheme(
    primary = GoldPrimary,
    onPrimary = MonoBlack,
    primaryContainer = GoldSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = GoldCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = GoldCard,
    onTertiaryContainer = MonoWhite,
    background = GoldBackground,
    onBackground = MonoWhite,
    surface = GoldSurface,
    onSurface = MonoWhite,
    surfaceVariant = GoldSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = GoldOutline,
    outlineVariant = GoldSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val RosewaterDarkScheme = darkColorScheme(
    primary = RosewaterPrimary,
    onPrimary = MonoBlack,
    primaryContainer = RosewaterSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = RosewaterCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = RosewaterCard,
    onTertiaryContainer = MonoWhite,
    background = RosewaterBackground,
    onBackground = MonoWhite,
    surface = RosewaterSurface,
    onSurface = MonoWhite,
    surfaceVariant = RosewaterSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = RosewaterOutline,
    outlineVariant = RosewaterSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

val MintDarkScheme = darkColorScheme(
    primary = MintPrimary,
    onPrimary = MonoBlack,
    primaryContainer = MintSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = MintCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = MintCard,
    onTertiaryContainer = MonoWhite,
    background = MintBackground,
    onBackground = MonoWhite,
    surface = MintSurface,
    onSurface = MonoWhite,
    surfaceVariant = MintSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = MintOutline,
    outlineVariant = MintSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)

// True light theme. Secondary/tertiary use charcoal/near-black so their
// onSecondary/onTertiary = white stays at ≥7:1 contrast (previously these
// paired white text on light-gray MonoTextSecondary/Tertiary, which failed
// WCAG and rendered as ghost text). onSurfaceVariant is a darker gray so
// secondary labels on WhiteSurfaceVariant (#EBEBEB) stay readable.
val WhiteScheme = androidx.compose.material3.lightColorScheme(
    primary = WhitePrimary,
    onPrimary = MonoWhite,
    primaryContainer = WhiteSurfaceVariant,
    onPrimaryContainer = MonoBlack,
    secondary = WhiteSecondary,
    onSecondary = WhiteOnSecondary,
    secondaryContainer = WhiteCard,
    onSecondaryContainer = MonoBlack,
    tertiary = WhiteTertiary,
    onTertiary = WhiteOnSecondary,
    tertiaryContainer = WhiteCard,
    onTertiaryContainer = MonoBlack,
    background = WhiteBackground,
    onBackground = MonoBlack,
    surface = WhiteSurface,
    onSurface = MonoBlack,
    surfaceVariant = WhiteSurfaceVariant,
    onSurfaceVariant = WhiteOnSurfaceVariant,
    outline = WhiteOutline,
    outlineVariant = WhiteSurfaceVariant,
    error = ErrorRed,
    onError = MonoWhite
)

val ClearDarkScheme = darkColorScheme(
    primary = ClearPrimary,
    onPrimary = MonoBlack,
    primaryContainer = ClearSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextSecondary,
    onSecondary = MonoBlack,
    secondaryContainer = ClearCard,
    onSecondaryContainer = MonoWhite,
    tertiary = MonoTextTertiary,
    onTertiary = MonoBlack,
    tertiaryContainer = ClearCard,
    onTertiaryContainer = MonoWhite,
    background = ClearBackground,
    onBackground = MonoWhite,
    surface = ClearSurface,
    onSurface = MonoWhite,
    surfaceVariant = ClearSurfaceVariant,
    onSurfaceVariant = MonoTextSecondary,
    outline = ClearOutline,
    outlineVariant = ClearSurfaceVariant,
    error = ErrorRed,
    onError = MonoBlack
)
/**
 * The OS's own palette — what the system settings call "System colors".
 *
 * Not a palette this app owns: it is read back from the platform, which derives
 * it from the wallpaper (or from a featured colour the listener picked) and
 * adjusts it for the Contrast accessibility setting. Kept as a named constant
 * because three places need to agree on the spelling and a typo in any of them
 * fails silently as "theme not found" rather than as a build error.
 */
const val MATERIAL_YOU_KEY = "material_you"

/** Display names for theme selection UI */
val themeDisplayNames = mapOf(
    "system" to "System",
    MATERIAL_YOU_KEY to "System colors",
    "monochrome_dark" to "Monochrome",
    "ocean" to "Ocean",
    "midnight" to "Midnight",
    "crimson" to "Crimson",
    "forest" to "Forest",
    "sunset" to "Sunset",
    "cyberpunk" to "Cyberpunk",
    "nord" to "Nord",
    "gruvbox" to "Gruvbox",
    "dracula" to "Dracula",
    "solarized" to "Solarized",
    "lavender" to "Lavender",
    "gold" to "Gold",
    "rosewater" to "Rosewater",
    "mint" to "Mint",
    "white" to "White",
    "clear" to "Clear"
) + ThemeAccents.keys.associate { base ->
    // Every theme gets a light variant, named and listed alongside the dark
    // one it is derived from. Generated rather than typed out so adding a
    // theme adds its light twin automatically instead of leaving a gap
    // somebody notices six months later.
    lightVariantOf(base) to "${baseDisplayName(base)} Light"
}

/**
 * The themes this device can actually honour, for the picker to list.
 *
 * [themeDisplayNames] is the full catalogue and stays that way — the Settings
 * subtitle looks a stored key up in it, and a device that cannot *offer* the
 * system palette must still be able to *name* it if the preference arrived from
 * a backup or from a newer Android on the same account.
 *
 * What is filtered here is only what may be chosen. Below Android 12 there is no
 * system palette to read, and [rememberMaterialYouScheme] returns null so
 * [MonochromeTheme] falls through to Monochrome. Offering a name that silently
 * resolves to a different theme is worse than not offering it: the listener
 * picks "System colors", gets Monochrome, and has no way to tell whether the
 * setting failed or their wallpaper is simply grey.
 *
 * [sdkInt] is a parameter rather than a direct read so this stays a pure
 * function the JVM unit tests can drive at both sides of the boundary; the app
 * always calls it with the default.
 */
fun selectableThemes(sdkInt: Int = Build.VERSION.SDK_INT): Map<String, String> =
    if (sdkInt >= Build.VERSION_CODES.S) themeDisplayNames
    else themeDisplayNames - MATERIAL_YOU_KEY

private fun baseDisplayName(base: String): String = when (base) {
    "monochrome" -> "Monochrome"
    else -> base.replaceFirstChar { it.uppercase() }
}

fun getColorScheme(themeName: String, paper: Paper = Paper.Crisp): ColorScheme {
    // Light variants are built, not listed: "<theme>_light" for any theme the
    // app knows an accent for. Fifteen hand-written light palettes is fifteen
    // chances to pick a grey that fails on its own card, which is exactly how
    // the one that existed went wrong.
    lightVariantBase(themeName)?.let { base ->
        ThemeAccents[base]?.let { return lightSchemeFor(it, paper) }
    }
    return darkSchemeFor(themeName)
}

private fun darkSchemeFor(themeName: String) = when (themeName) {
    "ocean" -> OceanDarkScheme
    "midnight" -> MidnightDarkScheme
    "crimson" -> CrimsonDarkScheme
    "forest" -> ForestDarkScheme
    "sunset" -> SunsetDarkScheme
    "cyberpunk" -> CyberpunkDarkScheme
    "nord" -> NordDarkScheme
    "gruvbox" -> GruvboxDarkScheme
    "dracula" -> DraculaDarkScheme
    "solarized" -> SolarizedDarkScheme
    "lavender" -> LavenderDarkScheme
    "gold" -> GoldDarkScheme
    "rosewater" -> RosewaterDarkScheme
    "mint" -> MintDarkScheme
    // The original hand-built light theme, kept as its own entry so nobody who
    // chose it wakes up on a generated one. "monochrome_light" is the built
    // equivalent and is what "system" now reaches for.
    "white" -> WhiteScheme
    "clear" -> ClearDarkScheme
    else -> MonochromeDarkScheme
}

/**
 * One colour from the palette the scheme is about to be built from, used only as
 * a cache key.
 *
 * Which resource is the right one to watch depends on which builder will run.
 * From API 34 the scheme comes from the Material 3 *role* resources, and those
 * are the only ones the Contrast accessibility setting moves — the older
 * `system_accent1_*` ramp is left untouched by a contrast change, so seeding
 * from the ramp there would read a palette that never appears to change while
 * the whole system repaints around it. Below 34 the ramp is all there is.
 *
 * Annotated rather than guarded again internally: the ramp does not exist below
 * Android 12 and reading it there would throw, so the caller's early return is
 * the real precondition and this states it where the compiler can check it.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun systemPaletteSeed(context: Context, dark: Boolean): Int {
    val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (dark) android.R.color.system_primary_dark else android.R.color.system_primary_light
    } else {
        android.R.color.system_accent1_500
    }
    return context.resources.getColor(id, context.theme)
}

/**
 * The OS's own palette, as a scheme. Null below Android 12, where no such
 * palette exists, so callers can fall back to a built-in theme.
 *
 * Deliberately a thin call into Material 3 rather than a hand-rolled read of
 * `android.R.color.system_*`. From API 34 `dynamicDarkColorScheme` already
 * dispatches to a builder that reads those role resources directly, which is
 * what makes this match the rest of the system exactly — featured colours and
 * the Contrast setting included — instead of re-deriving a palette from the
 * older accent ramps. Reimplementing that mapping here would duplicate thirty-odd
 * slots the library already gets right and would go stale the next time the
 * platform adds a role.
 *
 * The [remember] is not an optimisation to taste. This sits at the theme root,
 * which recomposes continuously for the whole colour cross-fade window every
 * time the track changes (see `ColorBlend.millisFor` in MainActivity), and
 * building a scheme allocates every slot; without it this churned one full
 * ColorScheme per frame of every transition.
 *
 * Keyed on a colour read out of the palette rather than on the context, because
 * the context is not reliably a new one when the palette moves. Changing System
 * colors swaps a resource overlay, which normally recreates the activity — but
 * this activity declares `uiMode` in its own `configChanges` and so survives a
 * light/dark switch, and nothing obliges an OEM skin to deliver a contrast or
 * palette change any differently. Re-reading one colour per recomposition costs
 * a cached resource lookup and makes the cache self-invalidating whichever way
 * the change arrives.
 */
@Composable
fun rememberMaterialYouScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    val seed = systemPaletteSeed(context, dark)
    return remember(context, dark, seed) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
}

/**
 * A pair of ARGB colours the listener picked, for the custom-theme override.
 * Held as ints so it is a stable, equals-comparable key — a [Color]-based one
 * would still work, but this is what the store hands back.
 */
data class CustomThemeColors(val accent: Int, val background: Int)

@Composable
fun MonochromeTheme(
    themeName: String = "monochrome_dark",
    fontScale: Float = 1.0f,
    customFontFamily: FontFamily? = null,
    dynamicPalette: DynamicPalette? = null,
    paper: Paper = Paper.Crisp,
    /**
     * The listener's own two colours. When [customColors] is set it wins over
     * the named theme, the paper and Material You alike — it is the deliberate
     * override the "Custom colors" switch turns on, so nothing else gets to
     * quietly decide the scheme out from under it.
     */
    customColors: CustomThemeColors? = null,
    /**
     * "Tint the menus": let the album's colours reach the app-wide scheme, not
     * just the surfaces that opt into [DynamicColorScope]. Ignored when there is
     * no [dynamicPalette] to read.
     */
    dynamicMenus: Boolean = false,
    /**
     * The bypass for the half of [dynamicMenus] people most often do not want:
     * the accent still follows the cover, the ground stays where the theme put
     * it. Only consulted while [dynamicMenus] is on.
     */
    dynamicMenusKeepBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    // "system" follows the OS dark-mode toggle. In light mode it now reaches
    // for the *light variant of the chosen theme* rather than a single shared
    // white one, so following the system no longer means losing the theme.
    val resolvedTheme = if (themeName == "system") {
        if (systemDark) "monochrome_dark" else lightVariantOf("monochrome")
    } else themeName
    val materialYou = if (resolvedTheme == MATERIAL_YOU_KEY && customColors == null) {
        rememberMaterialYouScheme(dark = systemDark)
    } else null
    val chosen = when {
        customColors != null ->
            customScheme(Color(customColors.accent), Color(customColors.background))
        materialYou != null -> materialYou
        else -> getColorScheme(resolvedTheme, paper)
    }
    val colorScheme = tintedByAlbum(
        base = chosen,
        palette = dynamicPalette.takeIf { dynamicMenus },
        keepBackground = dynamicMenusKeepBackground,
    )
    val family = customFontFamily ?: InterFontFamily
    val typography = remember(fontScale, family) {
        buildTypography(family, fontScale)
    }

    // By default the album palette is NOT overlaid on the global scheme — doing
    // so bleeds the album accent into the menus. It is published via
    // [LocalDynamicColorPalette] and only the player, mini player and lyrics opt
    // in through [DynamicColorScope]; every other surface keeps the chosen
    // theme. "Tint the menus" is the listener asking for that bleed on purpose,
    // and [tintedByAlbum] above is the only thing that grants it.
    CompositionLocalProvider(LocalDynamicColorPalette provides dynamicPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

/**
 * How far the app's ground travels toward the cover's dominant colour.
 *
 * Not all the way, and this is the whole reason the app-wide tint is usable at
 * all. Handing [customScheme] a raw album colour lets every bright cover flip
 * the entire app to a light scheme and every dark one flip it back, so a queue
 * strobes the menus between light and dark from track to track. A quarter of
 * the way keeps the hue plainly visible while the theme goes on deciding
 * whether this is a light app or a dark one.
 *
 * The accent takes no such wash — an accent is meant to be loud, and
 * [customScheme] already floors it for contrast wherever it lands.
 */
private const val ALBUM_GROUND_MIX = 0.25f

/**
 * The theme's ground carried a quarter of the way toward the cover's.
 *
 * Pulled out of [tintedByAlbum] so `AlbumTintTest` can hold it to the promise
 * the constant makes: whatever is playing, the tinted ground must stay on the
 * same side of the light/dark line as the theme's own, so the menus never flip
 * polarity between two tracks.
 */
internal fun albumTintedGround(base: Color, album: Color): Color =
    lerp(base, album, ALBUM_GROUND_MIX)

/**
 * The app-wide scheme rebuilt around the album, for "Tint the menus".
 *
 * Rebuilt rather than patched: swapping the accent slots the way
 * [DynamicColorScope] does works on a player whose foregrounds are hardcoded
 * white, and falls apart across the menus, where a cover's accent lands on
 * surfaces the base theme derived from a different colour entirely and can end
 * up under 4.5:1 on any of them. [customScheme] takes an accent and a ground and
 * derives every other slot from them with the contrast floors applied, which is
 * exactly this job — the same machinery the listener's own two colours use.
 *
 * A passthrough when the tint is off, when nothing is playing, or when the cover
 * yielded no palette.
 */
@Composable
private fun tintedByAlbum(
    base: ColorScheme,
    palette: DynamicPalette?,
    keepBackground: Boolean,
): ColorScheme {
    if (palette == null) return base
    return remember(base, palette, keepBackground) {
        customScheme(
            accent = palette.primary,
            background = if (keepBackground) base.background
            else albumTintedGround(base.background, palette.background),
        )
    }
}

/**
 * The album-art-derived palette for the currently playing track, or null when
 * Dynamic Colours is off / nothing is playing / extraction failed. Published by
 * [MonochromeTheme] and consumed by [DynamicColorScope]. Kept out of the global
 * MaterialTheme on purpose so the menus never pick up the album accent.
 */
val LocalDynamicColorPalette = compositionLocalOf<DynamicPalette?> { null }

/**
 * What colour a sheet of glass should be tinted.
 *
 * A tint the listener set by hand wins outright — they picked it, and nothing
 * else is entitled to overrule them. Otherwise the current scheme's primary.
 *
 * Deliberately *not* the album palette directly. Reading it here made every
 * pane in the app drift with what was playing, menus and search bars included,
 * which is the bleed the theme goes out of its way to prevent. The album
 * reaches the glass the same way it reaches everything else — by the screen
 * opting into [DynamicColorScope], where the scheme's own primary becomes the
 * album's. That keeps it to the places where following the music is the point:
 * the player, and the world radio globe.
 */
@Composable
fun glassTint(explicitArgb: Int): Color {
    if (explicitArgb != 0) return Color(explicitArgb)
    return MaterialTheme.colorScheme.primary
}

/**
 * Overlays the album-art dynamic palette ([LocalDynamicColorPalette]) onto the
 * current colour scheme for [content] only — swapping the same primary/secondary
 * slots the app-wide theme used to. Wrap the player, mini player and lyrics in
 * this so they follow the album art while the rest of the UI (the menus) stays
 * on the user's chosen theme. A transparent passthrough when no palette is set.
 */
@Composable
fun DynamicColorScope(content: @Composable () -> Unit) {
    val palette = LocalDynamicColorPalette.current
    if (palette == null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, palette) {
        base.copy(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            secondary = palette.secondary,
            onSecondary = palette.onSecondary,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
