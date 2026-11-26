---
name: player-visuals-themes
description: >-
  Author, tweak, or study Tryptify's Player Visuals themes — the Lyrics FX and
  Player Glass parametric presets shown as chips in the Player Visuals Studio.
  Use whenever the user wants to add, design, remix, or fix a lyrics/player
  "theme", "preset", "skin", or visual style, wants a new look for the now-playing
  screen, or asks what a Lyrics FX / Player Glass parameter does or what its range
  is, including the lyric god rays, the lyric curve and the letter-glass optics. Covers every
  tunable field, its range and visual effect, the hard rules that
  keep the unit tests green, and a repeatable recipe for coordinated, in-range,
  visually distinct themes.
---

# Tryptify Player Visuals themes

Tryptify's "themes" for the now-playing screen are **two independent parametric
preset systems**, both surfaced as chips in the **Player Visuals Studio**
(Settings › the *Visual Studio* chip, which opens it directly, or from the
player). The Studio has four tabs: *Player* and *UI panels* (both Player
Glass), *Lyrics* (Lyrics FX) and *Visualizer* (not a preset system). Presets
carry **geometry and optics only — never colour or font** (colours are derived
from album art at runtime).

| System | Data class | File | What it styles |
|---|---|---|---|
| **Lyrics FX** | `LyricsFxSettings` | `app/src/main/java/tf/monochrome/android/domain/model/LyricsFxSettings.kt` | Lyric typography, the liquid-glass letters, the 3D per-letter wave, the bass beat engine, the reactive glow, the god rays |
| **Player Glass** | `PlayerGlassSettings` | `app/src/main/java/tf/monochrome/android/domain/model/PlayerGlassSettings.kt` | Refractive glass, kept as two copies: the *Player* tab's (play disc, chevrons, action dock, the progress "thermometer") and the *UI panels* tab's (mini player, tab bar, every panel and pill). Both tabs offer the same `PRESETS`. |

The two rosters are **independent**. Player Glass opens with six themes
tuned on device (Float, the starting look, first), then an iOS Liquid Glass
roster (Clear, Tinted, Tilt) and creative extensions; its names
no longer mirror the Lyrics FX list, and a glass theme does not need a lyric
twin. If you do want a coordinated look, give the two the same name — the lists
are separate, so the same name may appear in both.

Each system exposes a `companion object` `PRESETS: List<Pair<String, …Settings>>`.
**Appending a `Pair` to that list is all it takes to add a chip** — the Studio
renders the list with `PRESETS.forEach { GlassChoiceChip(…) }`
(LyricsFxStudioScreen.kt; the chip is `ui/mixer/GlassChoiceChip.kt`), so no
UI edit is needed. Chip order follows list order; the selected chip lights via
`matchesPreset`.

## The one thing you cannot theme

Colours and fonts are **not** part of a preset. Player/lyric colours come from the
album's extracted palette (`albumColors.vibrant/dominant`) at runtime. The only
user colour pins are `PlayerGlassSettings.tintColor` / `previewBg`, and those are
**personal fields a preset must leave at 0**. The lyric glow colour has no knob at
all — it is always the album accent. Do not add a colour/font to a preset; a unit
test enforces `tintColor == 0 && previewBg == 0` for every preset, and
`withPersonalFrom` strips personal fields on apply.

## Not in a preset: the CRT effect and music links

- **The CRT effect is not a Lyrics FX field.** The Studio's Lyrics tab ends
  with its controls, but they are Settings › Appearance › CRT effect's own
  preferences (`appTvShader`, `appTvTuning`, and whether it draws over the
  whole app or the lyrics only), with its own presets (`glyph/engine/CrtPreset.kt`,
  `ui/settings/crt/`). A Lyrics FX preset cannot set or change it. While the
  CRT effect bows the whole app, the lyrics' own curve is set aside
  (`appTubeCurved()`), so a curve tuned then looks flat; the Studio says so
  under the curve sliders.
- **Music links are not in a preset.** A link (`VisualLinkTargets`, saved by
  `MusicLinksRepository`) moves a field's drawn value with the music each
  frame and never its saved value, so a preset needs nothing for it and a
  chip still lights while a link runs. A Theme Store pack can carry links
  beside the settings, but that is the pack, not `PRESETS`.

## Hard rules (these keep the build + tests green)

1. **Stay in range.** Every value must sit inside the `clamped()` bounds (tables
   below). The test `presets are all valid after clamping` asserts
   `preset == preset.clamped()` for every entry — an out-of-range value is
   silently coerced and fails that test.
2. **Never set personal fields in a preset.** They are carried from the user's
   current settings by `withPersonalFrom`, so setting them is pointless and breaks
   `matchesPreset`.
   - Lyrics FX personal: `customFont`, `customFontPath`, `bluetoothDelayMs`,
     `glassSampleRings`, `fxaa`, `fxaaStrength`, `glowBehindArt`,
     `artGlowRadiusDp`, `artGlowBrightness`, `godRayQuality`.
   - Player Glass personal: `sampleRings`, `tintColor`, `previewBg`,
     `miniProgressBar`. The last one is a preference about the bar, not part of
     a glass theme's optics — three unit tests pin that a theme cannot switch it
     back on.
3. **Set every field you want to differ from `DEFAULT`.** `matchesPreset` compares
   the full non-personal field set with `==`; any field you omit inherits the
   data-class default (listed below), which may not be the look you intend.
4. **Mind the gates** (they cut whole subsystems off):
   - Lyrics `rotationDegrees ≤ 0.05` → the entire per-letter 3D path is off, so
     `waveSpeed` / `wavePhaseStep` / `waveTravelDp` do nothing. (`shadowDepth`
     no longer depends on it: the shadow is its own layer under every line.)
   - Lyrics `bassReact ≤ 0.01` → the analyzer is off, so `pumpAmount` / `attackMs`
     / `releaseMs` / `bounce` / `glowRadiusDp` / `glowBrightness` do nothing —
     and neither does `godRayBeat`, which rides the same pulse.
   - Lyrics curve: both angles under 0.05° (`curvatureDeg`, and
     `effectiveCurvatureSideDeg`, which is `curvatureDeg` while
     `curvatureSideDeg` is -1) → no curve layer, and `curvatureTarget` does
     nothing (`curved`). Android 13+ only, and set aside while the CRT effect
     bows the whole app.
   - Lyrics `godRays = false` → every other `godRay*` field does nothing.
     Within the rays, `godRaySunSize` only matters with `godRaySource = 1`
     (backlight), and `godRaysFollowWord` only with word-timed (karaoke)
     lyrics; line-timed lyrics shine from the whole line either way.
     `godRaysAllLyrics` overrides `godRaysFollowWord`.
5. **Don't rename or remove these preset names — tests look them up by name:**
   Lyrics FX `Voltage`, `Sunburst`, `Cathedral`, `Eclipse`, `Daybreak`,
   `Searchlight`, `Spotlight`, `Crepuscular` (pinned to the Shadertoy's
   numbers) and `Mercury`; Player Glass `Float` (must stay
   first and be `INITIAL`) and `Tinted` and `Tilt`. **Appending new names is
   safe** — no test pins the total. But never insert a Lyrics FX preset
   *before* `Sunburst`: the test `god rays are off by default and every
   earlier preset is untouched` takes the 17 presets ahead of it and asserts
   none of them sets a letter-glass optic or a god-ray field.
   No Lyrics FX preset may curve the lyrics: `the lyrics are flat unless
   curved, and the curve stays in range` asserts every preset leaves
   `curvatureDeg` at 0. Leave `curvatureSideDeg` and `curvatureTarget` at
   their defaults too, since the sides' angle alone also bends them.
   Player Glass presets must also keep `frost` light, at most 0.15 (`no preset
   uses more than a light frost grain`): frosted glass is made from
   `hazeBlurDp`, and heavy shader grain read as noise on the rim. Blurred
   (0.03) and Frosted Ripple (0.11) are the only themes that use it.
6. **Keep names unique (case-insensitive) and give each a distinct value set** —
   two presets with identical values light two chips at once.
7. **Lyrics glass has the full Player Glass optics, under `glass*` names.**
   `glassRoundness`, `glassDepth`, `glassSurfaceMotion`, `glassReflection`,
   `glassGloss`, `glassTiltReactivity`, `glassLightAngleDeg`, `glassEdgeWidth`
   and `glassFrost` feed the same uniforms, mapped the same way, as
   `PlayerGlassSettings`' `roundness` … `frost` (gloss → 20 + 240·g, edge →
   Fresnel 8 − 6·e). Their defaults are the values the lyric shader used to
   pin by hand, so an upgrade moves no pixel. What the letters do *not* get:
   the drop shadow, the backdrop haze and the lens rim, which belong to panes —
   a glyph has no rounded rect to lens. Lyric `glassBodyOpacity` is floored at
   0.2 (the glass one at 0), because a letter has to stay readable. The Studio's
   *Match player glass* copies the optics across (`withGlassOpticsFrom`), never
   the body opacity.
8. **Player Glass shadow fields are Compose, not shader.** `shadowDepth`,
   `shadowSoftness`, `shadowTint` drive the drawn drop shadow under the play disc /
   skip glyphs — you won't see them by tuning the shader.
9. `tiltReactivity`, `surfaceMotion` drift and `lightAngleDeg`'s tilt-sway only
   render on a **physical device** with a motion sensor; in a preview/emulator the
   tilt term is ~0 (the static light placement still shows).

## Where to add a preset

- Lyrics FX: append at the end of `PRESETS = listOf(…)` in
  `LyricsFxSettings.kt`, after `"Sea Glass"` (27 presets today; `"Static"`
  closes the first 17, and nothing goes between it and `"Sunburst"`).
- Player Glass: append after `"Holo"` inside `PRESETS = listOf(…)` in
  `PlayerGlassSettings.kt` (19 today: Float, Opal, Ripple, Glint, Blurred,
  Frosted Ripple, Clear, Tinted, Tilt, Pure, Droplet, Prism, Bubble, Mercury,
  Ice, Halo, Aurora, Dusk, Holo). It opens with `"Float" to FLOAT` and holds
  `"Clear" to CLEAR`, references the validator cannot parse, so it counts 17
  and leaves Float and Clear to the unit tests.
- `FLOAT` is also `INITIAL`, what every glass setting starts from — the
  player's and the mini player's — and what the Studio's reset restores.
  Changing its values restyles the app for everyone who has not customised
  it; the test `every glass setting starts from Float` pins that it stays
  still (no surface motion, no tilt). `DEFAULT` keeps the classic values but is
  no longer a chip; it stays because every preset inherits omitted fields from
  it — so set **every** material field in a new glass preset.
- Motion and tilt cost battery on the app-wide mini player (`surfaceMotion` > 0
  runs a frame clock, `tiltReactivity` > 0 holds the gravity sensor). Keep both
  at 0 unless the theme is *about* moving.

## Lyrics FX parameter reference

Personal fields are omitted here — never set them in a preset.

| Field | Range | Default | Visual effect |
|---|---|---|---|
| `fontSizeSp` | 14..64 | 23 | Base type size; the **ceiling** the width-fitter shrinks from. Bigger ⇒ larger but more per-line shrink on long lines. |
| `letterSpacingSp` | -1..1 | -0.2 | Tracking. Negative = condensed/dense; positive = airy/editorial. |
| `edgeMarginDp` | 0..48 | 0 | Side inset of the text (added to a fixed bevel-safe pad). Larger narrows the column ⇒ earlier shrink / more centred. It moves the text only: the glass and the other layers stay the surface's full width, so letters moving past the text's edge are not cut. |
| `maxWrapLines` | 1..3 | 3 | Rows a line may wrap before shrinking. **1 = strict single-line ticker.** |
| `liquidGlass` | bool | true | Master glass relight. **Off = flat solid text** and every `glass*` field below becomes a no-op. |
| `glassBodyOpacity` | 0.2..1 | 0.62 | Letter face alpha. Low = see-through ghost text; high = solid. |
| `glassRefraction` | 0..0.4 | 0.14 | How hard beveled edges lens the backdrop. 0.4 = thick warped glass. |
| `glassRimBrightness` | 0..2 | 1 | Specular edge glint brightness. |
| `glassDispersion` | 0..2 | 1 | Chromatic fringing at edges. 2 = rainbow prism. |
| `glassRoundness` | 0.5..2 | 1 | Bevel shoulder width on each letter. High = round pillowy strokes; low + high depth = faceted. |
| `glassDepth` | 0.5..2 | 1 | Relief steepness. 2 = letters pop hard in 3D. |
| `glassSurfaceMotion` | 0..1 | 1 | Living-liquid shimmer on the letters. **0 = still glass and no frame clock for the glass.** |
| `glassReflection` | 0..2 | 1 | Room reflection on the letters. 2 = liquid metal. |
| `glassGloss` | 0..1 | 0.29166666 | Highlight polish: 0 = soft wide glint, 1 = tight mirror. The default is the old fixed exponent of 90. |
| `glassTiltReactivity` | 0..1.5 | 0.7 | How far device tilt moves the letters' light. **0 releases the gravity sensor.** Device only. |
| `glassLightAngleDeg` | 0..360 | 135 | Key-light direction on the letters (90 = top). |
| `glassEdgeWidth` | 0..1 | 0.5 | Reflective rim width: 0 = hairline, 1 = broad glassy shoulder. |
| `glassFrost` | 0..1 | 0 | Shader grain on the letters: 0.7+ = sea glass. |
| `glassRayCatch` | 0..1 | 0.7 | How much the glass letters catch the god rays' light: lit bevels, a glint and a lit face toward the source; with a backlight, glowing rims. Only while `godRays` is on. |
| `rotationDegrees` | 0..25 | 12 | Per-letter 3D tilt amplitude **and gate** (≤0.05 = no wave). |
| `waveSpeed` | 0.25..3 | 1 | Wave temporal rate. It adds to the wave's own clock each frame (`rememberLetterWaveSeconds`), so a change speeds the wave up from where it is; music-linkable (`lyrics.waveSpeed`). |
| `wavePhaseStep` | 0.05..0.9 | 0.22 | Phase advance per letter. Low = smooth ribbon; high = choppy/glitchy. |
| `waveTravelDp` | 0..8 | 3 | Vertical bob amplitude of each letter. |
| `shadowDepth` | 0..1 | 0.7 | Soft shadow cast on the background under every lyric line, falling away from the light (the rays' light when they are on, else the glass key light). 0 = none, 1 = darkest and softest. Not on the letters: it is its own layer outside the glass. |
| `curvatureDeg` | 0..75 | 0 | The lyrics on a curved tube, as a CRT draws them: how far the glass bows toward the top and bottom edges, in degrees (the sides' are `curvatureSideDeg`). The bow is the CRT shader's warp, `1/cos θ − 1`: about 16° (1/24) is the classic tube's top and bottom, 14.1° (1/32) its sides. The middle stays as it is, lines bow outward and draw in toward the corners, which round off: never a fish-eye. Set to 0.1° with the Studio's buttons. 0 with the sides unset = flat and no layer. Android 13+. Bends inside the letters' copy, so their shadow and rays bend too. |
| `curvatureSideDeg` | -1..75 | -1 | The same tube's bow toward the left and right edges, in degrees (about 14° is the classic tube); `curvatureDeg` is then the top and bottom's. -1 = unset: drawn at `curvatureDeg`, so a curve saved with one angle keeps its shape (`effectiveCurvatureSideDeg`). |
| `curvatureTarget` | 0..2 | 0 | What the curve bends: 0 = all the lyrics as one tube (`CURVE_ALL`), 1 = a tube over the sung line alone (`CURVE_LINE`), 2 = over the sung word alone (`CURVE_WORD`; word-timed lyrics, the line otherwise). Everything else stays flat, and so do the tube's own rounded-off corners. Only while the lyrics are curved (either angle ≥ 0.05°). |
| `bassReact` | 0..1 | 0.8 | Master reactive intensity **and gate** (≤0.01 = no pump/pop/glow). |
| `pumpAmount` | 0..0.25 | 0.08 | Active-line swell on a kick. |
| `attackMs` | 4..60 | 12 | Pulse attack — how fast it snaps onto a kick. Low = snappy, high = soft swell. |
| `releaseMs` | 40..500 | 150 | Pulse release — how long it holds. Low = staccato, high = sustained/drone. |
| `bounce` | 0..1 | 0.7 | Spring damping. 0 = stiff/mechanical, 1 = rubbery overshoot. |
| `glowRadiusDp` | 0..160 | 44 | Reactive bloom radius behind the active line. 160 = supernova. |
| `glowBrightness` | 0..0.6 | 0.22 | Bloom peak alpha. |
| `godRays` | bool | false | Master switch for the light shafts (volumetric light scattering, GPU Gems 3 ch. 13). **Off = every `godRay*` field is a no-op.** |
| `godRaySource` | 0..1 | 0 | 0 = the sung line shines and streams light; 1 = a backlight disc behind the lyrics that the letters block, so their shadows streak through the shafts. |
| `godRaysOnTop` | bool | false | false = shafts under the letters (crisp); true = light added over them (the article's additive composite, hazier). |
| `godRaysFollowWord` | bool | false | Word-timed lyrics: only the sung word shines, hopping word to word. Line-timed lyrics keep the line. |
| `godRaysAllLyrics` | bool | false | Every lyric shines (the Shadertoy's whole image as the light). Overrides `godRaysFollowWord` and drops the sung line's legibility dimming. |
| `godRayExposure` | 0..1.5 | 0.6 | Shaft brightness (the article's exposure). |
| `godRayDensity` | 0.2..1 | 0.85 | How far toward the light each pixel gathers: the shaft length (the article's density). |
| `godRayDecay` | 0.85..1 | 0.95 | Light kept per 1/50 of a shaft (the article's decay at 50 samples). 1 = no falloff. |
| `godRayAzimuthDeg` | 0..360 | 90 | Where the light comes from around the screen: 0 = right, 90 = above, 270 = below. |
| `godRayElevationDeg` | 0..90 | 60 | The 3D angle: 90 = straight behind the line (a burst), 0 = flat and raking (near-parallel shafts). Projected as `focal · cot(el)`, focal = half the window's short side, so it lands the same in the Studio's preview as in the player. |
| `godRaySunSize` | 0.03..0.3 | 0.08 | Backlight disc radius, share of the window's short side. Backlight only. |
| `godRayShimmer` | 0..1 | 0.35 | Dust: slow flicker across neighbouring shafts. Any value > 0 runs a frame clock. |
| `godRayBeat` | 0..1 | 0.5 | How much a kick brightens and lengthens the shafts. Needs `bassReact` > 0.01. |
| `godRaySpinDps` | -45..45 | 0 | The light orbits the line, degrees per second; sign = direction. |
| `godRaySway` | 0..1 | 0 | The light wanders on the Shadertoy's own path, `(sin(t), sin(0.913·t))`, out to half the window's short side at 1. Runs a frame clock. |
| `godRayTilt` | 0..1.5 | 0 | How far tilting the phone swings the light. Device only; > 0 holds the gravity sensor. |
| `godRayFadeMs` | 0..1000 | 250 | Light transition, ms: how long the light takes to come up when a word or line starts being sung, to go out when it is over, and to glide from one sung word or line to the next. 0 = switches and jumps. The light is out whenever nothing is sung (before the first line, between words when following the word, after a word-timed line's last word). Not music-linkable. |
| `godRayFade` | bool | true | The Light transition's switch. Off, the light switches and jumps as at 0 ms and `godRayFadeMs` keeps its value. Code reads `godRayFadeTimeMs` (the time, or 0 when off), never `godRayFadeMs` directly. |
| `godRayHold` | bool | false | When a word ends: false "Fade out" (the light goes out at each word's end), true "Keep shining" (it stays on a word until the next word of the line starts, and goes out after the line's last word; following the line, a line keeps it until the next line's turn). Never lights the intro or a ♪ break. |

## Player Glass parameter reference

Personal fields (`sampleRings`, `tintColor`, `previewBg`, `miniProgressBar`)
omitted — leave at default (3 / 0 / 0 / true).

The defaults below are the *shipped* look and several of them sit at a bound —
the pinning test is `defaults reproduce the shipped glass`. Read them before
relying on hard rule 3: a field you omit inherits these, not a tidy midpoint.

| Field | Range | Default | Visual effect |
|---|---|---|---|
| `enabled` | bool | true | Master button-glass toggle. **False = flat buttons.** |
| `bodyOpacity` | 0..1 | 0.2 | Glass body see-through amount. Low = ghost/invisible-ink; 0 = body fully invisible (edges/rim remain). **The shipped default is already ghost-thin.** |
| `refraction` | 0..0.4 | 0.4 | Bevel lensing of the backdrop, and the interior slab parallax. On lens-rim panes the rim offset is in pixels, about two rim widths at 0.4. **Default is the maximum** — flat faces lens, not just bevels. |
| `rimBrightness` | 0..2 | 0.2633547 | Lit specular rim brightness. Default is dim; 2 = blazing edge. |
| `dispersion` | 0..2 | 1.8702691 | Chromatic aberration at edges. Default is heavy. |
| `roundness` | 0.5..2 | 2 | Bevel shoulder width. Higher = rounder/softer; low + high depth = faceted gem. On lens-rim panes (mini player, panels, tab bar, dock, play disc) also sets the rim band: 0.5..2 → 5/8..all of the corner radius (max 24dp). **Default is the maximum.** |
| `depth` | 0.5..2 | 1.0025804 | Bevel relief steepness. Default is neutral. |
| `shadowDepth` | 0..1 | 0.20475428 | Drop-shadow darkness (Compose). 1 = deeply floated/levitating. |
| `reflection` | 0..2 | 2 | Room/environment reflection strength. **Default is the maximum** — mirror-strong. |
| `gloss` | 0..1 | 1 | Highlight polish. 0 = soft frosted-wide, 1 = tight mirror. **Default is the maximum.** |
| `surfaceMotion` | 0..1 | 0.53 | Living-liquid undulation — face swell, edge shimmer, glint twinkle. All undulate in place; no pass travels across the pane. 1 + frost = molten/lava-lamp. |
| `tiltReactivity` | 0..1.5 | 0 | Device-tilt light sway. **Default is 0** — a locked studio light. 1.5 = gyro/holo. |
| `lightAngleDeg` | 0..360 | 215.1965 | Key-light direction. 25 = raking sunset, 90 = top, 270 = underlit. |
| `edgeWidth` | 0..1 | 0 | Reflective shoulder width. **Default is 0** — a hairline edge. Wide + low gloss = soap. |
| `frost` | 0..1 | 0 | Frosted surface roughness (shader). 0.9–1 = sea-glass/etched. Distinct from `hazeBlurDp`, which blurs the *backdrop*. |
| `shadowSoftness` | 0..1 | 0.01565171 | Drop-shadow blur/spread. Default is very tight. |
| `shadowTint` | 0..1 | 0 | 0 = neutral black … 1 = accent-tinted glow halo. |
| `progressGlass` | bool | true | Glass thermometer scrubber vs plain slider. |
| `hazeBlurDp` | 0..80 | 40 | Backdrop (Haze) frost blur radius, in dp, for surfaces that gaussian-blur what is behind them. 0 disables the frost layer entirely. Studio slider: *Backdrop blur*. |
| `hazeTint` | 0..2 | 1 | Strength multiplier on the frost layer's luminance-picked tint. Studio slider: *Backdrop tint*. |

`hazeBlurDp` / `hazeTint` reach further than the rest of this table. The app
keeps two `PlayerGlassSettings` blobs, and both blur their backdrop: the *UI
panels* copy (`mini_player_glass_json`) is the mini player bar, the tab bar,
the audio-tools sheet, the speed panel, the search bar, the map panels and the
mixer strips; the *Player* copy (`player_glass_json`) is the now-playing action
dock and play disc. A glass theme applied on the UI panels tab is restyling app
chrome, not just the now-playing screen. They are material (not personal), so
a preset may set them and a user who moves either slider stops matching every
chip.

## Recipe for a new coordinated theme

1. **Pick a vibe** and a **distinct region** of the space. Skim the existing
   presets in both files; pick params they cluster on and push them somewhere new
   (a tight/wide tracking, a choppy/ultra-smooth wave, a ghost or opaque body,
   glass off, a single-line ticker, an unusual `lightAngleDeg`, a tinted shadow…).
2. **Author the Lyrics FX preset** — set every field you want to differ from
   DEFAULT; respect the gates; keep in range.
3. **Author the Player Glass preset** — setting every material field — with the
   same name if you want the two to read as one look.
4. Append both to their `PRESETS` lists.
5. **Validate ranges + colour-safety** with the helper — a second, no-Gradle
   check, not a replacement for the tests:
   ```bash
   python3 .claude/skills/player-visuals-themes/scripts/validate_theme_ranges.py
   ```
   It reads the bounds and the personal-field set **out of the Kotlin** and flags
   any out-of-range value, any preset that sets a personal field, any field the
   data class doesn't have, and any duplicate name. Exit 2 means it could not
   read the model and checked nothing — treat that as a failure, not a pass.
6. **Run the unit tests.** They are the real gate:
   ```bash
   ./gradlew :app:testDebugUnitTest --tests '*LyricsFxSettingsTest' \
                                    --tests '*PlayerGlassSettingsTest'
   ```
   (The wrapper pins Gradle 9.1.0, which matches AGP 9 — an older note in this
   file claimed Gradle couldn't run here and it was wrong. `assembleDebug` is
   the thing that needs the git submodules; the unit tests do not.)
7. Confirm names are unique and each preset's values differ from its siblings.
8. **Verify the look on a physical device** — tilt/motion/light-angle fields are
   invisible in previews and emulators.

## Adding a brand-new FIELD (not just a preset)

Much heavier — a multi-file change:
1. Add the field + default to the data class. Default it to the look that
   ships, so every saved blob and every existing preset draws as before.
2. Add its clamp line in `clamped()` (finite fallback + `coerceIn`).
3. Decide **aesthetic vs personal**; if personal, add it to `withPersonalFrom`.
4. Wire it into the renderer (`ui/player/LyricsHero.kt`, `LyricsBassFx.kt`,
   `LyricsGodRays.kt`, `LyricsShadow.kt`, `LyricsCurvature.kt`) or the shader
   uniform push in `ui/player/LiquidGlass.kt` for the glass path.
5. Add a Studio control in `LyricsFxStudioScreen.kt` so it's user-tunable.
6. Music links: if the field is read in a draw or layer block and may move with
   the music, add a `VisualLinkTarget` in `domain/model/VisualLinkTargets.kt`
   with the clamp bounds, and give its Studio slider `linkKey = "lyrics.<field>"`
   (glass sliders use `glassKey(linkArea, "<field>")`). `VisualLinkTargetsTest`
   fails on a target with no slider and on a `linkKey` with no target. If it may
   not be linked, its reason belongs in `VisualLinkTarget`'s doc list.
7. Add its row to the table in this file. The validator fails on a tunable
   field the table leaves out, and on a wrong range or default.
8. Update the `*SettingsTest.kt` (they assert clamp bounds, round-trip,
   `matchesPreset`, `withPersonalFrom`). The settings sync and Theme Store
   packs carry the whole JSON blob, so neither needs a change for a new field.

Shareable codes (`TRYPTFX1:` / `TRYPTGLASS1:`) re-clamp on encode and decode with a
tolerant codec (`ignoreUnknownKeys`, `encodeDefaults`), so old codes stay importable
when you add fields — but never rely on a code carrying an out-of-range signature
value; it's clamped on import.
