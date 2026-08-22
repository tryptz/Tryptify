# Tryptify StepTech Glyph asset pack

An original, dependency-free SVG system for Tryptify's StepMania **Glyph** and
**Training Ground** modes. The artwork combines an 8 px technical grid with
rounded arcade shapes, strong silhouettes, and restrained UI chrome.

## Pack contents

| Pack | Files | Native viewBox | Purpose |
|---|---:|---:|---|
| `noteskin/taps` | 36 | `64 × 64` | Four directions across eight beat subdivisions, plus default aliases |
| `noteskin/receptors` | 8 | `64 × 64` | Resting and active receptors for all four lanes |
| `noteskin/holds` | 12 | `64 × 64` | Hold/roll heads, seamless bodies, and tails |
| `noteskin/special` | 4 | `64 × 64` | Mine, lift, fake, and shock notes |
| `ui/icons` | 33 | `24 × 24` | Training, transport, analysis, navigation, and accessibility controls |
| `ui/decor` | 6 | varies | Grid, panel brackets, timeline, divider, focus, and graph decoration |
| `feedback/judgements` | 6 | `320 × 64` | Marvelous through Miss wordmarks in a font-free 5×7 grid alphabet |
| `feedback/grades` | 8 | `96 × 96` | SSS, SS, S, A, B, C, D, and Failed badges |
| `feedback/effects` | 4 | `128 × 128` | Tap, hold, miss, and combo feedback overlays |

There are **117 production SVGs**. The three `preview_*.svg` files are contact
sheets and are not listed in `manifest.json`.

## Beat palette

The tap colors encode rhythmic subdivision instead of lane, so direction is
still communicated by shape when color perception is limited.

| Subdivision | Color |
|---|---|
| 4th | `#FF5F6D` |
| 8th | `#58D9FF` |
| 12th | `#A77BFF` |
| 16th | `#FFD95A` |
| 24th | `#FF74C8` |
| 32nd | `#FF9659` |
| 48th | `#52E6D8` |
| 64th | `#63F2A2` |

## Runtime rules

- Treat the SVGs as vector masters; never upscale a cached bitmap.
- Snap gameplay art to whole physical pixels after lane scaling.
- Render receptors above the lanes and note explosions above receptors.
- Tile hold/roll bodies vertically without spacing; the body edges are seamless.
- `ui/icons` and `ui/decor` use `currentColor`. Replace or tint it with the
  current Material color when converting to Compose or Android VectorDrawable.
- Notes and feedback contain their own semantic colors and should not receive a
  global tint.
- Preserve the SVG `viewBox` when exporting. Do not crop the intentional effect
  padding.
- Keep touch targets at least 48 dp even though the icon artwork is 24 dp.

Android does not decode SVG resources by itself, and this project does not
currently include Coil's SVG decoder. Convert the selected masters to Compose
paths/Android VectorDrawable during implementation, or add a deliberate SVG
decoder dependency rather than silently rasterizing them.

## Manifest

`manifest.json` provides stable relative paths, dimensions, tintability, and the
full palette. Gameplay code should resolve semantic assets through the manifest
or an equivalent typed mapping instead of constructing filenames ad hoc.

## Regeneration

Run from the repository root:

```bash
python3 tools/generate_stepmania_glyph_assets.py
```

The generator is deterministic. All production artwork is original to
Tryptify and contains no copied StepMania noteskin graphics.
