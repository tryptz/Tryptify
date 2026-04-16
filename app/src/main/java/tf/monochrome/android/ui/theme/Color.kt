package tf.monochrome.android.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome Dark (Default)
val MonoBlack = Color(0xFF000000)
val MonoSurface = Color(0xFF111111)
val MonoSurfaceVariant = Color(0xFF1A1A1A)
val MonoCard = Color(0xFF1E1E1E)
val MonoOutline = Color(0xFF333333)
val MonoWhite = Color(0xFFFFFFFF)
val MonoTextSecondary = Color(0xFFB0B0B0)
val MonoTextTertiary = Color(0xFF808080)
val MonoAccent = Color(0xFFFFFFFF)

// Ocean Theme
val OceanPrimary = Color(0xFF00D4FF)
val OceanBackground = Color(0xFF0A1929)
val OceanSurface = Color(0xFF132F4C)
val OceanSurfaceVariant = Color(0xFF0F2640)
val OceanCard = Color(0xFF173A5E)
val OceanOutline = Color(0xFF1E4976)

// Midnight Theme
val MidnightPrimary = Color(0xFF7C4DFF)
val MidnightBackground = Color(0xFF0D0D1A)
val MidnightSurface = Color(0xFF1A1A2E)
val MidnightSurfaceVariant = Color(0xFF151528)
val MidnightCard = Color(0xFF1F1F38)
val MidnightOutline = Color(0xFF2E2E52)

// Crimson Theme
val CrimsonPrimary = Color(0xFFFF4444)
val CrimsonBackground = Color(0xFF1A0A0A)
val CrimsonSurface = Color(0xFF2E1A1A)
val CrimsonSurfaceVariant = Color(0xFF261414)
val CrimsonCard = Color(0xFF381E1E)
val CrimsonOutline = Color(0xFF4A2828)

// Forest Theme
val ForestPrimary = Color(0xFF00E676)
val ForestBackground = Color(0xFF0A1A0D)
val ForestSurface = Color(0xFF1A2E1E)
val ForestSurfaceVariant = Color(0xFF142618)
val ForestCard = Color(0xFF1E3824)
val ForestOutline = Color(0xFF284A30)

// Sunset Theme
val SunsetPrimary = Color(0xFFFF9800)
val SunsetBackground = Color(0xFF1F1209)
val SunsetSurface = Color(0xFF332015)
val SunsetSurfaceVariant = Color(0xFF2A1910)
val SunsetCard = Color(0xFF402A1E)
val SunsetOutline = Color(0xFF5A3C2A)

// Cyberpunk Theme
val CyberpunkPrimary = Color(0xFFFF003C)
val CyberpunkBackground = Color(0xFF0F0F1A)
val CyberpunkSurface = Color(0xFF1E1E2E)
val CyberpunkSurfaceVariant = Color(0xFF181826)
val CyberpunkCard = Color(0xFF28283E)
val CyberpunkOutline = Color(0xFF383854)

// Nord Theme
val NordPrimary = Color(0xFF88C0D0)
val NordBackground = Color(0xFF2E3440)
val NordSurface = Color(0xFF3B4252)
val NordSurfaceVariant = Color(0xFF353B4A)
val NordCard = Color(0xFF434C5E)
val NordOutline = Color(0xFF4C566A)

// Gruvbox Theme
val GruvboxPrimary = Color(0xFFFABD2F)
val GruvboxBackground = Color(0xFF282828)
val GruvboxSurface = Color(0xFF3C3836)
val GruvboxSurfaceVariant = Color(0xFF32302F)
val GruvboxCard = Color(0xFF504945)
val GruvboxOutline = Color(0xFF665C54)

// Dracula Theme
val DraculaPrimary = Color(0xFFFF79C6)
val DraculaBackground = Color(0xFF282A36)
val DraculaSurface = Color(0xFF44475A)
val DraculaSurfaceVariant = Color(0xFF363948)
val DraculaCard = Color(0xFF525569)
val DraculaOutline = Color(0xFF6272A4)

// Solarized Theme
val SolarizedPrimary = Color(0xFF2AA198)
val SolarizedBackground = Color(0xFF002B36)
val SolarizedSurface = Color(0xFF073642)
val SolarizedSurfaceVariant = Color(0xFF04323D)
val SolarizedCard = Color(0xFF094452)
val SolarizedOutline = Color(0xFF586E75)

// Lavender Theme
val LavenderPrimary = Color(0xFFB4A1FF)
val LavenderBackground = Color(0xFF171324)
val LavenderSurface = Color(0xFF241E38)
val LavenderSurfaceVariant = Color(0xFF1E192E)
val LavenderCard = Color(0xFF30284A)
val LavenderOutline = Color(0xFF423863)

// Gold Theme
val GoldPrimary = Color(0xFFFFD700)
val GoldBackground = Color(0xFF0D0D0D)
val GoldSurface = Color(0xFF1A1A1A)
val GoldSurfaceVariant = Color(0xFF141414)
val GoldCard = Color(0xFF222222)
val GoldOutline = Color(0xFF333333)

// Rosewater Theme
val RosewaterPrimary = Color(0xFFF2D5CF)
val RosewaterBackground = Color(0xFF241F23)
val RosewaterSurface = Color(0xFF382F34)
val RosewaterSurfaceVariant = Color(0xFF30282C)
val RosewaterCard = Color(0xFF4A3E45)
val RosewaterOutline = Color(0xFF5E4F59)

// Mint Theme
val MintPrimary = Color(0xFF69FFB4)
val MintBackground = Color(0xFF0C1410)
val MintSurface = Color(0xFF16241D)
val MintSurfaceVariant = Color(0xFF111C16)
val MintCard = Color(0xFF1E3328)
val MintOutline = Color(0xFF284736)

// White Theme
// True light theme — dark text on light surfaces. All secondary/tertiary
// tokens must pair with dark `on*` colors; reusing the dark-theme
// MonoTextSecondary/MonoTextTertiary with white `on*` is a WCAG violation.
val WhitePrimary = Color(0xFF000000)
val WhiteBackground = Color(0xFFFFFFFF)
val WhiteSurface = Color(0xFFF5F5F5)
val WhiteSurfaceVariant = Color(0xFFEBEBEB)
val WhiteCard = Color(0xFFFAFAFA)
val WhiteOutline = Color(0xFFE0E0E0)
// Light-theme accents: desaturated slate primary, charcoal secondary,
// near-black tertiary — all contrast ≥4.5:1 against WhiteOnSecondary.
val WhiteSecondary = Color(0xFF424242)       // charcoal
val WhiteTertiary = Color(0xFF1F1F1F)        // near-black
val WhiteOnSecondary = Color(0xFFFFFFFF)     // white text on charcoal
val WhiteOnSurfaceVariant = Color(0xFF4A4A4A) // readable secondary text on #EBEBEB

// Clear Theme
// Clear is intentionally translucent so surfaces let the background (album
// art, visualizer, etc.) bleed through. ClearSurface used to be fully
// transparent (0x00000000) which made Material cards invisible — any
// composable painting onto colorScheme.surface would render nothing.
// 0x40000000 keeps the smoky look while guaranteeing ≥25% opacity so
// surface bounds stay legible against bright backdrops.
val ClearPrimary = Color(0xFFFFFFFF)
val ClearBackground = Color(0xFF000000)
val ClearSurface = Color(0x40000000)
val ClearSurfaceVariant = Color(0x15FFFFFF)
val ClearCard = Color(0x0AFFFFFF)
val ClearOutline = Color(0x22FFFFFF)

// Shared
val ErrorRed = Color(0xFFCF6679)
val ExplicitBadge = Color(0xFF666666)
