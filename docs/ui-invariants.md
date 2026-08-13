# UI invariants — do not revert

Guidance for anyone (human or agent) changing this codebase's interface. None of
this is style preference: every rule is a bug that was found the hard way,
usually from a screenshot, and reverting one brings the bug back.

The build these describe is tagged `ui-baseline-2026-08-13`, so the accepted
state can be diffed against rather than argued about.

These describe the current, accepted look. If a change makes one of them false,
the change is wrong even if it compiles and the tests pass.

### Glass

The app has one glass material, built in three layers that must all be present
for it to read as glass:

1. **Haze** — a real gaussian blur of the backdrop. This is what you see
   *through* the pane. Only meaningful with a haze source that the pane is a
   *sibling* of, never a descendant: a haze effect cannot sample a layer it is
   drawn inside, and doing so paints the source's flat base colour instead of a
   blur. That is the "solid slab" failure.
2. **Frost** — the tint over the blur. Black at 0.32 on dark themes, white at
   0.45 on light. `GlassPanel` and the mini player use the same numbers on
   purpose; they are the same material and are usually on screen together.
3. **The shader slab** — a rounded rect drawn at **full tint opacity**, relit by
   the AGSL `playerGlass` modifier.

**The slab must be solid.** The shader builds its bevel and rim from the alpha
heightfield of what is drawn beneath it. A near-transparent fill leaves it
almost nothing to bevel and produces a soft smudge with a blown-out highlight
and no edge — which is what made search bars look nothing like the mini player.
The reason it was ever faint: on a device where the shader silently no-ops
(glass off, low-performance override, below API 33, or a driver that will not
compile it), a solid fill is left on screen as an opaque rounded rectangle. Ask
`rememberLiquidGlassAvailable()` rather than hedging with a low alpha.

**Do not put a haze pane under a punched glass slab** (the transport disc, the
action dock, the mini player). Those are drawn solid and made see-through by the
shader's body opacity, and what shows through is whatever is composited
underneath — an opaque frosted pane there gives them an opaque backdrop and they
come out as flat slabs with the artwork nowhere in them. Haze belongs under a
sheet that slides *over* a page, like the audio-tools sheet.

Search bars and other app chrome take **the mini player's** settings
(`LocalMiniPlayerGlass`), not the player's. The player route overrides
`LocalPlayerGlass` with its own material for the transport, which is right there
and wrong everywhere else. `GlassPanel` publishes whatever settings it was handed
as `LocalPlayerGlass` for its own shader, so its `glass` parameter is the whole
material — frost and shader both.

### Search bars

Every search bar in the app is `SearchOverlay` + `GlassSearchBar`. There is one
behaviour and it is not negotiable:

- The bar **floats over** the content. It is never a row in the screen's Column.
  Laid out inline it pushes everything down and the list then clips at its own
  new top edge, so rows vanish at a hard line an inch short of the glass.
- Opening it **hides nothing**. The bar's measured height is handed to the
  content, which passes it to the scrolling container as **`contentPadding`** —
  not padding on the container, and not a Spacer. Content padding starts the rows
  below the glass while leaving them free to travel up behind it, which is the
  whole point: nothing covered at rest, rows sliding under the glass as soon as
  you scroll.
- The height is measured **on the bar itself**, not the `AnimatedVisibility`
  around it — that animates the container's height, so measuring there reports a
  value climbing from zero and the inset chases it.
- Fixed chrome the bar must never cover: Settings' tab rail sits *above* the bar
  (it says which of nine tabs you are on, and searching is exactly when you are
  about to be moved between them). Discover's genre rail lives on the page, not
  inside the bar's pane — a pane four rows deep covers the feed it filters.
- Settings' form runs full height under the bar, with the inset going to each
  tab's own `LazyColumn` via `LocalSettingsSearchInset`. Pushing the form down
  instead leaves an empty strip behind the glass, and glass with nothing behind
  it paints its own base colour: the rectangle.

### Press feedback

Clickable glass swells under the finger — the shader's dome, following the press
position, not a flat scale. `GlassPress` / `Modifier.glassSqueeze` /
`PressableGlass` carry it. The dome's radius is a fraction of the pane's longest
side for a pane that *is* a button; the shader's own default (a sixth of the
width) is for picking one icon out of a row, like the transport and dock.

List rows keep the quieter scale squeeze; a full dome on a wide text row reads
heavy.

### Themes

Light variants are **generated**, never hand-written, and every foreground is
floored for WCAG AA against the surface it actually sits on. Custom colours
(accent + background, overriding the preset) use the same machinery in both
directions, choosing the ink direction **per surface** — a mid-tone ground can
carry neither pure black nor pure white everywhere, and their crossover is at
~4.58, so a single global direction cannot clear 4.5. The chosen background is
used verbatim; legibility is bought by moving foregrounds.

`LightSchemesTest` and `CustomSchemeTest` assert this. They are the guarantee,
not a formality — do not loosen a threshold to make one pass.

### The globe

Land rings are clipped to the visible hemisphere by cutting each ring into its
genuinely-visible runs and rejoining them along the rim, walking **against** the
ring's winding. The old shortcut — pushing every far-side vertex radially onto
the rim in ring order — drags a rim trace most of the way round the disc for a
ring with a large hidden portion, and an extra loop around the disc is an extra
crossing for every point inside it, so an even-odd fill inverts: sea filled,
continents punched out. It looked like the theme flickering. `GlobeLandClipTest`
sweeps 840 cameras and includes a test that reverses the arc direction and
asserts the sea floods, so the fix cannot be undone by a sign.

### Discord presence

The animated asset must stay under **~248 KB** or Discord's media proxy shows
the first frame and never animates — which reads as "the animation is broken".
`PresenceArtwork` fits itself to that budget by stepping quality down and then
dropping frames. Do not raise the canvas size or frame count without re-checking
the assembled size.

Local (`content://` / `file://`) artwork has no URL Discord can fetch, so it is
uploaded as an attachment and referenced by the media-proxy path — the same route
the animation takes. This needs the upload channel configured.

## Build and test

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

`assembleDebug` needs git submodules (`third_party/projectm`, `libusb`) checked
out; without them it fails for reasons unrelated to any change here.

## Commits

Author as `tryptz`. No co-author trailers, no tool attribution in commit
messages, PR bodies or code comments.
