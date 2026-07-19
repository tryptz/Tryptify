---
name: player-visuals-themes
description: >-
  Author, tweak, or study Tryptify's Player Visuals themes — the Lyrics FX and
  Player Glass parametric presets shown as chips in the Player Visuals Studio.
  Use whenever the user wants to add, design, remix, or fix a lyrics/player
  "theme", "preset", "skin", or visual style, wants a new look for the now-playing
  screen, or asks what a Lyrics FX / Player Glass parameter does or what its range
  is. Covers every tunable field, its range and visual effect, the hard rules that
  keep the unit tests green, and a repeatable recipe for coordinated, in-range,
  visually distinct themes.
---

# Tryptify Player Visuals themes

Tryptify's "themes" for the now-playing screen are **two independent parametric
preset systems**, both surfaced as chips in the **Player Visuals Studio**
(Settings → *Now Playing Appearance*). They carry **geometry and optics only —
never colour or font** (colours are derived from album art at runtime).

| System | Data class | File | What it styles |
|---|---|---|---|
| **Lyrics FX** | `LyricsFxSettings` | `app/src/main/java/tf/monochrome/android/domain/model/LyricsFxSettings.kt` | Lyric typography, the 3D per-letter wave, the bass beat engine, the reactive glow |
| **Player Glass** | `PlayerGlassSettings` | `app/src/main/java/tf/monochrome/android/domain/model/PlayerGlassSettings.kt` | The refractive glass on the transport buttons + the progress "thermometer" |

A **theme** is a coordinated pair: add one `LyricsFxSettings` preset **and** one
`PlayerGlassSettings` preset **with the same name** so the lyrics and the chrome
read as one look. The two lists are separate, so the same name may appear in both.

Each system exposes a `companion object` `PRESETS: List<Pair<String, …Settings>>`.
**Appending a `Pair` to that list is all it takes to add a chip** — the Studio
renders the list with `FilterChip { … }.forEach` (LyricsFxStudioScreen.kt), so no
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

## Hard rules (these keep the build + tests green)

1. **Stay in range.** Every value must sit inside the `clamped()` bounds (tables
   below). The test `presets are all valid after clamping` asserts
   `preset == preset.clamped()` for every entry — an out-of-range value is
   silently coerced and fails that test.
2. **Never set personal fields in a preset.** They are carried from the user's
   current settings by `withPersonalFrom`, so setting them is pointless and breaks
   `matchesPreset`.
   - Lyrics FX personal: `customFont`, `customFontPath`, `bluetoothDelayMs`,
     `glassSampleRings`, `fxaa`, `fxaaStrength`, `glowBehindArt`.
   - Player Glass personal: `sampleRings`, `tintColor`, `previewBg`.
3. **Set every field you want to differ from `DEFAULT`.** `matchesPreset` compares
   the full non-personal field set with `==`; any field you omit inherits the
   data-class default (listed below), which may not be the look you intend.
4. **Mind the two gates** (they cut whole subsystems off):
   - Lyrics `rotationDegrees ≤ 0.05` → the entire per-letter 3D path is off, so
     `waveSpeed` / `wavePhaseStep` / `waveTravelDp` / `shadowDepth` do nothing.
   - Lyrics `bassReact ≤ 0.01` → the analyzer is off, so `pumpAmount` / `attackMs`
     / `releaseMs` / `bounce` / `popAmount` / `glowRadiusDp` / `glowBrightness` do
     nothing.
5. **Don't rename or remove these preset names — tests look them up by name:**
   Lyrics FX `Voltage`; Player Glass `Neon`, `Chrome`, `Frosted`. **Appending new
   names is safe** — no test pins preset count or order.
6. **Keep names unique (case-insensitive) and give each a distinct value set** —
   two presets with identical values light two chips at once.
7. **Lyrics glass is only 4 knobs.** For the *lyrics* shader only
   `glassBodyOpacity` / `glassRefraction` / `glassRimBrightness` / `glassDispersion`
   are wired; the richer glass uniforms (`gloss`, `reflection`, `surfaceMotion`,
   `tiltReactivity`, `lightAngleDeg`, `frost`, `roundness`, `depth`, `edgeWidth`)
   exist **only** on `PlayerGlassSettings` and only affect the player buttons +
   progress tube.
8. **Player Glass shadow fields are Compose, not shader.** `shadowDepth`,
   `shadowSoftness`, `shadowTint` drive the drawn drop shadow under the play disc /
   skip glyphs — you won't see them by tuning the shader.
9. `tiltReactivity`, `surfaceMotion` drift and `lightAngleDeg`'s tilt-sway only
   render on a **physical device** with a motion sensor; in a preview/emulator the
   tilt term is ~0 (the static light placement still shows).

## Where to add a preset

- Lyrics FX: append inside `PRESETS = listOf(…)` in `LyricsFxSettings.kt` (after
  the `"Static"` / *Studio Pack* entries).
- Player Glass: append inside `PRESETS = listOf(…)` in `PlayerGlassSettings.kt`
  (after `"Mirror"` / *Studio Pack* entries).

## Lyrics FX parameter reference

Personal fields are omitted here — never set them in a preset.

| Field | Range | Default | Visual effect |
|---|---|---|---|
| `fontSizeSp` | 14..34 | 23 | Base type size; the **ceiling** the width-fitter shrinks from. Bigger ⇒ larger but more per-line shrink on long lines. |
| `letterSpacingSp` | -1..1 | -0.2 | Tracking. Negative = condensed/dense; positive = airy/editorial. |
| `edgeMarginDp` | 0..48 | 0 | Side inset (added to a fixed bevel-safe pad). Larger narrows the column ⇒ earlier shrink / more centred. |
| `maxWrapLines` | 1..3 | 3 | Rows a line may wrap before shrinking. **1 = strict single-line ticker.** |
| `liquidGlass` | bool | true | Master glass relight. **Off = flat solid text** and the 4 `glass*` fields become no-ops. |
| `glassBodyOpacity` | 0.2..1 | 0.62 | Letter face alpha. Low = see-through ghost text; high = solid. |
| `glassRefraction` | 0..0.4 | 0.14 | How hard beveled edges lens the backdrop. 0.4 = thick warped glass. |
| `glassRimBrightness` | 0..2 | 1 | Specular edge glint brightness. |
| `glassDispersion` | 0..2 | 1 | Chromatic fringing at edges. 2 = rainbow prism. |
| `rotationDegrees` | 0..25 | 12 | Per-letter 3D tilt amplitude **and gate** (≤0.05 = no wave). |
| `waveSpeed` | 0.25..3 | 1 | Wave temporal rate. |
| `wavePhaseStep` | 0.05..0.9 | 0.22 | Phase advance per letter. Low = smooth ribbon; high = choppy/glitchy. |
| `waveTravelDp` | 0..8 | 3 | Vertical bob amplitude of each letter. |
| `shadowDepth` | 0..1 | 0.7 | 3D block extrusion / contact-shadow depth. 0 = flat, 1 = chunky. |
| `bassReact` | 0..1 | 0.8 | Master reactive intensity **and gate** (≤0.01 = no pump/pop/glow). |
| `pumpAmount` | 0..0.25 | 0.08 | Active-line swell on a kick. |
| `attackMs` | 4..60 | 12 | Pulse attack — how fast it snaps onto a kick. Low = snappy, high = soft swell. |
| `releaseMs` | 40..500 | 150 | Pulse release — how long it holds. Low = staccato, high = sustained/drone. |
| `bounce` | 0..1 | 0.7 | Spring damping. 0 = stiff/mechanical, 1 = rubbery overshoot. |
| `popAmount` | 0..0.2 | 0.08 | Size swing when a new line activates. |
| `glowRadiusDp` | 0..160 | 44 | Reactive bloom radius behind the active line. 160 = supernova. |
| `glowBrightness` | 0..0.6 | 0.22 | Bloom peak alpha. |

## Player Glass parameter reference

Personal fields (`sampleRings`, `tintColor`, `previewBg`) omitted — leave at
default (2 / 0 / 0).

| Field | Range | Default | Visual effect |
|---|---|---|---|
| `enabled` | bool | true | Master button-glass toggle. **False = flat buttons.** |
| `bodyOpacity` | 0.2..1 | 0.5 | Glass body see-through amount. Low = ghost/invisible-ink. |
| `refraction` | 0..0.4 | 0.16 | Bevel lensing of the backdrop. 0.4 + high depth/dispersion = diamond. |
| `rimBrightness` | 0..2 | 1.3 | Lit specular rim brightness. |
| `dispersion` | 0..2 | 1.2 | Chromatic aberration at edges. |
| `roundness` | 0.5..2 | 1 | Bevel shoulder width. Higher = rounder/softer; low + high depth = faceted gem. |
| `depth` | 0.5..2 | 1 | Bevel relief steepness. |
| `shadowDepth` | 0..1 | 0.45 | Drop-shadow darkness (Compose). 1 = deeply floated/levitating. |
| `reflection` | 0..2 | 1 | Room/environment reflection strength. 2 = mirror. |
| `gloss` | 0..1 | 0.4 | Highlight polish. 0 = soft frosted-wide, 1 = tight mirror. |
| `surfaceMotion` | 0..1 | 0.25 | Living-liquid undulation. 1 + frost = molten/lava-lamp. |
| `tiltReactivity` | 0..1.5 | 0.7 | Device-tilt light sway. 0 = locked studio, 1.5 = gyro/holo. |
| `lightAngleDeg` | 0..360 | 135 | Key-light direction. 25 = raking sunset, 90 = top, 270 = underlit. |
| `edgeWidth` | 0..1 | 0.4 | Reflective shoulder width. Wide + low gloss = soap. |
| `frost` | 0..1 | 0 | Frosted roughness. 0.9–1 = sea-glass/etched. |
| `shadowSoftness` | 0..1 | 0.4 | Drop-shadow blur/spread. |
| `shadowTint` | 0..1 | 0 | 0 = neutral black … 1 = accent-tinted glow halo. |
| `progressGlass` | bool | true | Glass thermometer scrubber vs plain slider. |

## Recipe for a new coordinated theme

1. **Pick a vibe** and a **distinct region** of the space. Skim the existing
   presets in both files; pick params they cluster on and push them somewhere new
   (a tight/wide tracking, a choppy/ultra-smooth wave, a ghost or opaque body,
   glass off, a single-line ticker, an unusual `lightAngleDeg`, a tinted shadow…).
2. **Author the Lyrics FX preset** — set every field you want to differ from
   DEFAULT; respect the gates; keep in range.
3. **Author the Player Glass preset** with the **same name** and a matching mood.
4. Append both to their `PRESETS` lists.
5. **Validate ranges + colour-safety** with the helper (no Gradle needed):
   ```bash
   python3 .claude/skills/player-visuals-themes/scripts/validate_theme_ranges.py
   ```
   It parses both `PRESETS` lists and flags any out-of-range value, any preset that
   sets `tintColor`/`previewBg`, and any duplicate name.
6. Confirm names are unique and each preset's values differ from its siblings.
7. **Build caveat:** this environment can't run Gradle (the Gradle 9 host is
   blocked and only 8.14.3 is installed while AGP needs 9). Verify the look on a
   physical device — tilt/motion/light-angle fields are invisible in previews.

## Adding a brand-new FIELD (not just a preset)

Much heavier — a multi-file change:
1. Add the field + default to the data class.
2. Add its clamp line in `clamped()` (finite fallback + `coerceIn`).
3. Decide **aesthetic vs personal**; if personal, add it to `withPersonalFrom`.
4. Wire it into the renderer (`LyricsHero.kt` / `LyricsBassFx.kt`) or the shader
   uniform push in `ui/player/LiquidGlass.kt` for the glass path.
5. Add a Studio control in `LyricsFxStudioScreen.kt` so it's user-tunable.
6. Extend persistence/sync if needed and update the `*SettingsTest.kt` (they assert
   clamp bounds, round-trip, `matchesPreset`, `withPersonalFrom`).

Shareable codes (`TRYPTFX1:` / `TRYPTGLASS1:`) re-clamp on encode and decode with a
tolerant codec (`ignoreUnknownKeys`, `encodeDefaults`), so old codes stay importable
when you add fields — but never rely on a code carrying an out-of-range signature
value; it's clamped on import.
