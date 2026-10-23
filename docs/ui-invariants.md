# UI invariants — do not revert

Guidance for anyone (human or agent) changing this codebase's interface. None of
this is style preference: every rule is a bug that was found the hard way,
usually from a screenshot, and reverting one brings the bug back.

The build these describe is commit `281c07a2` (1.9.4: Trypt Mania, music links,
the CRT effect, Material 3 lists and the typography table), so the accepted state
can be diffed against rather than argued about:

```
git diff 281c07a2 -- app/src/main/java/tf/monochrome/android/ui
```

Until 1.9.4 the reference was `5ec5b072` ("Match the search bars to the mini
player, and undo the player haze"); everything accepted since is written into the
sections below.

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
2. **Frost** — the tint over the blur, `playerFrostTint`: black at 0.32 over a
   dark ground, white at 0.26 over a light one, times the glass's Backdrop tint
   (`hazeTint`, 0 on the Float preset). Every haze pane frosts through it
   (`Modifier.glassFrost`), so `GlassPanel`, the mini player and the player's
   sheets are the same material; they are usually on screen together. The
   light side was 0.45, nearly twice the dark one, and buried the blur under
   most of a solid colour: milky slabs on a light theme.
3. **The shader slab** — a rounded rect drawn at **full tint opacity**, relit by
   the AGSL `playerGlass` modifier.

Where the live lens runs (below), it takes the place of the first two layers:
it draws the real backdrop, blurred and bent, with no frost veil.

**The slab must be solid.** The shader builds its bevel and rim from the alpha
heightfield of what is drawn beneath it. A near-transparent fill leaves it
almost nothing to bevel and produces a soft smudge with a blown-out highlight
and no edge — which is what made search bars look nothing like the mini player.
The reason it was ever faint: on a device where the shader silently no-ops
(glass off, low-performance override, below API 33, or a driver that will not
compile it), a solid fill is left on screen as an opaque rounded rectangle. Ask
`rememberLiquidGlassAvailable()` rather than hedging with a low alpha. A pane
asks it of its own glass, `rememberLiquidGlassAvailable(glass)`: the plain call
reads `LocalPlayerGlass`, the screen's, which is another material or, where no
screen provides one, the default, so a pane with its glass on could be told the
shader would not run and draw its slab faint (`GlassMaterial`, `GlassInkScope`,
`glassPunches`).

**A rounded-rect slab passes its corner as `lensCorner`.** And the slab itself is drawn rounded at that corner, never a square `drawRect`: the player's audio sheet drew one, the shader lit its square edges, the sheet's clip cut the corners off, and its rim stopped short of every corner (`GlassSlabCornerTest` reads every `.playerGlass(` for both). The alpha
heightfield alone gives a solid fill a bevel 2–4px wide: the fill steps from 0
to 1 across one anti-aliased pixel, so everything inside it is flat and
`refract()` bends nothing. The pane then reads as a tinted sheet with a
garbled hairline, not as glass. With `lensCorner` set, the shader lays a
rounded (circular) edge across a band as wide as the corner (capped at 24dp, scaled by
`roundness`), and measures the bend in pixels against that band rather than as
a fraction of the pane, so the backdrop bends hardest at the rim and not at all
in the middle. The mini player, `GlassPanel` (so every search bar), the tab
bar, the action dock and the play disc all pass it. Leave it unspecified for
anything that is not one rounded rect filling the layer — glyphs, icons, the
spectrum — or the rim lands where the edge is not. With it unspecified, the
output is bit-identical to the alpha-only glass. The profile is circular, not a squircle: a squircle is flat for most of its
width, only its outermost pixels bent, and on device the refraction read as too
weak.

**The app has two looks: liquid glass and Material 3. There is no third.**
Glass is the shader (`GlassPanel`, `GlassMaterial`, `playerGlass`, the live
lens and `glassFrost`). Everything else stands on `Modifier.materialSurface`,
a flat fill in the caller's shape between `surfaceContainerLow` and
`surfaceContainerHighest` by the caller's `tintAlpha` (a quieter pane a tone
lower); `GlassPanel` without its shader is its own `surfaceContainerHigh`.

There used to be a third, the old frosted pane (`liquidGlass` in
`ui/components`: a black tint, a gradient rim, a refraction sheen, Haze blur
under chrome). Rows never run the shader, so every list row, card and tile
wore it whenever liquid glass was on, and so did the chips, the tab bar and
the mini player on any phone that could not run the shader. It read as neither
look and cost a clip, a border and two fills per row on every scroll. Do not
bring it back, and do not add a rim or a sheen to `materialSurface`: a list is
Material 3 in both looks.

**Never nothing.** The old modifier returned nothing at all on LOW-tier
phones, and with "Remove liquid glass" on it once did too: the nav bar, the
mini player and every pane went see-through and the page read straight
through them (seen on device). `materialSurface` always draws.

**White ink over artwork stands on `overArtScrim`, a black scrim, not on
`materialSurface`.** The player hero's badges and buttons and the
visualizer's: a container fill is light on a light theme, under white text.

**The mini player, the tab bar, `GlassPanel` and the full player's disc and dock bend the live screen**
(`LiveGlassLens.kt`, behind `LIVE_LENS_GLASS`, on since 1.9.3). **The mini player and the tab bar are one
material and must match exactly:** same lens, same blur share
(`LIVE_LENS_CHROME_BLUR_SHARE`, now the same share as every other pane's, kept
as its own name so the two bars always pass one value), same frost, and the
same tint — the nav host takes the tab bar's tint *outside* `DynamicColorScope`
and hands it to the mini player
(`glassTintColor`), because inside it `primary` is the album's colour and the
bar came out a different hue from the tab bar under it. Their haze pane is replaced by a
layer that draws Haze's own capture of the screen behind them
(`HazeState.areas[i].contentLayer`, offset by `positionOnScreen`), blurs it (a
fifth of `hazeBlurDp`, 6.3dp on Float) and bends it with the same lens rim,
with no frost veil at all (clear glass: a veil read as a dull frosted pane on
device), and blurs exactly as much as "Backdrop blur" asks: 0 is crisp, unblurred
refraction. (A 6dp floor was tried and removed on device.) The blur runs
first and the lens bends its result, and the blur covers a margin of the page
around the pane (twice the radius plus 2px), drawn into an inflated layer that
the pane's clip trims back. Blurring only the pane's own rectangle clamps at its
edges and smears the edge row into the rim, which is the band that refracts.
The lens layer is opaque: it lays the page colour (`ground`, the haze pane's
`HazeStyle.backgroundColor`) down before the capture. Haze's capture has no app
background, so without it the blurred text was see-through and the sharp text
under the bar read straight through it. The mini player gets that colour from
outside its album scope (`glassGround`), like its tint. Glass frosts as well as bends: at 2-3dp page
text read straight through and fought the labels on top; at 10dp nothing was
left for the rim to bend. Earlier, an 8dp blur together with full frost and the
slab's own 20% veil on top flattened the bend into a dark
smear. For the same reason the slab over a live lens is told so
(`playerGlass(liveUnder = true)`): it drops its stand-in refraction and draws its
body as plain tint at half the body opacity, leaving the rim, reflection and
glint to carry the glass. With `liveUnder` false the slab is bit-identical.

**The mini player and the tab bar cast their shadow from outside their clip.**
Both clip to their rounded shape, so a shadow drawn inside is cut away. Each
wraps its clipped bar in an unclipped box that draws `GlassBarShadow` first,
and only when the bar has an opaque backdrop pane (live lens or haze), which
covers the footprint so only the spill reads; without a pane the shadow would
show through the bar as a dark slab. The stacked mini player's
`AnimatedVisibility` expands and shrinks with `clip = false` for the same reason.

**A lens rim is lit through a flattened normal** (`NL`, 10% of the rim's slope)
while it bends through the full one. Lit at full slope, the glint and the key
light peak where the rim tilts ~15° toward them — several dp inside the edge —
so a second bright edge sat inside the bevel's
crisp outer line and the pane read as two layers, worse at some light angles.
Do not light with `N` on a lens pane. These
surfaces start from the `Float` preset (`PlayerGlassSettings.INITIAL`), as does
every glass setting; `DEFAULT` keeps the classic values because presets inherit
omitted fields from it. On the full player the disc and dock get it through
`PlayerGlassHaze(lensCorner = …)` over the player background's haze source, and
their slabs ask `rememberPlayerLiveLens()` for `liveUnder`, so the pane and the
slab always agree on whether the real backdrop is underneath. This is possible only because the lens rim is computed from the
corner: the slab shader spends its single RenderEffect input on the alpha
heightfield, while this layer spends its input on the backdrop. The slab still
draws on top for the tint, the rim light and the control holes. Same sibling
rule as haze: drawing a source's layer from inside that source recurses. The two
surfaces are one material, so they move together; turning the flag off restores
the haze pane on both, exactly.

**A punched glass slab has its backdrop pane under it, clipped to its own
shape** (the transport disc, the action dock, the mini player). Those are drawn
solid and made see-through by the shader's body opacity, and what shows through
the body and the punched holes is whatever is composited underneath. Each lays
its pane first: the live lens where it runs, else a haze frost of a source it
is a sibling of (`PlayerGlassHaze` over the player background, which carries
the artwork and the reactive glow; `MiniBarBackdrop` on the bar), clipped to
the slab and drawn after the slab's drop shadow, so the holes open onto
blurred light rather than the raw background and the shadow reads round the
edges but not through the glass casting it. This rule used to say the
opposite: the first pane put under the dock and the disc gave the body
opacity an opaque backdrop to be see-through against, and they came out as
flat light slabs (taken out in `5ec5b072`). It came back clipped and after the
shadow (`c607bc59`), and the live lens lays no frost veil at all. What must
not go under them is an opaque pane that is not the backdrop: a haze of a
source the slab sits inside paints that source's flat base colour. Haze also
belongs under a sheet that slides *over* a page, like the audio-tools sheet.

**A dialog is opaque.** A dialog has no haze to blur what is behind it
(below), so any alpha on its container shows the page through its text. The
mixer's Add Plugin list was `surfaceContainerLow` at card alpha, and the FX
chain's preset chips, MIX and Add Effect read through the plugin names. Its
container is `surfaceContainerHigh`, the Material 3 dialog colour, at full
alpha.

**A pane that needs haze cannot live in a dialog, popup or `ModalBottomSheet.`**
Those are separate windows. The backdrop was captured into a layer belonging to
the window behind them, and a haze effect cannot sample a layer from another
window — handing the state across is the same "solid slab" failure as sampling
your own layer, and there is no setting that fixes it. The pane has to be
rendered in the window whose background it is meant to blur, as a sibling of that
window's haze source: `MainPlayerScreen`'s `overlay` slot exists for exactly
this, and the speed panel goes through it. The cost is owning the scrim, the
slide and Back by hand, and that is the cheaper half of the trade.

**The song menu is a `GlassOverlay`, not a `ModalBottomSheet`.** `TrackContextMenu`
(a row's ⋮ from every list; a long-press where a row has no preview) draws in the nav host's overlay slot
at `Alignment.BottomCenter`, where the sheet was, so it blurs the list it was opened
from. Its Track info turns the same pane into the song's tags and Back returns to
the actions. Put back in a `ModalBottomSheet` it is a solid slab, for the reason
above; outside the nav host it falls back to a plain dialog, never to nothing.

**An overlay pane animates in its own layer, and a bottom one clears the bar.**
`GlassOverlayLayer` fades the scrim and slides the pane up, and plays both
backwards on the way out: it holds the withdrawn pane until it has left, and a
leaving pane takes no presses (a second tap on a menu item would run it twice).
Its transitions start hidden (`MutableTransitionState(false)`); given
`visible = true` on its first composition `AnimatedVisibility` would just appear,
and the first menu after launch would pop in. A pane at `Alignment.BottomCenter`
sits `bottomClearance` (the nav host's `chromeHeightNow`) above the bottom edge,
on top of the tab bar rather than over its buttons. In the song menu, Track info
and the actions turn as pages (`AnimatedContent`, the pane's height following),
not a cut.

With low-performance mode these panes keep their fallback: "Disable
animations" (`reduceMotion()`) makes the overlay, the page turn, Track info's
growth and the DAC pop-up come and go at once, and stops the player title's
back-and-forth loop (a tap jumps it to its end and back instead). With no
shader and no blur a pane is plain surface colour, and a pane floating over a
page of text — the overlay, the DAC pop-up — passes
`fallbackMinAlpha = FLOATING_FALLBACK_MIN_ALPHA` so the rows behind do not read
through its own; other panels keep the see-through 0.55 floor.

**A `GlassPanel` inside a scrolling list passes `blockTouchesBelow = false`.**
Its backstop consumes every touch its content does not handle, drags included.
Over a map that is the point — a tap on the pane must not select the nearest
genre underneath — but in a list it means a swipe that starts on the pane never
scrolls the page. The font browser's panes and Discover's cards pass `false`;
the genre map and the globe keep the default.

**The real backdrop is sampled, not reconstructed — when there is one.** The
`playerGlass` shader carries a `uArt` sampler holding the current cover
(`GlassBackdropArt.kt`), so a pane refracts the artwork actually behind it
rather than the procedural field `backdropField` reconstructs. Three rules keep
it safe:

- `uArt` is **always bound**, including on every path that does not use it. SkSL
  requires a child shader to have an input; an unbound one fails the draw rather
  than sampling blank.
- `uArtMix = 0` must stay **bit-identical** to the reconstruction-only output.
  That is what let the sampler ship without re-tuning a single preset, and it is
  what every device with no decoded cover falls back to.
- Whoever provides the art decides whether there is any: `rememberBackdropArt`
  returns null when it should not be used and a null art binds `uArtMix = 0`.
  In the player that means **only while the blurred album background is
  drawn**, because that is the only time the artwork is really behind the
  glass; without it the backdrop is the flat wash, which the shader
  reconstructs exactly. The background is no longer a setting
  (`playerBlurredBackground` reads true, see "Settings has one home per
  setting"), so in the player the art is always used. The gate is still in
  `MainPlayerRoute` (`rememberBackdropArt(…, blurredBackground)`); a player
  background without the artwork has to switch it off again.

The **mini player is the exception, and deliberately so.** Away from the player
the artwork is not behind the bar — the app's own content is — so its glass uses
`BackdropArtFit.PANE`: the cover is fitted to the bar rather than positioned
behind it, and its colours sweep along the length of it. Two reasons it cannot
use the honest mapping. A 64dp bar is about a twelfth of a phone, so the slice
of a 64px thumbnail behind it is roughly five pixels and refraction moves it by
a fraction of one — the effect would be invisible. And there is nothing honest
to map: PANE is a material property of the bar, not a window onto something, so
it is not gated on the player's blurred background either. Its scrim is flat
(zero height in `uArtScreen`) and read off the bar's own position on screen,
because the gradient it stands in for is not really there.

The **Glass spectrum on the hero art is the other exception**. It lies on the
cover itself, so the cover is behind it whatever the player's background is,
and the hero hands it the art unconditionally while that style is on. The
mapping is still honest: the cover box records itself with `backdropFrame`, and
`playerGlass(artFrame = …)` resolves the pane against that box through
`anchorInFrame` rather than against the window, so the band lenses the bottom of
the cover it actually covers. `GlassSpectrumTest` holds the arithmetic.

Its body is drawn solid like every other slab, but **with an inner alpha ramp**:
nested `DST_OUT` strokes clipped to the body take the rim down to about two
thirds while the core stays opaque. The shader reads normals off the alpha
gradient a few pixels either side of each point, which is a hairline on a body
the width of the cover. Do not replace the ramp with a blur of the edge: the
output alpha is capped by the input alpha, so a soft edge comes out as a smudge,
and do not drop it — without it the body reads as a flat pane.

The backdrop art (`uArt`) is a bitmap and not a live layer capture because it
cannot be one: `RenderEffect.createRuntimeShaderEffect` binds exactly one
input, this shader spends it on `content` (the alpha heightfield every bevel
normal comes from), and no public API turns a `GraphicsLayer` into an
`android.graphics.Shader` for the second. The thumbnail is 64px on purpose — it
stands in for a 64dp blur, and lensing a sharp cover would refract detail that
is nowhere on the screen.

The **waterfall is one mesh, drawn with `drawVertices`**, not a `drawLines`
and a `drawPath` per line. A stroke wider than a hairline with round caps is
something the renderer cannot batch: `drawLines` strokes every segment as a
path of its own, about six thousand a frame across 49 lines, and Ridgeline's
ground added 49 concave anti-aliased fills on paths that change every frame.
That halved the player's frame rate. `WaterfallMesh` builds every line as a
ribbon with a one-pixel transparent fringe (its anti-aliasing) and the ground
as a strip under it, in back-to-front order, in one or two draws.
`WaterfallMeshTest` holds the geometry. Below Android 10, where a mesh is not
hardware-drawn, each line is one stroked path, never `drawLines`.

Search bars and other app chrome take **the mini player's** settings
(`LocalMiniPlayerGlass`), not the player's. The player route overrides
`LocalPlayerGlass` with its own material for the transport, which is right there
and wrong everywhere else. `GlassPanel` publishes whatever settings it was handed
as `LocalPlayerGlass` for its own shader, so its `glass` parameter is the whole
material — frost and shader both.

**The Studio previews each material on the tab that owns it.** The Player tab
shows the transport straight over the swatch, with no pane behind it — that is
how the real screen is built, and a `GlassPanel` there would be drawing the *UI
panels* blob, which those sliders do not control. The pane belongs to the UI
panels tab, alongside the mini player bar, because that tab is what tunes it.

**The UI panels preview is a small screen built the way the real one is**
(`UiPanelsPreview`): a page of drawn covers and song rows as the haze source,
and as its siblings the real `GlassSearchBar` on top and the mini player
stacked over the tab pill and Search button below, with the nav host's tint
and ground handed to the mini player. **Every one of them is given the
preview's haze state.** A mini player without it draws neither its live lens
nor its shadow, and the preview shows a flat pill that is not what ships,
which is what it did until this was fixed. The backdrop must have detail:
over a smooth gradient, blur, refraction and dispersion move nothing visible.

**Under the CRT effect over the whole app, the glass holds still.**
`rememberFrameSeconds` runs no frame clock while `LocalAppTvShader` is on, as
it runs none under "Disable animations": the app is one layer through two
full-screen shader passes, and every frame of glass motion re-ran both over
every pixel, so the library screen alone ran the phone hot (seen on device).
The tube and the mask hide a breath of glass anyway. Turning the CRT off
brings the motion back.

### Lyrics: letter glass and god rays

**The lyric glass reads the same optics as the player's, and its defaults are
the old pins.** `liquidGlassModifier` used to set roundness, depth, motion,
reflection, gloss, tilt, light angle, Fresnel and frost by hand; they are
`LyricsFxSettings.glass*` now, mapped exactly as `playerGlassModifier` maps the
glass fields, and each default reproduces the pinned uniform
(`the letter glass defaults are the uniforms the lyric shader used to pin`).
Moving a default restyles every listener's lyrics. Still letters
(`glassSurfaceMotion` 0) run no frame clock and tilt-blind ones
(`glassTiltReactivity` 0) hold no gravity sensor, as on the player glass. The
letters get no lens rim, haze or drop shadow: those belong to panes.

**The shadow and the god rays live on the background, under the glass UI — in
both modes.** They are drawn by `LyricBackdropFx` in the player's `fxUnderlay`:
full screen, after the album background, inside the haze sources, before the
hero and the chrome. Drawn inside the lyric surface, a shaft stopped dead at its
bottom edge, just above the song title, and the title, the progress tube, the
disc and the dock had none of the light under them (seen on device, in "Under"
and then again in "On top", which was left behind in the surface). A render
effect reads only its own layer, so the lyric view records its letters into a
`GraphicsLayer` (`captureLetters`, innermost, before the glass, so the glass
shader does not run twice; only the tube's curvature, `lyricCurvature`, sits
inside it, so the copy is bent with the letters and the shadow and the shafts
follow the bent lines; it is a CRT tube's bow, `crtSource`, never a
fish-eye: the middle keeps its size and lines bow outward toward rounded
corners, the top and bottom (`curvatureDeg`) and the sides
(`curvatureSideDeg`, unset on a curve saved with one angle, which keeps its
shape) bowed apart, as the CRT effect's two curves; set to the sung line or word, its tube covers that box alone, read in
the draw phase, flat outside it and in its own rounded-off corners) and the backdrop draws that copy as its input and
hands back only the shadow and the shafts (`uRaysOnly`); the real letters draw
over them. Where a pixel's march misses the sung line it hands back nothing,
never the copy: returning the copy there drew plain letters under the glass
ones on every line the march missed. It fades the copy's top and bottom as
`lyricsEdgeFade` fades the real ones, and fades as a whole with
`lyricsProgress`. The legacy player has no `fxUnderlay` and is given no
backdrop, so its lyric view draws both itself. The Studio's preview is built
the same way as the player, down to the `lyricsEdgeFade` box around the
playing song's lyrics.

**A `GraphicsLayer` recorded in a draw modifier gets a plain `Density`.**
`LyricBackdropFx` records its copy with `layer.record(Density(density,
fontScale), layoutDirection, size) { … }`, never the node's `record { … }`
shorthand. Inside the shorthand's block the node's draw scope is its own
density (Compose 1.10's `LayoutNodeDrawScope.record` hands itself to the layer
as the density and then reads it back), so the first `dp.toPx()` there
recursed until the stack overflowed: the app crashed the moment the lyrics
opened (seen on device, a `StackOverflowError` in `getDensity`). Work out
every length before recording either way. `record { drawContent() }` is safe:
`drawContent()` draws the children in their own scopes.

**"On top" is the backdrop's shafts plus the letters' own light, added to
their own pixels.** A layer under the letters cannot add light over them, so
`lyricRaysOnLetters` does that part alone (`uOnLetters`): a render effect on
the real letters' layer, outside the glass, that adds the light falling on
each letter to it, capped where the old composite capped it, at what the
letter covers. Over the shafts that is the old in-surface composite, to float
precision under white light (checked by running the shader through Skia:
1.2e-7); under a coloured backlight a see-through letter can come out a few
percent dimmer, because its colour cannot pass its own coverage. Two ways of
doing it are wrong, and both were tried. Measuring the cap on the backdrop's
copy: the copy is drawn before the glass at full strength, so there was never
any room and the sung line stood grey under its own light (seen in that
render). Adding the light as a separate layer: the layer brings alpha of its
own, which in the edge fade's offscreen buffer hides that much of the shafts
behind every soft letter. The effect leaves the letters' alpha alone, so the
edge fade takes them like any letters.

**The light is one light, in root px, sized by the window.** `LyricRayLight`
works it out once, in root px, and every layer that draws it — the backdrop,
the light on the letters, the glass, the shadow — only moves it into its own
pixels (`frameFor`). Every distance in it (how far the light stands off the
line, the reach cap, the tilt and the sway, the backlight disc) is a share of
the window's short side, and it centres on the lyric surface's box, not on the
layer drawing it. It was worked out as shares of the rays layer's long side,
which is the preview's width in the Studio and the screen's height in the
player, so the same settings put the light more than twice as far from the
sung line in the player and the preview showed a different picture (seen on
device). `GodRayGeometryTest` pins it.

**The Studio previews the playing song with the song's beat.** Its lyrics pulse
from the player's own analyzer, still when the song is paused. The synthetic
kick under them pushed every shaft brighter and longer twice a second, so a
paused song's preview showed shafts its player did not. The sample row, with no
song behind it, keeps the kick: it is the only way to see the beat settings.

**The cover in the player does not move with the music.** Wave Candy's kick
swells its own lines and nothing else; the cover used to punch in on every
kick (with a strength slider), and a cover bouncing to the bass was taken out.
`WaveCandySettings` no longer has `kickZoom`; an older saved blob that has it
still reads, since the settings JSON ignores unknown keys.

**God rays drawn in the lyric surface (no backdrop) wrap the lyric list outside
its side inset and outside the glass.**
`lyricGodRays` is a RenderEffect, and a layer cannot draw past its own bounds,
so a rays layer inside the inset cuts every shaft off in a hard vertical line
14dp from the edge. Outside the glass, so the shafts are gathered from the
finished glass letters. The effect is rebuilt in the layer block every draw,
like the glass: a RuntimeShader effect takes its uniforms when it is created.

**Only what is being sung shines, and it is dimmed on itself.** The band is
the active line from `layoutInfo` (`item.offset - viewportStartOffset`, read in
the draw phase so the shafts follow the scroll glide) or, with "Follow the sung
word", the word the karaoke line reports. Letting every visible line emit made
the screen a wash; with no guard at all the sung line was buried under its own
light (the first prototype, at 90° elevation). The guard takes 65% of the light
off *under the sung letters, in their shape* (their coverage, `src.a * lit`),
never across the band: dimmed across the whole band, the shafts showed on device
a darker rectangle wherever the sung line was. The band's sides
are `GodRayGeometry.UNBOUNDED`, not infinity: an infinity reaching
`smoothstep` comes back NaN. The one exception is "All lyrics shine", which is
the Shadertoy's whole image as the light and asks for exactly that trade.

**The Letters march is the Shadertoy, tap for tap.** "Crepuscular light"
(ls2Xzd) starts from a centre tap, `texture(tc) * 0.4`, and steps *before* it
samples, so its taps sit 1..N steps toward the light. Both are kept: without
the centre tap the port was 5% off the original, and with it, at the 50-sample
quality, 0.1% (correlation 0.99998, checked against the GLSL run verbatim).
`GodRayGeometryTest` pins the weights, so a change to the gain, the tap or the
decay conversion that drifts from the original fails there. The jitter is the
one deliberate difference: interleaved gradient noise instead of its sine hash,
for the same banding fix with less visible grain.

**Backlight decays from the light, Letters from the pixel.** The article's
decay weights samples by their distance from the pixel. In Backlight the light
is always at the far end of the march, so that weighting all but erased it;
there the march is averaged plainly and the decay is counted out from the light.
Letters keeps the article's weighting. Both normalise by the sample count, so
the quality setting changes grain, not length or brightness (`GodRayGeometryTest`).

**The lyrics' shadow is on the background, never on the letters.** Nothing
dark may be drawn inside the lyric glass layer: the glass bevels and relights
everything in it, so an extruded backing glyph and a contact `Shadow` there came
out as solid blocks with dark slabs stuck to every letter (seen on device). The
shadow is `lyricShadow`, its own layer outside the glass and the side inset — a
blurred, offset, darkened silhouette of the finished letters laid under them,
falling away from the light — and inside the rays, whose shader weighs every
sample by `lit` so the shadow neither shines nor blocks the light, and composites
it under the shafts.

**The glass letters catch the rays' light from one shared light.**
`LyricRayLight` computes the light once, from the rays layer's own box, and
both layers read it in their draw phase; the glass moves it into its own pixels
through the two layers' root positions (`frameFor`). Computing it twice, once
per layer, is how a glint ends up somewhere the shafts are not. The `uRay*`
term in `LIQUID_GLASS_SRC` is gated on `uRayAmount`, and every pane — the
transport, the mini player, every panel — sets it to 0 (`setNoRayLight`), which
leaves their pixels bit-identical: added exactly 0. The term only ever adds
light. It used to dim the bevels turned away from the light and darken backlit
letters into silhouettes, and on device that read as shadows on the letters.

The rays are off by default, and the 17 presets ahead of `Sunburst` set no
glass optic and no ray field, so nobody's lyrics change on upgrade. The
low-performance glass switch drops the rays with the glass.

**The lyrics view's empty state carries the server's word, and one action.**
Under "No lyrics available" the view says where a server timing stands
(`ServerLyricsStatus`: timing on which host, failed and why, no file to send,
no server) in the same dim white as the empty text, and offers one text
button, "Time lyrics on the server", only while no job runs and a lyrics
server is configured. The same action sits in the player's overflow menu, so a
song that has plain or line-timed lyrics can still be sent. Nothing here is a
pane, a chip or a card: it is text under text, as the empty state was. The
timing itself never joins the lyrics race, which has fifteen seconds; it runs
in `ServerLyricsEngine` after the race, outlives the track, and its answer is
kept in `cached_lyrics` and read before any race from then on.

**Timing lyrics is a Trypt Mania job, not a spinner.** Songs go in a line
(`ServerLyricsEngine.request`) and `LyricsSyncWorker` drains it as
`StepManiaGenerationWorker` drains the chart queue: an ongoing notification
naming the song and its stage (downloading, sending, hearing), then a note
that can be swiped away saying which songs got their words. A song with no
file on the phone is downloaded through the app's own downloads first and
kept, as Trypt Mania charts only what is on the phone. The lyrics view shows
the same stage under the empty state, with "Cancel" while the line runs and
"Time lyrics on the server" when it does not. By itself a song joins the line
only after fifteen seconds of play (`SERVER_LYRICS_AFTER_MS`): on a phone
skipping through a playlist, every song passed over was downloaded and timed
for nobody. The worker runs up to eight songs at once, as many as the chart
server said it has copies, like a chart batch's lanes.

**The lyrics editor is built as the Player Visuals Studio is.** `StudioPreview`
(the real renderer, condensed, with the listener's own Lyrics FX) pinned up
top, the transport and the one big "Stamp now" button under it, the lines as
plain rows (the selected one tinted with the accent at a seventh, never a
pane), and every setting below in `StudioSection`s with `FxToggle` and
`FxNudge`. It reaches the song through the shared `PlayerViewModel`, passed in
from the nav host as the other tool screens get it, so a stamp is the song's
own position and a tapped line seeks. Saving goes through
`ServerLyricsEngine.publish`: the same `cached_lyrics` row a server would
write, the player's lyrics replaced at once, and the download's `.lrc`
rewritten with word times. The row list pads its bottom by
`LocalBottomChromeInset`, like the Studio.

### Music links

A setting that "moves with the music" never moves its saved value. The visual
links publish this frame's values in `VisualLinkLive`, and the renderers fold
them in **inside their draw and layer blocks** (`live.lyrics(baseFx)`), the way
the bass pulse is read. Read in composition, every lyric layer would recompose
sixty times a second. Composition may read `linkedKeys` alone, which changes
only when the links do — to keep a clock running for a linked ray setting.

**A rate is a target only when it adds to a phase, never when it multiplies
the clock.** The 3D letter wave runs on a clock of its own,
`rememberLetterWaveSeconds`, which each frame moves on by the frame's length
times the wave speed (linked or not) and wraps at 20π, a whole turn of every
term the letters use. It was the app clock times the speed: the slider then
threw the letters to a new pose at each step instead of speeding them up,
looked dead while dragged, and a link on it would have shaken them every
frame. One clock for the app, so every line rides the same wave; it stops
with "Disable animations" and the CRT over the whole app, as the glass does.

The knobs are the same rule in native code: the engine keeps each linked
knob's own value (`Route::base`) and saves *that*, so a mix saved mid-song
holds what the listener set, not where a kick left it. A knob turned while
linked sets its own value and leaves the sound to the link.

**Settings › Appearance links the CRT effect as the Studio does.** The tab sits
in `StudioMusicLinkScope`, which hands it the Studio's own link host, sheet,
editor and undo on `LyricsFxStudioViewModel`, so a link made there is the
Studio's and the Music links page's, never a second copy. The curve and tilt
link in both places (taps follow them; see the CRT paragraph below); the zoom
stays unlinked.

The link sheet and the editor are in-window glass, siblings of their screen's
haze source, never a dialog or a `ModalBottomSheet` (see Glass). The live
reach and position on a slider or knob are drawn in the draw phase from
`MusicLinkHost.liveValue`; they redraw the control and recompose nothing.

The editor takes nearly the whole window: from under the status bar to just
over the system bar, with `HideAppChrome` putting the tab bar and the mini
player away while it is open. Fitted under a preview and over the bar, it had
room for its title and its first row, and every choice was a scroll away. The
meter in its band picture is what shows the link moving.

Appearance's cover glow links in the same scope: its radius and brightness
(`IntSettingSlider` with a `linkKey`). The cover
glow is drawn in cover-art view, so the player runs its two links there
(`VisualLinkTargets.COVER_KEYS`) while the rest of `lyrics.` wait for the
lyrics. Colour transition is not linked: it is an animation's length, read in
composition.

Every Studio tab links, not only Lyrics. A key's prefix is the part of the app
that draws it (`LinkArea`): `lyrics.`, `player.` (the play disc, chevrons and
dock), `panels.` (the mini player, tab bar and every pane), `tv.` (the CRT
effect) and `visualizer.` (the ambient visualizer). Three drivers run them,
each where its renderer lives:

- The app's own, in `MainActivity`: `panels.` and `tv.`, which are on every
  screen. It reads `MusicLinksRepository.previewVisualLinks`, so the link open
  in an editor runs there too and the CRT moves while it is shaped.
- The player's, in `MainPlayerRoute`: `player.`, `lyrics.` while the lyrics
  show, `visualizer.` while the ambient visualizer is the background. Its
  `parent` is the app's driver, so `LocalVisualLinks` there has both.
- The Studio's, over the whole screen: every saved link plus the draft, for
  its previews.

Which glass a renderer is drawing is `LocalGlassArea` (PLAYER inside the player
route and the Player tab's preview, PANELS everywhere else). Glass reads its
live values with `links.glass(base, area)` in its layer and draw blocks, and the
haze frost goes through `Modifier.glassFrost`, whose `hazeEffect` block re-reads
the blur and tint each frame. Never build a `HazeStyle` from the glass settings
in composition: the blur would ignore its link.

The CRT effect draws `appLive.tv(tuning)`, and touches are bent through that
same live shape, so the curve and tilt can be linked. **A gesture keeps the
tube it started on** (`gestureTube` in `MainActivity.dispatchTouchEvent`), from
the first finger down to the last up. Bent through each frame's shape, a finger
held still would drift with the beat and a tap could become a scroll; held, a
tap lands where it was seen. The lyrics' own curve still steps aside by the
tube as set (`LocalAppTube` is `appTvNow`), so a linked curve does not switch
it on and off with every beat. The ambient visualizer's
render thread is handed its live opacity and black point (`updateLive`), NaN
meaning "as saved", since it is a GL thread that cannot read Compose state.

What a link may not move, and why, is the list in `VisualLinkTarget`'s doc.
`VisualLinkTargetsTest` holds every target to a ♪ in the Studio's source and
every ♪ to a target, and checks each one moves its own setting and no other.

### Titles over descriptions

**A title is SemiBold and its description Regular, two weights apart.** The
theme's titles (`titleLarge`, `titleMedium`, `titleSmall`) and row title
(`bodyLarge`) are SemiBold; descriptions (`bodyMedium`, `bodySmall`) are
Regular. At one weight a Settings row's name and the line under it read as
one, told apart only by size and colour; Medium over Light was tried and read
too thin. `titleLarge`, `titleMedium` and the row title are a size under where
the app had them (17, 15, 15 sp against 18, 16, 16) and keep the line heights
they had, so no row changes height. All of it lives in one table,
`TypeDefaults` (`domain/model/TypographySettings.kt`), which `buildTypography`
reads.

**A title over a description takes its style's weight; it does not force
one.** The titles that used to force Bold or Black (the link editor, the
Theme Store, sheets, headers) now inherit their style, so the typography table
reaches them. Text that is not a title keeps its own weight: numbers and
readouts (Stats, the EQ's values), labels and buttons, Car Mode, Trypt Mania.
Plain text drawn in `bodyLarge` (the lyrics' plain view, the search field,
"no lyrics") pins Regular, since that style is the row title.

**The typography table** (Settings › Fonts, under the fonts): Material's
fifteen styles, each with its own font, weight (Thin to Black), size, letter
spacing and letter width, stored as `TypographySettings` and applied in
`buildTypography` over `TypeDefaults`. A style left alone follows the main
font, the defaults and the page's Width; the text-size setting scales the
table's sizes too; a bigger size takes a taller line in proportion. Every
font loads at all nine weights (`loadAppFontFamily`), so a variable font draws
each one.

**Width is drawn by the font where it can, squeezed where it cannot.** The
page's Width (70% condensed to 130% wide, in steps of 5) and a style's own
width go through `loadWidthFont`: a font with a `wdth` axis (Bricolage,
IBM Plex Sans, Instrument Sans, any import whose every cut has one) is
declared at that width, and whatever its axis cannot reach, and every font
without one, is a `TextGeometricTransform` squeeze. Touches and layout follow
the squeezed text, since Compose measures it as drawn.

**The Fonts page is condensed** (the owner picked it from four mockups): the
font being tried is one row with its Use button; the text panel holds the
size steps as one segmented line and the Width slider; the fonts are chips in
their own letters, a hold on an imported one deletes it; and the table edits
every style in its own row, a weight menu and size and width steppers, with
the font and letter spacing in a menu on the style's name. No row opens: a
change is made where it shows.

**The Fonts page reads its fonts off the main thread, once.** Each chip is
drawn in its own font at the one weight it shows (`loadSampleFontFamily`), the
preview in the whole family, all read on the IO dispatcher and preloaded
there; until then the chip is in the theme's font. What it reads is kept while
the page is open, so a chip scrolled away and back is not read again, and an
imported font's family comes from its files as read once each
(`FontFamilies.cutOf`, by path, size and date). Each chip used to read its file
in the frame that drew it, and an imported one every file in the library to
find its family: with a long list of fonts the app froze.

### Text on glass

**Text on glass follows the glass, not the theme** (`GlassInkScope`). Every pane
of the shared material (`GlassPanel`, `PressableGlass` and so `GlassPill`) and
the mini player and tab bar work out how the glass looks as one colour — the
frost over what is behind it (`LocalGlassGround`, the page unless a screen says
otherwise; the galaxy says deep space), and the slab's tint over that — and give
their content light ink on dark glass and dark ink on light, with the accent
moved only as far as it must to read (3:1). The theme's own ink is the wrong
answer on dark glass in a light theme, and the other way round. Nested glass
tints its slab with the parent's original accent (`LocalGlassAccent`), not the
readable one, so a chip on a sheet stays the sheet's material. `GlassInkTest`
sweeps grounds, tints and opacities and asserts AA for the text everywhere.

**The player says what its glass stands over** (`LocalGlassGround` provides
`playerGlassGround`). The player paints its own dark background in every theme,
so its panes — the Speed panel, the audio tools, the preset browser — work their
ink out against that, never the theme's page. Against a light theme's page they
decided they were light glass and drew dark text on dark red. `PlayerGlassGroundTest`
sweeps covers from white to black. And a switch on glass takes its "off" colours
from the ink too (`glassSwitchColors`): Material's own come from the theme's
surface, which the ink does not move, and were a white pill on dark glass.

**A selected chip's rim is `matchParentSize`, never `fillMaxSize`.** Filling made
a selected chip as wide as the room offered: invisible in a scrolling row, but in
a plain `Row` or a `FlowRow` it swallowed the line and squeezed its label to a
letter a line.

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
  (it says which of eleven tabs you are on, and searching is exactly when you are
  about to be moved between them). Discover's genre rail lives on the page, not
  inside the bar's pane — a pane four rows deep covers the feed it filters.
  Discover's bar is the field alone, one row like every other: its "Browse the
  map" line made it twice their height, so the map and "no match" live in the
  genre row under it.
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

**Every button in the player's sheet is `PressableGlass`**: the four status
cards and the Visualizer and Waveform buttons under them, one material and one
corner. Those two stayed on the old frosted tint after the cards moved,
and read as dark outlined tiles beside real glass. Their "on" is the accent
and a ring, not a stronger tint: glass has none to switch to.

**A press's swell is animated into `GlassPress.amount` by the animation
itself** (`animate { press.amount = value }`). It must never be copied across
in a `SideEffect`. The copy only runs on recomposition, and an animation read
only inside the `SideEffect` never causes one. The swell then freezes wherever
it was when the finger lifted, and every pill stays half pressed.

**Every glass button and pill is the panel recipe, never a faint slab.**
`PressableGlass`, `GlassPill` and `GlassChoiceChip` draw through
`GlassMaterial`, the same layers `GlassPanel` uses: the live lens or the
haze frost, then a **solid** slab with `lensCorner` (`Dp.Infinity` for a pill or
a disc). `PressableGlass` used to draw its slab at a tenth of the tint, so every
button and pill in the app looked like the low-performance fallback even where
the shader runs. Their material is the Studio's UI panels settings
(`LocalMiniPlayerGlass`), the universal glass. Only the player's own controls
pass `LocalPlayerGlass`, like the status grid. There are no stock Material
`FilterChip`s or `AssistChip`s left; a row of choices is `GlassChoiceChip`.

**A pill frosts `LocalGlassBackdrop`, which a page provides only for a source
its pills are siblings of.** Discover provides its page backdrop. With none,
the pill is the shader glass without a frost under it. That is still glass,
not a broken blur.

**A screen borrows the mini player through a stable handle**
(`TakeOverMiniPlayer`, `MiniPlayerSlot`). The nav host reads only
`slot.handle`; the bar reads `handle.takeover`. The screen hands over a new
takeover on every composition. If the nav host read it, the nav host would
recompose, recompose the screen, and be handed a new one forever. Swipe to
discover uses it: while the deck is up, the bar shows ✕, undo and ♥ punched
into its glass instead of the track, at the same height, and a swipe along the
bar skips or keeps.

**Settings has one home per setting.** The font size (the five steps and
Use system font size) is on the Fonts page, in the text panel beside Width; Appearance keeps only the Font Library row that leads
there. There is no Now Playing group: the view mode is what a tap on the
player's artwork changes, the player's tint is Appearance › Dynamic Colors,
and the blurred album background is always on. The two removed switches
read as on whatever was stored, so nobody is left with one off and no way
back. A copy of a setting in a second place is not a convenience; it is two
switches that can disagree.

**The music folders are one list shown in two places, never two lists.**
Settings › Library › Music folders (the owner asked for it there, with +)
and Library › Local's Folders both read and write the user's folder roots
(`PreferencesManager.userFolderRoots`), add through
`ScanCoordinator.addFolderAndScan` and remove through `excludeFolder` behind
the same `ExcludeFolderDialog`, so a folder added or removed in one is gone
from or there in the other at once. A second store of folders for Settings
would be exactly the two switches above. An empty list scans the whole
phone, and the row says so.

**Playlist Import in Settings is the link import.** No account needed: the
link is looked up as it is pasted (`rememberSpotifyLinkPreview`,
`LinkPreviewCard`, the same pieces as Library › Playlists › New playlist),
and Import Playlist reads the public playlist when the account cannot.
Browse My Playlists, for your own playlists and Liked Songs, shows only with
Spotify connected; the field is never greyed out behind a sign-in again.

**An imported font is a family, not a file.** Fonts arrive as a file per
weight; the library groups the files that name the same family
(`FontFamilies`: the typographic family, else the legacy one without its
weight word) into one entry, "Imported · 3 styles", and deleting it deletes
them all. A chosen font is stored as one file's path, and loading it brings
the rest of its family, each file at its own weight and slope
(`loadAppFontFamily`, and the lyrics' font through `loadImportedFontFamily`),
so a SemiBold title draws the SemiBold file. A family of one file is declared at
every weight over that file, as before. `FontFamiliesTest` runs it on the
app's own Inter, which is five files.

### Pages and the tab bar

**The app has ONE pager over one flat list of pages** — Home, Discover, World
radio and the Library sections — held in `APP_PAGES` (`ui/navigation/AppPages.kt`)
and ordered by the `page_order` preference, plus Search, which is always there.
There used to be two nested pagers, an outer Home/Discover/Library and an inner
one over the Library's sections. Do not reintroduce a second pager, and do not
pin any page to an index: `local` was pinned to page 0 for a long time, which is
why moving it in Settings did nothing.

**The nav bar and the Library switcher both drive that one pager.** (Users see
it called the nav bar; the code and this file still say tab bar.) The bar is
Home · two chosen pages · Library in a glass pill, with Search as a round button
beside it (`GlassTabBar`, `AppTabs.kt`). Library is one tab over every Library
section; the chip row at the top of the Library page (`LibrarySectionSwitcher`)
moves the same pager to a section, and is not a pager of its own. A tab tapped
from a pushed screen pops back to `home` first, because the pager is only drawn
while the NavHost is there.

**The bar is the mini player's material, built the mini player's way.** It is a
sibling of the haze source, never inside it; its slab is drawn solid and its
glyphs are punched out of it, as the player's action dock does; and it asks
`rememberLiquidGlassAvailable()` before drawing that slab, because a solid
slab without the shader is an opaque block with holes in it. Its titles are plain text, not punched: the
glyphs are chunky because the bevel needs about 3dp of stroke to read as an
edge, and an 11sp title's strokes are thinner than the bevel.

**Without liquid glass, the bar is Material 3's.** Whenever the panels' glass
cannot run — "Remove liquid glass", the UI panels tab's glass switched off,
or a phone before Android 13 — `flatChrome` is
`!rememberLiquidGlassAvailable(miniPlayerGlass)` and the app shows
`FlatTabChrome`: a `NavigationBar` across the bottom with every tab in it,
Search included, and the mini player docked flat above it. It stands on the system bar's inset
itself, so `chromeHeight` has its own value for it (`FLAT_NAV_BAR_HEIGHT`),
and it does not fold. The floating pill without its glass was a shape with
nothing under it.

**Scrolling down folds the mini player into the bar; scrolling up unfolds it.**
It is driven by nested scroll at the nav host, so every list drives it without
knowing. Lists pad by the bar's *expanded* height even while it is folded — a
padding that followed the fold would jolt the list mid-scroll.

**The two middle buttons are the listener's choice**, from `NAV_BAR_CHOICES`:
Discover, World radio and the Library sections, Discover and World radio by
default (`nav_bar_slots`, synced). Read them through `sanitizeNavBarSlots`,
never raw: it guarantees two different pages the bar can hold, whatever came
from storage or sync. A pinned Library section lights its own button, not
Library's, and Library skips it, so two buttons never open the same page. A
hidden page loses its button but keeps its slot.

**"Hide mini player when the nav bar shows" makes the two take turns.** The open bar
is the tabs alone, with no mini player stacked over them. The folded bar is
unchanged: the current tab's bubble, the mini player, then Search. Both states
are one row, so the expanded height lists pad by drops the mini player's row.
The open bar then has no way to the player except scrolling, which a short page
cannot do. So a tap on the tab you are already on (otherwise a no-op) folds
the bar. Do not make that tap switch or reload the page instead, and do not
start pages folded: tab switching would become two taps.

**At a listening party the chrome grows a way back to it.** While a party
is on (`PartyController.active`), a round button with the party's glyph
(`AppTab.PARTY`, a one-tab `GlassTabBar`: Search's glass exactly) stands
over Search, the same 64 dp, and the stacked mini player gives up that
width for it, so the two share its row. Folded, it sits between the mini
player and Search; taking turns, it joins the open bar's row before Search.
On the flat chrome it is a tonal icon button beside the flat mini player.
Its tap opens the party screen, where its glyph is lit; it is never a page
of the pager. When the party is over or left, the chrome is as it was.
Never a second mini player, and never a button drawn in another glass.

**The bar is on every screen but four**: the player, the mixer, Oxford and car
mode, whose own controls run to the bottom edge (`chromeHiddenRoutes`). The
download pill follows the same list. A screen that needs the window for only
part of its life asks for it with `HideAppChrome` instead of joining the list:
either galaxy in full screen, the music link editor while it is open, and
Trypt Mania only while a chart is being played, practised or edited in the map
editor. Its song list, panels and results are menus and keep the
bar like any other pushed screen; putting the whole route on the list hid the
tab bar and the mini player from the menus too. While a screen holds
`HideAppChrome` the nav host runs it full-bleed, so it pads the system bar
itself; stopped above the bar, the black playfield had a strip of theme
background under it, white on a light theme.

**Trypt Mania never has the mini player**, on its menus, song list and results
alike (`miniPlayerHiddenRoutes`, checked by route in the nav host, as well as
`HideMiniPlayer` in GlyphRoute): it keeps the tab bar only. The route is known
before the screen composes, so the bar cannot show there for a frame or after
a run, whatever order the screens' effects run in.

**Bottom padding comes from `LocalBottomChromeInset`, never a number.** It is
measured from where the reading screen ends: pager pages and full-bleed routes
run under the system bar, so theirs includes it; pushed screens stop above it,
so theirs does not. Use `bottomChromePadding` for a list's last row. A screen
that pads the navigation bar itself as well must consume those insets first, as
the genre map's panel does, or the bar is counted twice. The old flat 80dp fell
short of the system bar plus the mini player on every page that runs under both.

**Every page's top bar is a way into Settings**, Search's included. The tab bar
keeps Home reachable from anywhere, which is what makes this hold even with
pages hidden — but a page without the button is still a page whose own chrome
cannot reach Settings.

**Home is never hidden, and the Library keeps at least one section.** Settings
disables those eyes, the view model refuses the write, and `visiblePages()`
restores them regardless — the third layer is not redundant: a hidden set can
arrive from another device's settings sync without either of the first two ever
running on this one. Hiding Discover or Radio removes its tab, nothing more.

**The bottom bar's fold is two fades in turn, never a crossfade.** The row
going fades out in the first two fifths of the fold, then the row coming
fades in (`TabChrome`'s `AnimatedContent`). Crossfaded, the full-width bar's
slab and its glass shadow sat at half opacity behind the folded pill and the
mini player for a quarter of a second: a grey slab across the bottom of the
screen with every scroll (seen on device).

### List rows

**Every list row is Material 3, in both looks.** Song rows (`TrackItem`, the
library's `SongRow`, search results), the Library's category tiles, folders,
album and artist cards stand on `materialSurface`, never glass: a list is the
heaviest thing to scroll, and the shader is for chrome. See Glass.

**Every list row is one shared height**, `MonoDimens.listRowHeight`, applied with
`Modifier.height(...)` in place of vertical padding. Songs, artists, genres,
folders, `TrackItem`, search results, the folder browser and the genre detail
list all take it, so a scroll reads as an even column.

Rows used to size themselves to their content, and library content is not
uniform. Three things moved the height: a subtitle whose artist and album did not
both fit wrapped to a second line; a linkable artist carried `linkHitBoxV`'s
inset where a plain "Unknown Artist" did not; and a row whose text was shorter
than its artwork was sized by the artwork instead. Neighbouring rows differed by
up to a whole line.

The height is **derived from the typography, not a dp constant**. The app ships
its own text-size setting (0.85x..1.5x) and rebuilds the type scale from it, and
that multiplies with the system font scale. 64dp is correct at 1.0x and clips the
subtitle above it. `ListRowHeightTest` walks both scales and checks every row
shape against the budget; if you raise a line height in `Type.kt` or add a taller
row, that test is where it surfaces.

**A song's cover is its own, its album's in its own folder, or none.**
Albums are grouped by tags across every folder, so the scanner gives a
coverless song the cover of another song of its album only from the same
folder, and never inside a catch-all album ("Various Artists", "Unknown
Album", "Singles"; `AlbumArtShare`). Before, a coverless song tagged
"Various Artists" took the first cover of any unrelated file with that tag
anywhere: a random picture. A song with no rightful cover shows the note.

**"Find album art" asks, and takes only the same song.** In a song's ⋮
menu for a file on the phone, and on the selection bar of a folder (Select
from a song's menu, then tap to add songs). `AlbumArtFinder` searches the
catalogue, then iTunes; `ArtMatch` takes a result only with the same title
and the same artist within 5 s, or, for an artist tag that names nobody,
within 3 s, and none is better than a guess. The cover goes in the app's
artwork store (`ArtworkStore.putFound`), never into the music file, and the
scanner reads it back after the file's own picture and before any sidecar,
so it lasts through rescans. A song with its own embedded picture is left
alone.

**The subtitle is capped at one line** in both `ClickableArtists` and
`TrackArtistAlbumLine` — the outer `FlowRow` too, not just the inner one. The
album segment is appended outside `ClickableArtists`, so capping only the inner
row still let "artist • album" wrap. With a fixed row height a second line is
clipped rather than shown, so the cap is what keeps the text intact.

**Holding a song row plays it for 25 s, and lifting the finger stops it.**
`SongPreview` (one app-wide player, separate from the main one) plays from the
song's remembered drop, else a third in, and the row fills left to right behind
its text, from the accent through amber to red as the time runs out
(`Modifier.songPreviewHold`, `previewFillColour`). Rows take it through
`LocalSongPreview`, given at the nav host the way `LocalPlayInTryptMania` is, so
no screen wires it by hand.

**A held song streams at 320 kbps.** `SongPreview` asks the resolver for
`AudioQuality.HIGH` (AAC on TIDAL and Apple, MP3 on Qobuz and Deezer): a
25 s preview of a song that may never be played should start in a moment
and cost little, not fetch a Hi-Res FLAC that the Qobuz cache fills from
the front. A song already in the Qobuz or Deezer cache whole, at any
quality, plays from disk instead; a file on the phone needs no stream at
all. The listener's quality setting is untouched for real plays.

- **The lift is watched, never consumed.** The watcher reads every event at
  `PointerEventPass.Initial` and leaves it for the row's tap and the list's
  scroll. Consuming the up would leave the row pressed and a scroll that started
  on a row dead. A held row that scrolls out of the list ends its preview, as
  no lift will ever reach it.
- **The main player is paused by audio focus, not by a call.** The preview asks
  for `AUDIOFOCUS_GAIN_TRANSIENT`; Media3 pauses the main player for it and
  resumes it when the preview gives the focus back. With "Play alongside other
  apps" the main player ignores focus and keeps playing under the preview.
- **The start is a `setMediaItem` position, not a clip.** A source that cannot
  seek (Apple's fMP4 over HTTP) plays from the top with a clip, and the
  front-filling Qobuz and Deezer caches start at 0:00 on purpose
  (`SongPreview.startMs`).
- **Hold no longer starts multi-select.** "Select" is in the ⋮ song menu of
  every list that has a selection. Left out on purpose: the queue (hold there
  drags), Trypt Mania's wheel (it previews by itself) and Discover's cards.

**Every song list has multi-select.** Begun from "Select" in a song's ⋮ menu;
then a tap picks or unpicks a row and the bar acts on all of them. The lists
over `UnifiedTrack` (the Local page's Songs and search, My server's songs,
search and albums, and the local album, artist and genre/year pages) share
`UnifiedTrackSelection` and `UnifiedTrackSelectionBar`, which keep the picked
songs themselves: the Local page's Songs is paged, and a picked row that
scrolls away is dropped from the page. A selection belongs to its list:
changing category, section or search, or leaving an album, ends it. In
selection mode a row shows its tick, hides ⋮ and its queue button, and its
artist and album links pick the row instead of opening the page. Search
results from the phone or the listener's server have a ⋮ too (the local
menu), so a selection can start there. Two lists start it differently: a
genre chart's row has no preview and no menu, so holding it picks it, and the
picked rows are found in the catalogue when the bar acts; an artist's
unreleased cards have no menu, so they join a selection begun from a top
song. Left out: the queue (its own selection), Trypt Mania's wheel, the
listening party's lists (Up next, and the add-song picker), the galaxy's
moons and the genre map's chart panel.

**Upload to Tryptify Cloud is on the selection bar** wherever the picked
songs include files on the phone (the Local lists, the local album, artist
and genre pages, folders, and search hits from the phone), and only those
files go up. A batch says it was queued once, then once when it is done
("Uploaded 12 songs"); a song that fails is told at once. There is no upload
to a listener's own music server: the Subsonic API has none.

`SongPreviewTest` holds the start point, the fade and the fill's colours.

### My server (Subsonic)

**My server is one Library page, with its own small stack inside it.** Index
(Albums · Artists · Songs) → artist → album, and Back pops that stack before it leaves
the page (`SubsonicLibraryTab`'s `BackHandler`, like Local's categories). It
pushes no nav routes: a server's ids are strings, and the catalogue album and
artist screens take Longs, so a pushed route would open the wrong page or none.
For the same reason a server song's "Go to album/artist" is hidden
(`CatalogNav`, `SourceType.SUBSONIC` beside `LIVE_RADIO`), and Search shows its
songs only, never its albums or artists.

**Songs and the search ask the server, they never filter on the phone.**
Songs is search3 with an empty query, a page of 500 at a time, fetched only
once the chip is opened; the list ends on an empty page (videos are dropped
from each page, so a short page is not the end) or one that adds nothing new
(a server that ignores `songOffset`). The search is the page's own
`SearchOverlay`, like Local's: songs only, asked again once typing pauses,
and Back closes it before it leaves the page. The three chips scroll sideways
and the buttons stay pinned right, because three chips and four buttons are
wider than a phone. A tap in a long list queues that song and the next 999,
not the whole server: the player maps every row of a queue on the tap.

**It is built from the Local page's lists**: `AlbumGrid`, `ArtistList`,
`SongList`. Material rows at `listRowHeight`, never glass, so the two pages read
as one library. `ArtistList` leaves out a song count of 0, since a server's
artist list counts albums only.

**Without a server it is a "set up" page, not an empty list**: one message and
a button to Settings › Connections. While the saved server is still being read
it draws nothing, so a connected listener never sees "not connected" flash.

**Artwork is `subsonic-cover://<id>`, never the getCoverArt URL.** That URL
carries the sign-in (in password mode, the password), and artwork leaves the
app: the media session, the widget's snapshot, Discord, party guests, the
synced history. `SubsonicCoverMapper` makes the URL inside this app's Coil, and
`StreamResolver` hands the notification the picture's bytes. A "simpler" fix
that puts the real URL in `artworkUri` is the bug this rule exists for.

**The setup guide stays folded** under the section, so Connections stays short
once a server is connected.

### Tryptify Cloud

**It starts folded under its title.** "Tryptify Cloud", with a ▾ beside it,
is the only line until it is tapped; the intro, the plans or the storage, and
the uploads open under it, and stay open while the screen lives. Three plan
cards made Connections long for everyone who came for something else. Folded,
it does not ask the server for the account; opening it, and coming back from
the payment page with it open, does.

**It is described as storage, never as hosting a server**: "your personal
Subsonic-API music storage, made for fast streaming". It sits right under the
music server section in Settings › Connections, because it plays through that
same Subsonic client.

**The cloud is played as the Subsonic server, not as a second library page.**
"Play it in Library › My server" asks the cloud for a device password and saves
the cloud the way a typed-in server is saved, so My server, Search and the
cover rule above apply unchanged. The app holds one Subsonic server, so this
replaces a connected Navidrome; the button says so, naming that server, rather
than doing it quietly.

**Prices, storage and terms come from the server's `/plans`**, never from the
app. The money rules live in `server/cloud/src/rules.ts`, and a price typed
into the app would drift from what the checkout charges. Sizes are decimal
gigabytes on both sides (`Formatter.formatShortFileSize`, `GB = 1e9`).

**Upload is a song-menu item for files on the phone only** (`LocalUploadToCloud`,
offered when the menu knows a `filePath`, like Find album art). Uploads run one
at a time in one WorkManager chain with one notification. A song that cannot be
sent still ends its worker successfully, reporting through `CloudUploads.last`,
because a failed worker would fail every song queued behind it.

**The account is re-read on resume.** Payment opens in the browser, and the
plan should be there when the listener comes back, with no reload button.

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

The album may repaint the menus only when the listener asks it to ("Dynamic
Colors › Tint the menus too"), and even then the ground moves **a quarter of the
way** toward the cover, never all of it. `customScheme` will build a light scheme
from a bright sleeve and a dark one from the next track, so a raw album ground
strobes the whole app between light and dark from song to song — the same class
of bug as the globe inverting. A quarter keeps the hue plainly visible and lets
the theme go on deciding polarity. The accent takes no such wash; an accent is
meant to be loud, and the contrast floors catch it wherever it lands.
`AlbumTintTest` sweeps the app's real grounds against a spread of covers and
asserts neither direction ever flips. Do not raise `ALBUM_GROUND_MIX` to make a
tint stronger.

Trypt Mania is the one exception, and on request: its accent follows the song
picked on its wheel (`AlbumTintScope`, see "### Trypt Mania"), the accent
only, by the same safe path. It does not add a second palette: while the mode
is open the app's one palette is built from the picked song
(`DynamicColorSource`).

The tint goes through `customScheme` rather than swapping slots the way
`DynamicColorScope` does. Slot-swapping works on the player, whose foregrounds
are hardcoded white, and falls apart across the menus, where the album accent
lands on surfaces the base theme derived from a different colour entirely.

### Theme Store pictures

**A store picture is drawn by the real renderers, never mocked up.**
`PackSnapshotFace` stacks the lyrics (`StudioPreview`), the player's transport
and dock (`PlayerChromePreview`) and the mini player and tab bar
(`UiPanelsPreview`) at a fixed 270 by 584 dp, under the pack's colours, font
and glass, with sample songs and sample lyrics only. Their `picture = true`
drops every label, hint and the panels' search bar: a picture that says
"PREVIEW · reactivity 40%" is a screenshot of the Studio, not a theme.

**It is recorded, not copied off the screen.** `SnapshotHost` draws the face
first in the screen's root, under the page's opaque backdrop, into a
`GraphicsLayer`, and reads it back with `toImageBitmap()`, which the GPU draws
on Android 9 and up, blur and glass shaders included. A PixelCopy of the
visible preview would catch the mini player, a sheet or the scroll position,
and the publish page is a lazy list whose preview is gone by the time Share is
reached. The host draws nothing between pictures.

**A picture never blocks a share.** The pack goes up first and its picture
after; a picture that fails leaves a shared pack whose card shows its colours
(`PackSwatch`). The author's own pack page offers "Take a new picture", and a
pack of theirs with none gets one the first time they open it.

**The store is two tiles to a row, each one `PressableGlass`** with the picture
inset at the top and the name, author, likes and downloads on the glass under
it. The picture is what a theme is chosen by, so it gets the room; part icons
stay on the pack's page.

**A card shows the top of the picture, the lyrics and the player, cut off
above the panels band** (`SNAPSHOT_CARD_ASPECT`, cropped from the top). The
whole 270 by 584 picture made two cards fill a screen, and the panels band's
coloured backdrop, drawn so its glass has something to bend, read as clutter
at card size. The pack's own page shows the picture whole. The list refreshes quietly on coming back to it
(`refreshQuietly`), so a download or a like counts on the card at once.

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

### The genre galaxy

**Drawn on Compose canvases, never OpenGL.** The map's panels are glass, and a
haze pane can only frost what is drawn in the window's own layers. A
`GLSurfaceView` or `SurfaceView` is not, so glass over one paints as a flat
slab. `GalaxyCamera` projects every point on the CPU, and `GenreGalaxyView`
draws in batches: one `drawPoints` per family and depth band, one `drawLines`
per family, and a sprite per star. Moving it to GL for speed breaks every panel
on the screen.

**Every size is in dp.** Star sprites, dust, strokes, tap reach and label gaps
are all multiplied by the density. The 2D map sized them in raw pixels, which is
why it came out a third of the size on a phone.

**The view's middle is the middle of what the panels leave visible**, not the
screen's. The top reserve is the measured title plus caption; the bottom is the
measured panel or HUD plus `LocalBottomChromeInset`, eased so the star slides
rather than jumps. Labels are never drawn inside either strip. A name under
glass is two pieces of text on top of each other.

**The genre panel is capped at a strip of map below the title**
(`MIN_MAP_STRIP`), and its sections' heights are measured between the title and
`LocalBottomChromeInset`, not across the whole screen. Its top row is outside
the scrolling part. Uncapped, the open Top 100 pushed the panel under the status
bar, and its close button opened the notification shade instead.

**Gesture handlers read the reserves through `rememberUpdatedState`.** Pointer
handlers outlive the composition that made them. Captured, a tap is hit-tested
against the view as it was before the panel opened, and lands on nothing.

**The sky is deep space in every theme, and the status bar icons are light
while the map is up.** This is deliberate, not a missed theme token. The map is
a window onto space, and the title, caption and labels use `GALAXY_INK` for that
reason.

**The travel blur is Android's own `BlurEffect`, only while gliding.** A
hand-rolled directional blur looked striped. A warp-speed effect was asked for
and then removed. Low-performance mode drops the blur, the god rays, the sky
shader, the twinkle and the turn, and uses less dust.

**The galaxy turns in the camera, not in the points.** `CameraFrame` sees the
scene from `yaw - spin`, so nothing has to move thousands of points per frame,
and the target, which is in the galaxy's own coordinates, follows a star with
no extra work. The sky uses the unturned basis (`s*`, and `SpaceSky`), so the
far stars hold still. Do not apply the spin to the sky. Do not apply it to the
target either, or a followed star slides off the centre. The turn is toward
smaller angles, so the arms trail. `GalaxyMathTest` pins all three.

**The galaxy's gas is AGSL on the canvas, never projectM on a GL surface.**
It follows MilkDrop's model: bass, mid and treble each read against the song's
own running average (`AudioBands`, 1 = usual), with `_att` smoothing. Only the
lift above 1 moves anything. It is drawn at a third of the resolution through
an offscreen layer scaled back up (`SMOKE_SCALE`). Putting it on a GL surface
to reuse the visualizer would make every glass pill over the map a flat slab.

**The black hole is drawn in halves.** The far half of the disk, then the
shadow, the lensed arc and the photon ring, then the near half. The split
runs along the disk's long axis on screen. Seen face-on there is nothing to
split, and the disk is drawn whole. Genres stay outside `DISK_OUTER` in both
layouts (`GalaxyMathTest`).

**The galaxy's look is the listener's wish, not an override.**
`GalaxyVisualSettings` defaults to the galaxy as it shipped. Every effect it
turns on is still ANDed with low-performance mode and reduced motion. Its
sheet takes the dock's place and the camera's bottom reserve. Sliders update
the map at once; the save waits for the hand to stop (`GALAXY_VISUALS_SAVE_MS`).

**A selected genre is a dock of pills, not a panel.** Up to three related
genres, by name, and four glyph pills (Play, Radio, Top 100, and its system —
three planets — which does what a long press on the star does), popping in.
The glyphs are cut out of the slab (`GlassPill`'s `punch`, the tab bar's
recipe: an offscreen slab, the glyph erased with anti-aliased `DstOut` at whole
pixels), never drawn on it, and only where `glassPunches()` says the shader
runs; a selected one is lit over its hole. The
Top 100 and the history are one compact sheet above them, one at a time.
There is no close button: a tap on empty space or Back deselects.

**The deep sky is one AGSL pass on Android 13 and up** (`SpaceShader.kt`), the
demo's far stars done procedurally. Each star stays inside the middle half of
its cube-face cell, which is what lets a pixel test one cell per layer. Let a
star or its halo reach a cell edge and it is cut in half. Below 13, or in
low-performance mode, the sky is the flat colour with drawn points.

**The galaxy is one offscreen layer while glass reads it** (`CompositingStrategy.Offscreen`
on its Box, `glassOver`). Every pane of glass over the map frosts it, and without
the layer each one made the render thread replay the whole galaxy: frames of 0.7
to 0.9 s. Keep it. Only in full screen with nothing open, once the chrome has
faded (`GLASS_FADE_MILLIS`), does it draw straight to the screen: nobody reads
the layer then, and the sky under it is opaque with everything over it adding,
so the pixels are the same.

**The deep sky has a layer of its own and watches only the camera's turn.**
`skyFrameFor` builds its view from yaw, pitch and the viewport, and its clock is
`skyTime`, the twinkle stepped at `SKY_TWINKLE_HZ`. That is why HWUI can lay
last frame's sky down instead of running the sky shader over every pixel again.
Read `time()`, `spin()`, the camera's target or its distance in that canvas and
it is redrawn every frame again, with nothing on screen to show for it. The gas
has its own clock too (`smokeTime`: every frame while the map is steered or the
music moves it, `SMOKE_IDLE_HZ` at rest), and reads the turn without watching it.

**A god-ray pixel takes only the samples within its light's `reach`.**
`LightSpot.reach` is what the shader's window is cut to, and the pass is skipped
when no pixel can come that close (`GalaxyLight.reachesView`). It covers the
glow, the shade's reach (`SHADE_REACH` glow radii: past it, under half a step of
8-bit alpha at the strongest shade) and everything drawn *in colour* in the pass —
the star's white-hot middle (`starEmitterPx`), the black hole's disk
(`holeLightExtent`, bounded by the disk's corners from any camera). Anything new
drawn in colour in a light pass has to be inside `reach`, or its light is cut
off where the window starts; black things may go anywhere. `GalaxyGpuPassesTest`
replays the shader with and without the window at the strongest settings and
holds the difference under half a colour step.

**The other stars' rays are one pass for all of them** (`GalaxyStarRays`, up to
`StarLights.MAX`). Every star's glow is drawn into one half-resolution light
pass and each pixel marches toward each light, from that light's first counted
sample (the same window as above) and breaking at the last. A pass per star
would be a full-screen layer per star; keep it one. The star with its own pass
(the one you are at) is left out of it, its planets stay in its own pass, and
only dust and bulge grains near a star in depth as well as on screen stand in
these glows. `pickStarLights` fades each star over the gap to the next in line;
a hard top-N cut would pop. Bloom (`GalaxyArt.bloom`) is the light passes'
glow, so it scales the light the rays carry, and ray length is the march's
falloff (`GalaxyLight.decayFor`), neither the reach: the window stays exact.

**Planet and moon size lives in the system, not the drawing**
(`PlanetSystem.withBodySize`, applied once in `GenreMapScreen`). Scaled there,
the moons' orbits widen with their planets, and the taps, names, shadows and the
zoom's near limit all read the same radius. Scaling only the drawn disc would
leave moons inside a big planet and the camera able to fly into it.

**The map's panels sit on the bar as it stands now** (`LocalBottomChromeNow`),
following its fold. `LocalBottomChromeInset` is the open bar's height on
purpose, for lists, and on a panel it left a tab bar of dead space under it
whenever the bar was folded.

**The smoke tests for the disc before its noise.** The density is at most the
disc's falloff times the amount, so the early return is the same cut the end of
the shader makes; keep it before the three fbm calls.

**At rest the map moves on every other vsync of a fast display** (`GalaxyPacer`):
60 frames a second on a 120 Hz screen, every vsync again the moment a finger is
down, and never paced at 90 Hz or less. Any touch on the screen counts (the root
`pointerInput`, in the Initial pass). An animation on the map that is not touch-
driven belongs in the clock's `animating` check, or it runs at half rate at rest.

**Lighter glass is the listener's switch, off by default** (`lightGlass`). On, the
map's panes and the bars over it run the live lens at half resolution
(`LocalLensDivisor`, the chrome through `AppChromeLens`), only where the blur is
wide enough (`REDUCED_LENS_MIN_BLUR_PX`); the clip, the slab and its rim stay at
full resolution. The shrink before the blur is not exact, which is why it is a
switch and not the default.

**Each map frame is reported in the debug log** (`GalaxyFrameStats`, tag
`GalaxyFx`): fps, frame, main thread, render thread and GPU times, late frames,
and how often each pass ran. Judge GPU work by those lines, before and after.

**No `PathEffect` on the map.** HWUI rasterises a dashed path on the CPU and
uploads it every frame. Dashes are plain segments from `Dasher`, laid on screen
only, the pattern carried across corners. The light passes run at half
resolution (`LIGHT_SCALE`, through `reducedLayer`, whose render effect works in
the layer's own pixels), and the links' dust is as dense as each link is long on
screen. None of it changes what is on screen; `GalaxyFastPathsTest` holds the
stand-ins (`fastSin`, `Dasher`) to the originals.

**Planets are fetched only after the camera stays on a genre** (`mapSystem`
waits 0.7 s), and without the MusicBrainz cross-check. Flying past a genre on
the way somewhere else must not cost a chart request.

**The god rays are an occlusion light pass, not the lyric engine** (`GalaxyLight`).
Each light — the core, or the star whose planets are up, one taking over from
the other as you arrive — draws its own layer: the light and its glow in colour,
and what stands in it — planets, moons, and the dust and bulge grains for the
fine rays — over it in black. Never the stars or the nebulae: a star in front of
another threw a broad black beam across the map. The march averages that, so every planet throws a shadow shaft
straight away from its star. The hole's shadow is *cleared*, not blacked out, or
it casts a dark ring over its own disk. Do not add stripes or a starburst to fake
rays: they come only from what is in the light's way.

**A pinch zooms harder than the fingers move, and starts on the spread**
(`GalaxyCamera.pinch`, `PINCH_GAIN`). Planets are a few hundredths of their
star, and at one to one reaching one took pinch after pinch. The gesture
starts when the fingers spread or close past the touch slop, not only when
their middle moves: counted on the middle alone, a clean pinch did nothing.
And a gesture that is mostly a pinch, while the camera follows a star or a
planet, zooms without panning: a pan lets go of what is followed, so the planet
you pinched toward orbited away. Arriving at a planet frames its third moon,
not its last, so the world is big in the view; arriving at a star frames its
planets, not the library's belt beyond them.

**Every star is lit, always**, at full size and brightness; there is no "still
dark" look for genres not listened to. **The star you are at is a sun** — the
selected one, else the followed one, else the one whose system the camera sits
in (`litStar`) — with its disc and its light as soon as you are near, not only
once its planets have come.

**The links' stardust moves through space, not round its link.** Comets run at
`FLOW_UNITS_S` scene units a second and the dust drifts at `FLOW_DRIFT_UNITS_S`:
timed per link, a comet crossed a long link in seconds and raced across the
screen close in.

**A star system is at real scale, more or less.** The star's radius is the
genre's size (`GalaxyScene.starRadius`, from its prominence), and everything is
laid out from it: a planet is 4–12 % of its star across, sized by its artist's
catalogue (MusicBrainz release groups, log scale, `PlanetSystem.catalogShare`);
its orbit is how hot the artist is this week (the 7-day chart's listens), the
hottest nearest; moons are a fraction of their planet. The star is drawn as a
sun disc at its true size once it has one (`drawSun`). Planets are specks from
the system's own distance, and the camera's `MIN_DISTANCE` is small so you can
fly to one; `nearLimit` keeps a pinch outside whatever it follows. Do not put
the old 5 dp floor back on planets: it made them bigger than their star.

**Moon orbits are laid out for the biggest planet the star could have**, and
planet sizes ease in (`SystemArt.ease`). A catalogue count arrives seconds after
the system; it must grow the planet, never move its moons or its orbit.
`PlanetSystemTest` pins both, and that neighbouring planets' moons never cross.

**Full screen hides everything but the map.** Turning the phone sideways enters
it and turning it back leaves it; the bar's button enters it upright and Back
leaves. The map asks for `SCREEN_ORIENTATION_SENSOR` while it is up and restores
the app's own rule on the way out. The nav host's chrome steps aside through
`HideAppChrome`, the system bars through `SystemBarsHidden`. A long press on a
star plays it and opens its system as a list (`GalaxySystemSheet`); that sheet
is the only thing shown in full screen.

**Nothing on the map runs while nobody can see it.** The clock loop stops when
the screen is not started (the app in the background, the phone off, another
screen in front), which stops every shader with it, and the spectrum tap is
released. Work on its own thread or timer elsewhere — the ambient projectM
render thread, the FFT taps, the loudness meter, the tilt sensor, projectM's
audio feed — is held with `LifecycleStartEffect` or `rememberOnScreen()`, never
a bare `DisposableEffect`, which holds it in the background too.

### The library galaxy

The music on the device as a sky: artists as stars, albums as planets, tracks
as moons. How it is built, and why, is `docs/local-galaxy-architecture.md`.
Everything under "The genre galaxy" above holds for it too.

**The two galaxies are one stage** (`GalaxyStage`). The camera, the clock, the
glides, full screen, the glass and the look sheet are the stage's; each map
brings only its stars (`StarField`), its system and its panels through slots.
Do not fork the stage for one map: a fix to one is a fix to both, and the rules
above were each paid for once.

**The genre galaxy draws from its stream in the order it always has**
(`GenreStarField`). Its stars and the scene's dust share one seeded `Random`.
Move a `gauss()` call, or draw a new number before the layout, and every dust
grain and nebula moves, which looks like nothing until you compare. The split
of `GalaxyScene` was proven against a fingerprint of every number in the scene.

**Every library star is placed from its own key, never from a shared stream**
(`LibraryStarField`). A library grows; the genre dataset does not. Seeded down
the list, one new artist reshuffled the whole sky after every scan.
`LibraryStarFieldTest` adds artists and checks no other star moved. (The dust
is dealt out again on a change: it is decoration, not a place.)

**An artist whose genre is not recognised is in the halo, not guessed at.**
`GenreGraph.resolve` returns null rather than guessing, and the galaxy keeps
that honesty: such a star floats above or below the disc, and the caption says why.

**Orbits are release order; plays are glow.** The debut orbits nearest. A play
never moves a planet: it brightens its atmosphere (`Planet.glow`). A system that
rearranged itself as you listened could not be learned. `LocalSystemsTest` pins
both.

**A library planet is its album's sound; a chart planet is untouched by it.**
Its size is the album's running time (full at 80 minutes), its colour its key
round the Camelot wheel, deeper for minor and paler for major, its haze how loud
it was mastered, its cover's turn one per 32 beats of its tempo, and its night
side as deep as the dynamic range the master kept. Every one has a default that
is the old look exactly (tone 0, haze 0, no spin, shade 1), which is all a chart
planet ever has: `PlanetSystemTest` pins chart planets at every size and
`PlanetLookTest` pins each default to the old numbers. An album missing a value
keeps the old look for that one thing; nothing is guessed. Only the cover turns:
the light and shadow masks stay turned toward the star, or the planet's lit side
would spin with it. All of it is drawn with what each body was already drawn
with, and the atmosphere colours are made once a system (`SystemArt.atmosphere`),
not per planet per frame.

A library moon is its track the same way: it goes round once every 16 beats of
the track's tempo (outer slots slower, as before), and its glow is the track's
key. Without a tempo it keeps its slot's pace, without a key its star's light,
which is all a chart moon has; `PlanetSystemTest` pins chart moons too.

**Nothing in the library is unreachable on the map.** Albums past the eighth are
the belt and tracks past an album's sixth are its ring, and both are listed in
full in the system's sheet. Drawn as grains, never dropped.

**A library system keeps its planets' moons wholly apart** (`SystemLimits.apartMoons`),
and its reach includes the belt (`PlanetSystem.reachFor`), so the camera frames
all of it before it arrives. The genre map's spacing is unchanged.

**Past 800 stars, the rest are points until you come near**
(`GalaxyScene.STAR_SPRITE_BUDGET`, `drawStars`). The genre map has 771 and is
under it, so it draws as it always did. Past it, each extra star's sprite fades
in over its point as it grows on screen. Do not make it a hard cut: a hard top-N
pops as the camera moves.

**Nothing on it comes off the network.** `LocalGalaxyRepository` reads the
library and the play history only, so the map works in airplane mode. A lookup
added for a nicer planet breaks that.

**The data layer returns records, not stars.** The repository hands back
`LibraryArtist`/`LibraryAlbum` (domain); the ViewModel lays them out. The
`data` package imports nothing from `ui`.

**Switching maps swaps the map, and keeps the view** (the title bar's "Your
library galaxy" on the genre map, "Genre galaxy" on the library map;
`switchSafe`, `GalaxyHandoff`). Both maps are laid out in one frame, so the camera and the
layout are handed over and the other map opens on the same part of the sky.
The map being left comes off the back stack; otherwise switching back and forth
stacked maps up behind Back. The handoff is taken once, and a map opened any
other way frames the whole galaxy.

**The library map is full bleed**, like the genre map (`fullBleedRoute`). Its
panels clear the bars themselves.

**Both galaxies' controls are pills, not a panel** (`GalaxyControls.kt`, shared,
so the two maps cannot drift apart). With nothing selected, the bottom is two
glass pills, Galaxy and Timeline, the one showing lit; Surprise is in the title
bar. A panel there covered a third of the sky to hold two choices. What it also
said moved into the caption: on the genre map, how many genres are explored and,
once any are kept, what the dashed ring means.

**Both galaxies search with the app's search bar, under the title**
(`GalaxySearchBar`, in `GalaxyStage`'s `underTitle`): `GlassSearchBar` over the
map, frosting it, with the matching stars as a row of pills inside the same
pane, the globe's arrangement, so the sky stays in view. Genres match on their
other names too ("dnb"). It lives in the measured top chrome, so its height is
in the camera's top reserve and no star's name is drawn under it. Back closes
it first.

**It opens from a tile under Folders** in the local Library tab
(`LibraryIndexList`'s `onGalaxy`), drawn like the categories but not one of
them: the categories open in place, this opens a screen.

### Precision AutoEQ filter colours

**A filter's dot on the graph is its chip's colour.** Precision AutoEQ
(`EqualizerScreen`) passes `bandDotColor = { studioFilterColor(it.id) }` to
`FrequencyResponseGraph`, the same colour as the dot on its filter chip and in
the inspector, so dot 4 and chip 4 read as one thing. The graph's own
fallback (green boosts, red cuts, grey flat) stays for the screens with no
chips: Parametric EQ, car mode, the import sheet. Dropping the parameter makes
the dots disagree with the chips under them.

### Stats charts

**Bars are glass, and the glass is flat colour.** `glassBar` in `StatsVisuals.kt`
draws each bar as layers that are each one colour: a translucent tint of the
theme's primary (0.38, or 0.82 when selected), a white rim, a bright inner top
edge, and a highlight streak on bars at least 16dp wide. No gradients, anywhere
on the stats pages: the owner asked for the theme or dynamic colour and nothing
else. A gradient fill looks like an easy upgrade, and it is the thing that was
asked to go.

Bars are drawn on the canvas, not as a `GlassPanel` each. The clock grid is 168
cells, and a shader pane per cell spends the frame on squares a few dp wide. The
cards around the charts are real `GlassPanel`s over the page's own haze source,
the same pattern as Discover.

When `disableLiquidGlass` is on, the bars drop to plain solid tint (0.6, or 1
when selected), the same switch that flattens the rest of the glass. A chart
that keeps its rims with glass off is out of step with every pane next to it.

Stats and Achievements are **two pages under one glass pill**, not two tabs of
a Material `TabRow`; the pill is a pair of `GlassChoiceChip`s, as on the chart
page's view and compare switches.

**An achievement completed shows once, as a glass pill from the top**
(`AchievementUnlockedPill`): its icon in a gold ring, "Achievement unlocked",
its name and View, which opens Stats on Achievements. Chrome, so the mini
player's material and a sibling of the app's haze source, as the DAC popup is.
`AchievementWatcher` is the only place an achievement is stamped, the Stats
screen reading its states, so none is stamped without its pill; the first check
after the pill arrived stamps what was already done quietly. Gold is for the
ring and the icon only: as text it does not read on light glass.

**Trypt Mania has a card of its own on the Stats page** (`TryptManiaCard`),
after Quality, and also under "no plays" when the range is empty: it counts
all time, from the saved runs (`TryptManiaStats`, read once by
`AchievementWatcher`), not the range. Whole songs only, and runs compare by
grade or percent, never score: score grows with the combo, so an older run's
score reads as worse play.

**Night is one window, 9 pm to 5 am** (`NIGHT_START_HOUR`, `NIGHT_END_HOUR`):
the Night owl listening type, the night share, the clock chart's Night row
and, for its after-midnight part, the Night Owl achievement. A stat with its
own hours says a different thing under the same word.

**A song opened from another app ("Open with") plays at once and is offered,
not added** (`OpenedFilePill`): a glass pill from the top, the same chrome as
the achievement pill, with "Opened from another app", the title and Add to
library; a file that could not be opened says why on the same pill. The song
plays from a copy in the cache and stays out of the library until Add (on the
pill, or in Now Playing's ⋮ menu while it plays) copies it to Music/Tryptify.
A file that is already a library song plays as that song, with no pill.
Never route an unknown VIEW link to the sign-in callback: `MainActivity` hands
content and file links to `OpenedAudioRepository`, and only
`tf.monotrypt.android` links to Supabase.

### Trypt Mania

**The playfield is the mode's own; the menus are the app's.** `GlyphTheme`'s
near-black palette and the monospaced `GlyphTypography` belong to the lanes,
the HUD over them, the beat and lane colours and the judgement accents, and to
readouts that change while they are read. The music wheel, the selection,
generation and pause panels, Training Ground's controls and the results follow
the theme: a top bar with Back and `SearchAction`, `SearchOverlay`, wheel items
of `materialSurface` at `MonoDimens.listRowHeight`, `GlassPanel`s, and buttons and
chips from `GlassPill` and `GlassChoiceChip`. Text on a pane takes the glass's
ink from `MaterialTheme`, never `GlyphTheme.Paper`: a fixed dark menu was the
only dark screen in a light theme, and fixed white text on the app's glass is
the wrong ink wherever the glass is light. An accent written as text on the
page goes through `menuAccent`, which keeps its hue and moves it until it reads.

**While the mode is open, the app's colours are the picked song's.** The
wheel picks a song the player is not playing (previews play through the
game's own player), and when the mode followed the picked song while the rest
followed the playing track, two covers coloured one screen: the tab bar and,
with "Tint the menus too", the ground from one, the accent from the other.
There is one palette: `GlyphRoute` makes the selected song's cover
(`gameplayCoverUri`) the app's colour source (`OverrideDynamicColorSource`,
read in `MainActivity` through `DynamicColorSource`) for as long as the mode
is open, and the playing track takes over again when it closes. The mode's
screens take that palette's accent through `AlbumTintScope` (`tintedByAlbum`,
never a slot swap, so the menus keep their contrast floors; the ground is the
theme's, or the menus tint's when that is on). The fade is the listener's
blend capped at `GLYPH_COLOR_BLEND_MAX_MS` (0.5 s): songs are skipped through
on the wheel, and at longer blends a colour was still arriving two picks
later. The gameplay background's cover dissolves at the same capped speed
(`glyphBlendMillis`). The playfield's own palette is untouched.

**The home panels' haze source is the page in every state**, the loading and
empty messages included, not the list alone. While the list was the source,
the generation panel over a library still loading had nothing drawn to frost
and came out a solid slab. The selection pills sit on `LocalBottomChromeNow`
and add no navigation-bar inset: the nav host already stops a pushed screen
above the system bar, and counting it again floated them a bar's height above
the tab bar.

**The songs are on StepMania's music wheel, in folders, not in a list.**
`GlyphMusicWheel` over `GlyphWheel`: the folders are what the sort makes them
(Pack, the default, is a folder per imported pack and one for the library), one
folder is open at a time with its songs after it, and the wheel wraps once it is
longer than it is tall. A lone folder is open already, and a search is one open
folder of its results, so neither costs a tap to get past a heading. The flat
list it replaced was the navigation complaint: hundreds of pack songs in one
column, with nothing to jump between.

* The centred song's folder is open, always: that is where the song is. The
  open folder is derived from the centred song, not only stored. Stored alone,
  a song that changed folder — a chart made under the Mapped sort, a search
  closed, a pack import landing beside a lone library — was left behind a
  closed folder, with its panel and preview gone.
* The centred song is chosen after the wheel rests on it for
  `SAMPLE_MUSIC_DELAY_MS` (150 ms; StepMania's `SampleMusicDelay` is 250, which
  with a half-second fade read as a preview slow to start), not as it passes; a
  folder stops the preview on the same delay, and resting again on the chosen
  song starts its preview again. The delayed choice is cancelled when the
  screen or the app is left: run out behind the app, it started a looping
  preview there.
* A preview fades in over 120 ms, and the player is built when the song list
  opens, not on the first song. A song's first preview waits 700 ms for its
  drop (`DropFinder` reads the whole song's loudness), then starts a third of
  the way in; the drop found after is the next visit's start.
* The picture behind the wheel follows the preview (`GlyphSongBackdrop`, fed
  `GlyphUiState.backdrop`): the song's chart background (`#BACKGROUND`), else
  its artwork, else nothing: a song with no picture leaves the page plain, with
  no scrim. (An orange and purple ranking grid stood in for one, and was not
  liked.) A pack song's artwork is its own picture, else the cover inside its
  music, taken out once and kept (`GlyphPackLibrary`, `SongArt`): a chart made
  in the app names no picture, so a song charted here came back from the
  player's own pack with none. The own pack now writes the cover beside each
  chart as its jacket and names it (`#JACKET`). It comes up as the preview
  starts, over `GlyphPreviewPlayer.FADE_IN_MILLIS`, and goes as a preview
  stops, over `STOP_FADE_MILLIS`; a folder that stops the music takes it too.
  With previews off it follows the choice instead. It waits for its decode
  before fading up, so a slow picture never pops in after its fade.
* The picture is inside the panels' haze source, under the wheel, with a scrim
  of the page's own colour over it so the rows read in any theme. The grid is
  still: anything moving under the frosted glass makes it re-blur every frame.
* The centre is reported by the item, not by its place: a new list with a
  different song at the same place is a new centre. Reported by place, the
  panel kept offering Play for the song that used to be there.
* A tap on the centre item opens it: a folder opens or closes, the chosen song
  goes to its options. A tap on a song away from the centre chooses it now.
* The selection panel is the centred song's and shows only once that song is
  the chosen one. A panel left up while the wheel turned past offered Play for a
  song no longer in the middle; a panel fading out takes no presses for the same
  reason.
* The wheel runs under the search bar and the panel's glass, and its centre
  moves to the middle of what they leave. The panel's room is kept while a song
  is chosen, whatever is centred: sized to the centred item, the wheel rose and
  fell by half a panel at every folder heading.
* It turns through `Modifier.scrollable`, like a list: presses wait while a
  drag may be starting (no flash on every drag), and the tab bar folds as it
  turns. A raw drag detector did neither, which left the mini player unreachable
  with "Hide mini player when the nav bar shows" on. Items press with
  `bounceClick`, never a ripple.
* Up and Down turn it; Left and Right are left to focus, so a D-pad can reach
  the panel and the top bar. The items are not keyboard stops: the wheel is.
* The items move in `graphicsLayer` from one float position, and the glass's
  insets are read there too; a frame of a turn recomposes nothing.
* It swishes as the player turns it (`GlyphWheelSound`, Settings › More ›
  Wheel sound), never when it is set to a new place by a sort, a search or a
  folder. The swish and the metronome are `USAGE_GAME`, on the media volume:
  as sonification they went with the system stream, which a phone on silent
  mutes, and the wheel was silent for anyone with their ringer off.
* The selection is free pills of glass over the wheel, with no pane around
  them: the difficulties on a line of their own, Train and Play under them.
  The card says the song's name, tempo, notes, best grade and why a song has
  no chart; a panel that said them again took a third of the screen, a pane
  around the pills read as a slab, and one row squeezed the difficulties until
  one was cut in half. The pills frost the wheel's haze source, their sibling.
* Trypt Mania keeps the tab bar and hides the mini player (`HideMiniPlayer`)
  for the whole route; the bottom insets follow, so the wheel and the pills
  take its place.

**The card over the wheel reads the chart; it never grades it.**
`GlyphSongInfoCard`: DDR's Groove Radar, the BPM meter, the meter in stars and a
density graph, from `GlyphChartStats`. The radar is StepMania's own
`CalculateRadarValues` over the song's length — rolls are not freeze, every
off-grid row is chaos, voltage is a rolling eight beats — and `GlyphWheelTest`
holds each axis to hand-worked charts. Divided by the wrong constants, Freeze and
Chaos read four times high and filled their axes on almost every chart. The
difficulty number is the chart's own meter; nothing predicts one, and as text it
goes through `menuAccent`. Every value moves to the next as the wheel turns or the
difficulty changes, and snaps under reduced motion. The beat light follows the
playing preview's beat and is still when nothing plays. The tempo shown is the
song's `#DISPLAYBPM` when it has one (`???` for `*`), as the list and the BPM
sort use: the timing's range showed a gimmick's one beat at 1500 as the song's
tempo. Play keeps the real timing.

The card is a `GlassPanel` floating over the top of the wheel, with the chart
progress and any error under it in one stack below the search bar: the wheel
runs the full height, its rows travel up beneath the card and read through its
glass, and its centre is the middle of what the stack leaves. Laid above the
wheel as a plain card it had nothing behind it to be glass over. Opening the
search puts the bar at the top and slides the stack below it; the wheel is
never padded, only inset, so nothing clips at a line. The card is frosted as
the player's sheet is (`GlassPanel(frosted = true)`: the whole of "Backdrop
blur" and its tint, not the live lens's fifth and clear glass), so the rows and
the song's picture under it are soft colour rather than text bent at its rim.
The radar is drawn in a well sunk into the card (`glassWell`: darker than the
glass, its far wall's shadow inside its top, its near lip lit), as one with
it; it used to sit on a second slab of glass over the card, a pane on a pane.
The density graph has a shallower well of its own. The meter is twelve stars
cut through the card (`StarHoles`, the slab's `punch` with `punchThrough`):
the cut goes through the frost as well as the slab, so an unlit star is a real
hole with the page behind it sharp, the shader bevelling its edge. A lit star
is light coming up through its hole, white at its heart and blooming onto the
glass round it, and they light one after another as the meter changes. The
holes are placed from the star row's position on the card, read when the slab
draws; where the shader cannot run (`glassPunches` false) the stars are drawn
as holes instead. The card takes the height the screen can spare:
whole when it is 720 dp or taller, without the radar and graph from 600 dp, and
gone below that (a phone on its side), where the wheel and the panel's Play
need the room. Whole at any height, it pushed Play off a landscape screen.

**The results lead with a ring, then DDR's evaluation, then timing.**
`GlyphEvaluationPanel`: the run's judgements as one ring, each arc its share
of the steps, around a glass disc with the grade and percent; then the
judgement lines, sliding in and counting one after another. A tap while they
count shows them all; after, a tap on a judgement lights its arc and puts its
count in the middle. The timing card draws a normal curve from the run's mean
and spread — two numbers, so it says it is a curve of them, not of the hits —
and offers a correction only from the recent runs' agreed lateness
(`ApplyRecentTiming`), never from one run. Every count keeps its spoken
"Label: value", and everything lands at once under reduced motion. The page paints
the theme's ground under itself, as the start menu does: it fades in over the
play, the cover and its glow, and none of that may show between its cards.

**The play is over the player's background, and only the play.**
`GlyphPlayerBackground`, under the playfield inside the TV shader (and the fail
shake): the player's own layers from the player's own composables, in its
order: the album-coloured wash (`dynamicPlayerBackground`), the cover blurred
and darkened (`PlayerBlurredArtBackground`) and the glow (`DynamicAlbumGlow`).
One look in two places, never a copy that drifts. The picture is the song's
album art, as the player shows it, and the chart's own background only for a
song with no art (`gameplayCoverUri`). Its colours follow the player's rule, the
Dynamic Colors switch and the player's own together, read from the player's
preferences (`GlyphViewModel.backdropLook`), never a second switch in the game;
with either off they are the theme accent. Under the legacy player the cover is
left out, as that player leaves it out: a full-screen blur under a playfield
that redraws every frame is the cost low-end phones are spared. It holds still:
the arrows are what moves. The ambient visualizer is not drawn here even when
it is the player's background: its engine runs one view at a time (see "The
visualizer has two compositions"), and the game's music plays through its own
transport, which feeds it nothing. The arcade pad, speakers and lights that
stood here before (`GlyphDanceStage`) were removed on request.

**The lanes have no grounds.** The playfield draws its notes, its receptors and
the receptor line over whatever is behind it, and no lane of its own: the
grounds it shaded on every other lane cut the background behind into strips. The
game, Training Ground and the previews alike; the map editor's grid keeps its
own, being a grid to place notes on.

**A held hold is cut at the receptor, with its head on it.** Both engines pin a
held body's top to the receptor for the whole hold, pressed early or late
(`fillVisible`; `GlyphEngineWindowTest` and the native test hold the same
case). The pin used to apply only before the head's moment, so after it the
body slid on past the receptor. The playfield draws a hit hold's head at the
body's top, on the receptor while held and carried on with the body once let
go; hidden, the body's raw end showed under the receptor. The body's short last
tile is its art cut to fit, never the whole tile squeezed into the gap.

**The run moves at its edges: in, on each step, and out on a fail.** While
READY shows, the HUD drops in from the top, the field rises into place and the
life bar fills to its start (`INTRO_IN_MILLIS`); a restart, with no READY,
starts in place. The percent rolls to each new value; its spoken label is the
real one. The combo pops on each step it gains and harder at every fiftieth,
and a broken combo falls away in red instead of blinking out. The judgement
word itself stays still: it is read at a glance. When the life bar empties the
whole game picture shakes, hardest first (`SHAKE_MILLIS`); on Immediate the
music stops, the field sinks under a red veil, FAILED lands on it (held still,
outside the shake, so it can be read) and the results follow
(`FAIL_HOLD_MILLIS`). Pause does nothing then: Resume would only restart the
music under FAILED. Nor do the lanes: a step under FAILED was still judged at
the stopped music, and the results count the engine's judgements. Imm. Continue shakes and plays on. Leaving or restarting
cancels the hold. Reduced motion keeps all of it still: the intro, roll, pop,
fall and shake are skipped, and FAILED shows at once.

**The TV shader is over the game, never over a menu.** `glyphTvShader`, off by
default, goes on the gameplay picture (the playfield, the judgements and the
HUD), Training Ground's playfield, the options' preview and the Graphics tab's
preview of the demo chart, and nowhere else. The pause menu is drawn over it,
outside it, in the app's own glass. Android 13 and later; off where the app
turns its blur off (a low-end phone, or liquid glass removed), since it is a
full-screen pass every frame, and the setting says so; a shader the driver will
not compile leaves the screen plain.

**Except when the player asks for it everywhere.** Settings › Appearance ›
CRT effect, the tab's last setting and off by default, draws the whole app
through one of the same looks (`Modifier.tvShader` on the activity's root
surface, the CRT unless another look is picked), with the same limits, and
says when this phone cannot run it. Pop-ups that open their own window (a
dropdown, a system dialog) are outside it. Under it the game's own
(`glyphTvShader`, `LocalAppTvShader`) is left off: two would draw every
scanline twice. Under Look it has every one of the game's sliders, its own
copy (`appTvTuning`, one line in the settings store, synced), and the app is
their preview: a drag moves the whole app live (`AppTvLive`, read in the
root layer's draw) and the store is written once, on release.

**Chromatic shift and the two curves are the tube's glass, over any look.** A
second pass chained after the pattern (`TUBE_SHADER`), so the scanlines and the
mask bow with the picture, as on a tube; it runs only while one is above 0, and
all start at 0. Each curve is an angle, 0 to 40°: "Curve, top & bottom" bows
those edges and "Curve, sides" the left and right, each `1/cos θ − 1` of the CRT
shader's warp (about 16° and 14° are its classic 1/24 and 1/32), so a flat-sided
or a round tube can be had. The middle keeps its size, the edges bow outward,
the corners go dark. A line saved with the one Curvature slider of before is
read as the two angles that draw the same tube. Chromatic shift reads red and
blue up to 3 dp either side of green.

**Zoom is the tube's picture-size knob, scaled after the bow** (`zoom`, 60% to
120%, 100% by default; `uZoom` in `TUBE_SHADER`, `zoomScale` in
`tubeSource`). The glass keeps its curve and the picture inside it grows or
shrinks: under 100% the whole bowed edge shows inside the screen with the frame
round it, over 100% the picture fills the corners a curve leaves dark. It is
not Bezel, which shrinks the picture before the bow, as if the tube stood
further off. At 100% the shader's sum is unchanged, and a line saved before
Zoom reads as 100%, never 0; the built-in looks leave it at 100%, so picking
one never resizes the picture. Zoom-in stops at 120%, enough to fill a
classic curve's corners: past that the app's own edges, its tab bar first,
leave the screen. Off 100% it counts as reshaping, so taps follow it, and like
the curves and tilts it cannot be linked to the music.

**CRT presets are one panel, in every place the CRT is set.**
`CrtPresetsPanel` sits in Settings › Appearance › CRT effect, the Lyrics FX
tab and Trypt Mania's Graphics, all reading one store (`CrtPresetStore`), so
a look saved in one is offered in the others. The built-ins come first:
Default, then Arcade, Trinitron, Living room and VHS; then the user's own. A chip is
picked only when the look on screen is exactly it; a picked one of the
user's can be deleted. Picking one sets the look and every slider together,
and turns the effect on. Save names the look on screen; Export writes it to
a `.crt.json` file (type `tryptify-crt-preset`, the sliders in
`GlyphTvTuning.encode`'s line); Import keeps the file's look with the
user's and applies it, every slider held to its range. The built-ins' line
counts follow the screen's height, since a TV pixel is a whole number of
the phone's own.

**The CRT effect has a default look of its own** (`CrtPresets.DEFAULT`,
the owner's tuning: the CRT look at a third of its strength, a full mask,
bowed glass with round corners, a thin frame, a little glare). Until a
slider is moved the app-wide effect is that look, and its Reset and every
per-slider reset go back to it. The effect itself stays off until switched
on. Trypt Mania's game TV keeps the shader's own numbers as its defaults;
the Default chip is there too.

**Every CRT slider has its own reset** (`SliderReset`): beside its label
or reading, always there so the row never jumps, dimmed and inert while
the slider is already at its default. It puts back that slider alone; the
look's Reset button still puts back all of them.

**The CRT effect draws over the whole app or the lyrics only** (Settings ›
Appearance › CRT effect › Draw over, `app_tv_target`, the app by default). Over
the lyrics it is handed down as `LocalLyricsTv` and put on the synced and the
unsynced lyric surfaces (`lyricsTv`), with the same look and sliders; a bowed
tube's corners are clear there rather than black, and taps are not bent.

**The lyrics are bent once.** While the CRT effect draws over the whole app
with a curve (`LocalAppTube`, `appTubeCurved`), the lyrics' own curve is left
flat in the player and in the Studio's preview: the tube already bows them with
every other screen, and both was a bowl inside a bowl. The angles are kept, and
Lyrics FX says why they do nothing under them. The modifier stays in place at
0° (see below), so nothing is inserted into the letters' copy when the CRT's
curve comes and goes.

**The Lyrics FX Studio's Lyrics tab ends with the CRT effect's controls**
(`CrtEffectSetting`, `CrtEffectControls.kt`): the same switch, look, Draw over,
sliders, Old TV look and Reset as Settings › Appearance, over the same three
preferences (`CrtEffectPrefs`, which both view models implement), so either
place sets the one effect.

**Nothing appears or disappears inside the letters' copy while the lyrics are
up.** `captureLetters` records the lyric surface into a layer that both the
glass layer outward of it and the backdrop draw. When the curvature slider left
0, the curve's graphicsLayer was inserted right inside the copy, and Compose
handed it the glass layer (which already held the copy) without re-recording
it: the copy and that layer each drew the other, and the render thread walked
the loop until its stack ran out (four native crashes, nothing in the trace
but `prepareTreeImpl`). So `lyricCurvature`'s layer is always there on Android
13+ (flat, it sets no effect), and `captureLetters` ends with a pass-through
layout node (`CopyBoundary`), so any layer toggled further in gets a fresh
one. The Studio notes its tab and, while it is up, the preview's settings in
the crash breadcrumbs.

**A glass lens never reads a source it sits inside.** `liveGlassLens` draws
every Haze source's recorded content into its backdrop; a pane inside one of
those sources (the Stats screens put their panels inside the page they frost)
would record itself into itself, and the render thread walked that loop until
its stack ran out: a native crash with nothing in the trace but
`prepareTreeImpl`, seen on device. It skips a source while Haze's
`contentDrawing` says that source is recording, as Haze's own blur does
(`HazeContentDrawing`, kept through R8 by name). A pane's own layer
re-records apart from its source's, when the flag is down, so a source caught
once is left out for good. A native crash's report names the screen and the
CRT setting the app was on (`CrashBreadcrumbs`): a render-thread tombstone
names neither.

**The tube can be seen from an angle, in a frame: an old TV filmed from beside
it.** Tilt, sideways and Tilt, up & down (±30°) turn the picture and look at it
from 3.5 half-sides away, so the nearer side stands taller; the turned picture
is fitted and centred on the screen (`GlyphTvTuning.tubeView`), and Bezel draws
it smaller inside a dark grey-green frame (clear over the lyrics). Rounded
corners, Vignette and Glass glare (a sheen and a streak on the frame) finish
it. All start at 0, so a flat, square, unframed picture is unchanged, and taps
under the app-wide effect go through the same view (`tubeSource`, which the
tube shader's sum mirrors). Old TV look sets CRT-Royale and these, after the
TRYPT CORE videos (`GlyphTvTuning.oldTv`).

**CRT-Royale is its passes folded into one** (`ROYALE_SHADER`, its own program,
from crt-royale, GPL-2.0-or-later). The beams are its sampled generalized
Gaussians, three lines a pixel, widening and flattening as they brighten; the
mask is drawn (aperture grille, slot mask, its default, or shadow mask) instead
of read from its textures, and amplified by its mean so the light is kept; its
"fake bloom" lets bright parts glow through the mask; halation and diffusion are
a 13-tap blur; its border darkens the rim. Every slider's default is the
original's value (`GlyphTvTuningTest`). Along a line it reads the phone's own
pixels, not TV pixels: a CRT's line has none, and the app's letters stay sharp.
Its lines start at the original's 480 down the screen, its triads at 2.5 dp.
Its arithmetic was checked against a numpy copy on a player screenshot, and each
program is compiled as GLSL before a build; neither replaces seeing it on a
phone. It is the one look named after its shader: it is known by that name and
was asked for by it. Under the app-wide effect a bowed picture would put every
button somewhere other than where it shows, so `MainActivity.dispatchTouchEvent`
sends each touch (and its history) through the same warp
(`GlyphTvTuning.tubeSource`, `bentThroughTube`): a tap lands on what is seen
under the finger.

* Every pattern is counted in TV pixels of a whole number of phone pixels
  (`GlyphTvTuning.pixelSize`), never a fraction. The old line grid was 640 x 480
  stretched over the window, 5.775 rows a line on a 2772-row screen: lines of
  five and six rows, beating over nine lines as moiré. A grid on whole pixels
  repeats every line, and a smaller layer draws the same pixel as the game.
* The CRT starts at the phone's own pixels, and its Pixel size slider lowers
  the density from there. At one and two pixels its scanlines have no gap to
  draw, and the slider's note says they show from three. The masks start at a
  monitor's pixel (1.6 dp), where a one-pixel line is still visible; Wave at
  the original's 480 lines, rounded to whole rows. Each look keeps its own size.
* A TV pixel the layer's edge cuts is read inside the layer, never as black:
  the screen has no dark rim, whatever the size.
* The sliders are the shader's own constants, not effects laid over it.
  Strength mixes the plain picture back in; Brightness gives back what the
  pattern takes. Scanlines, Mask, Glow and Sharpness are the CRT's ScanBrightness,
  mask levels, BloomAmount and FilterCRTAmount, and their defaults are the
  original's values exactly (`GlyphTvTuningTest`).
* The settings name each look by what it does, never by where its arithmetic
  came from, CRT-Royale apart (below).

**The mode opens on its menu.** `GlyphScreen.MENU`: Play and the map editor
both lead to the song list, Settings to its own page, and Back from the song
list returns to the menu rather than leaving the mode. The leaderboard returns
to whichever of the two opened it.

**The menu's three small pills put the icon over the name, and the name
shrinks before it is cut.** Map editor, Packs and Settings share one row, a
third of the width each. Laid out as icon-then-name with `maxLines = 1`, a
wide font (or Width over 100%) wrapped "Map editor" to "Map" and clipped
"Settings" to "Setting", with nothing to say text was missing. The name sits
under its icon with `TextAutoSize` down to 9 sp and an ellipsis as the last
resort; Play and Multiplayer keep the side-by-side layout, since they have
the width.

**Player options, Settings, the leaderboard and the results are menus, so they
are the app's.** StepMania's options and scoring, never StepMania's page: a top
bar with Back, the theme's page and ink, and Start on `LocalBottomChromeNow`.
The first options screen was a fixed dark StepMania page with white text and
underlined choices; in a light theme it was the one dark screen, and its Start
button sat under the mini player and the tab bar where nothing could press it.
StepMania's own art — the grade, the judgement and FULL COMBO sprites — is fine
on a card; its colours as text go through `menuAccent`. Its typefaces belong to
the playfield (the HUD, the judgement and the combo), not the menus.

**A choice of a few is `GlyphSegmented`, and the chosen one is filled.** A
`materialSurface` pill track with a solid thumb in `onSurface` and the chosen label
knocked out in `surface`; the options' and Settings' tabs are the same control.
Start (`GlyphButton(filled = true)`) and the one action of a panel that must
read as the action are filled the same way, and switches pass `onSurface` as
their accent. Never a selection told apart by an accent-coloured label: where a
theme's accent is grey, the chosen chip's label was the dimmest thing in the row
and every selection, Start included, read as disabled.

**The options' preview is the game's playfield, not a picture of one.**
`GlyphPreviewField` draws a real engine played by `GlyphAutoplay` with the
player's modifiers, following the song preview's own clock when it plays, and
`GlyphPlayfield(preview = true)`: a preview never releases the game's rasters
(it can share the screen with the game for a cross-fade), draws nothing rather
than a fallback square for art that is not warm, and closes its engine on
dispose. The menu's backdrop is the same field on `GlyphDemoChart`, whose loop
is seamless, faded out before the buttons.

**Keyboards play through `glyphKeyboard`.** The play and training screens hold
focus themselves so a hardware keyboard needs nothing to tab to; a key is
judged at its event's own time, its repeats are ignored, Escape pauses, and
Back is never bindable. In the map editor the arrows only move the playhead,
even where they play the lanes in a run. The editor is a work surface: like
the playfield it runs without the tab bar and the mini player.

**Held sideways, the lanes are a centred column.** Portrait gives the four
lanes the whole width. Landscape gave them the whole width too, and each arrow
grew to half the screen's height and ran over the HUD. In landscape the lanes
take `landscapeFieldWidth` of the width (40% by default, 25% to 100% in
Settings › Graphics), centred, on whole-pixel lanes (`GlyphFieldBounds`), and
the receptors sit 64 dp from the bottom instead of 96. Gameplay and Training
Ground both lay out through `glyphFieldColumn`. The touch zones follow the
drawn lanes (`glyphLaneSpans`), and the screen either side belongs to the
outer lanes, so a thumb at the edge still presses Left or Right; the hitbox
setting makes gutters between lanes, never at the screen's edge. Scroll speed
already measures the field's height, so a note's time on screen does not
change with the width. Previews keep their own layout.

**Tap to sync is heard, never seen.** The pad in Settings › Sync does not
flash, pulse or count with the click track: a player who follows a flash
measures the screen, and the offset it sets is for the sound. The track plays
through the game's own transport and each tap goes through `touchNanos` and
`positionAt`, as a lane press does, so the reading is exactly what a run would
judge — never a separate timer. At 140 BPM a beat (428 ms) is shorter than
Bluetooth's lateness can be, so a tap is never matched to its nearest click:
the first tap anchors on the first click and the rest follow the running
median (`GlyphTapSync`), which reads up to 500 ms.

**The offset is the output's, and the whole app's.** Trypt Mania's audio
offset is the Bluetooth sync of the output playing now (`OutputSync`, kept per
device by `OutputId`, a device's own before its slot's, on this phone only).
The Sync tab says which device it saves for; Settings › Audio › Bluetooth sync
shows the same value with a 5 ms nudge and Measure, which opens this tab and
leaves the mode when it closes. The lyrics (`LyricClock`), every visual link
driver and the visualizer's bus wait the same amount (`LocalOutputSyncMs`,
`OutputSyncRepository.delayMs`): one number per device, never three that
drift apart. The old lyrics and visualizer sliders are a line pointing here.

**Charting is a line, and a batch is asked before it starts.** Generate puts
the song in `ChartQueue` and never cancels the one being charted; while a song
is in the line its button says Queued or Charting… and cannot be pressed
twice. A folder heading centred on the wheel shows one pill, "Chart N songs",
in the selection's place, and the ⋮ menu has "Chart every song without one".
Both open `GlyphBatchDialog` first: how much music, and each mode's time and
cost, Fast (GPU) and Slow (CPU), from what the servers have shown
(`ChartPace`) — asking a sleeping GPU would wake it and bill its idle minutes.
The wheel keeps the pill's room while any folder has a song to chart, not only
while a heading is centred, so it does not rise and fall as it turns. The
generation panel counts a batch ("3 of 12 charted") with the song in flight
under it, and its button cancels all.

**Every song menu offers Play in Trypt Mania.** `TrackContextMenu`, wherever
it is opened, has the item with Trypt Mania's mark: the noteskin's own arrow,
hollow, turned to point up and right (`TryptManiaArrow`), one colour so it
takes the menu's tint. The action comes from the app's root
(`LocalPlayInTryptMania`) and is read where the menu is asked for, not inside
the overlay, whose host may sit outside what the root provides. Trypt Mania
takes the request when it is up, finds the song by its file, its download or
its name, centres and chooses it, and makes its chart when it has none; a song
not on the phone, or not an MP3 or FLAC in the library, says so.

**Back in a run pauses it; Back from the pause leaves.** The pause panel is
where "Quit to songs" is, so Back walks the same two steps. Quitting on the
first Back threw a run away to a stray edge swipe. On the home screen Back
closes the search, then an open folder, before it leaves the mode.

**Multiplayer is a room, and the room is where a song is picked and where
it ends.** `GlyphMultiplayerScreen` is built as the Packs page: a top bar
with Back, glass panels down a scrolling column, the tab bar kept. Before a
room it is the open rooms, a code to join by and a room to make; in one it
is the code (large, monospaced: it is read out and typed), the chart the
host picked, every player with whether they have the song and are ready,
Ready, the host's Start, Leave, and a line of chat.

* Players are accounts. Signed out, the page is one panel saying so with
  Sign in, which goes to the app's account page (`onSignIn` from the nav
  host); there is no guest play, and the name other players see is the
  account's. The server checks the token; a refused hello is shown once
  and not retried.
* The host picks from the song list in `GlyphSongListMode.ROOM`: the same
  wheel, titled for the room, with the difficulty chips and one pill, Pick
  for the room; no Train, no mods. The chart's text travels with the pick,
  so every phone plays the host's notes and needs only the song: the same
  chart by hash, else a song of that title and artist (or title) within two
  seconds of its length. Each phone tells the room, and the lobby shows who
  has it; Ready is enabled only with the song. Back from that list returns
  to the room, not the menu.
* A song can be passed round the room (`RoomSongShare`), on a rooms server
  that can: its welcome hands the phone a key, and without one nothing of
  this shows. While someone is without the song the host has Share the song
  with the room, which uploads its file as it is (`RoomClient.shareSong`);
  once it is up, a player without it has Get the song with its size, which
  fetches it (`fetchSong`) into the cache, plays from that file and readies
  them in one tap (`haveAndReady`, in that order on the socket, since the
  server refuses a ready without the song). Until the host shares, they are
  told to ask. Progress shows as a bar with its percentage, a failure as the
  server's own reason. A fetched song is the room's, not the library's: it
  is kept only while the room is on its chart and cleared on leaving, and
  its runs, like any of a chart this phone does not have, never count
  against local scores. Nothing of it while a song plays.
* The server names the moment; READY, GO! counts down to it, however long
  that is. A room's song waits for nobody: the pause panel opens over the
  running song, for Quit (to the room, as it says), and offers no Restart.
  An empty bar is a fail in the ranking, never a stop.
* A room's run has a live board down the left, as osu! has it
  (`RoomLeaderboard`, `roomBoardRows`): a card per player with place, name,
  score and combo, best first, a failed run under every live one, a tie kept
  in the room's order so cards do not swap back and forth. This phone's card
  is lit and outlined; past the cards shown (five upright, where the lanes
  take the whole width, eight in landscape) it is pinned last with its real
  place. Centred on the left edge, where arrows are in flight, never at the
  receptors. When a place changes the cards slide to their new order and the
  one that climbed glows green with a rising mark; the slide and the glow
  are a layer's translation and a drawn fill, so the board recomposes only
  as scores arrive (four times a second), each score rolling up in its own
  scope. Reduced motion: the cards jump, nothing glows or rolls.
* The results page of a room's run has the room's panel (the ranking once
  everyone has finished, the scores as they stand until then) and one way
  on, Back to room. A run of a chart this phone does not have is sent to
  the room and not saved against the local scores.
* Back in a room leaves it; Back from the rooms screen closes the socket,
  and with it any room this phone was in, as the server forgets a dropped
  phone's seat. **A listening party is the exception** (below): its socket is
  its own (`PartyClient`, never `RoomClient`), Back from the party screen
  leaves the party on, and only Leave the party ends it.

### Tryptify Connect

**The Devices button sits in the player's top bar, left of the output
(headphones) button**, white like its neighbours; `DevicesOther` while another
of the listener's devices is online, `Devices` otherwise. It opens
`ConnectPanel`, a pane of the player's own glass through `MainPlayerScreen`'s
`overlay` slot, like the Speed panel and the audio tools: never a dialog or a
`ModalBottomSheet` (see Glass), swiped down or Back to close.

* Top to bottom: the grab handle and "Tryptify Connect"; when another device
  plays, "Playing on …" (or "Last played on …" once it is gone), the song, its
  timeline (a Slider with the elapsed and total time, moving in step, a drag
  seeks that device), its Previous, Play/Pause and Next, and Play here; then
  the devices, this phone
  first, each a list row (`MonoDimens.listRowHeight`) with "Play here" or
  "Play there" when the music can move to it.
* Its text and icons are the glass's ink (Text on glass): the playing device
  is said in words ("Playing"), never by an accent colour.
* **The mini player follows another device, as Spotify's does.** While this
  phone plays nothing (or is paused) and another of the listener's devices
  plays, the bar shows that song, "Artist · Playing on …" on its artist line,
  its progress line running in step (`rememberRemotePosition`: worked out from
  that device's last word and the server's clock, nothing sent to keep it
  moving), and its buttons and swipes steer that device. It keeps following
  when that device is paused from here, and lets go when this phone plays or
  that device goes. A tap opens the player with the Connect panel up.
* What it says once ("Now playing on …", "Playing here, from …", "… started
  playing, so this phone paused") is a toast, and only while the app is on
  screen.

### Listening party

**A listening party is the song first** (direction A, the owner's pick from
the demos; `docs/plans/listening-parties.md`). `PartyScreen` is a pushed
screen, reached from the Account page's "Listening party" row (its mark is
`ListeningPartyIcon`, two listeners and a note) and kept with the tab bar
and the mini player like any pushed screen. At a party it is, top to
bottom: a top bar with Back, the party's name, "Hosted by … · code …" and
Invite; the listeners' avatars, "N listening · N on the web" and the dot
that says how this phone is doing (In sync, Catching up, Paused by the host,
…); the cover large, the title, the artist and who added it, the progress
(a Slider for the host, who seeks for everyone; a bar for the rest); the
host's Play/Pause for everyone and Skip, everyone else's Vote skip with the
count; Up next with who added each song and Shared or Uploading N%, and
Add song; the switches for this phone (Share songs, and which goings-on are
notified); Leave the party, and for the host End the party for everyone.
The chat is a panel of the page right under Up next: every line, newest at
the bottom, and the field to say something.

* Colours, type and glass are the listener's own. The demos' purple and
  their Orbitron were placeholders; the website (demo D, served by the rooms
  server) keeps its own look, and the app's screen never copies it.
* The chat is part of the page, a Material 3 panel under Up next like the
  other panels (the owner's call, replacing a glass sheet floating at the
  bottom, which covered Up next and the switches). Never a sheet over the
  page, glass or `ModalBottomSheet`. A long chat scrolls inside its panel
  (at most 280 dp tall), held at the newest line. The pills on the page
  frost the page's ground, a source of their own, never the page they are on.
* Up next's rows are list rows: Material 3, `MonoDimens.listRowHeight`. The
  Add song page is `SearchOverlay` + `GlassSearchBar` over its results, as
  every search bar is: songs on this phone first, then the catalogue.
* Back from the party screen leaves the party playing, in the background
  too; the notification and the lock screen are the player's own, because
  the party plays through it (`PartyController`, a MediaController and
  QueueManager). The host's pause from the notification pauses the party;
  anyone else's pauses only their phone, and the screen offers Back to the
  party. A song played from elsewhere in the app leaves the party playing
  without this phone, said the same way.
* "Add to party" is in every song menu while a party is on, given at the
  root (`LocalAddToParty`) as "Play in Trypt Mania" is, and absent otherwise.
* The host's options live where the people are: a tap on the listeners
  opens Who's listening, and there the host taps someone else's name for
  Make host and Take out of the party, in place under the name. A website
  guest gets no Make host (a guest has no controls, and the server refuses
  it). End the party for everyone is asked twice, in place: the first tap
  only opens a panel saying what it does, with End it and Cancel. Neither is
  a dialog (a dialog is another window and opaque; see Glass), and ending a
  party is never one tap.
* An invite link (`https://<rooms server>/<secret>/party/<code>`) opens the
  party screen, which joins the code at once when this phone is at no party.
  At another party it asks first, in place at the top of the room ("You're
  invited to party …", Join and Not now), never a dialog and never by
  leaving on its own. Signed out, the code waits in the Join field. Only the
  code is taken from a link, and an `/api/party/…` address is never one.
* The host's speed is the party's, and at a party it is always vinyl style:
  the pitch rides the speed, whatever the keep-pitch switch says (the
  owner's call: "like vinyl"). The host changes it with the app's own speed
  control, and every phone and the website play at it. No setting is
  written: `SpeedOverride` stands in for the speed and the switch on every
  phone at a party, the host's too, and they are back the moment the party
  is over. Off 1×, the progress row says the speed between the times
  ("1.25× · vinyl").
* Many songs go in at once, while a party is on: the party's glyph in every
  multi-select bar, and Add all to party in a playlist's and an album's
  action row (`LocalPartyAdder`, null without a party, like
  `LocalAddToParty`). They join the queue in the order given
  (`PartyController.addAll`), one after another, never racing each other.
* Anyone at the party with the app shares their own songs that are files on
  their phone (Share songs, on by default): one upload at a time, in queue
  order, tried three times. A song that came from search says which
  catalogue song it is (`PartyMatch.catalogId(source)`) and carries its
  cover, so other phones and the website find that very recording. Away
  longer than the seat is kept, a phone joins its party again by its code;
  it never lands at another party.
* While a party is on, the phone stays at it in the background, playing or
  not: `PartyService` is a foreground service with its own quiet ongoing
  notification ("Listening party connection" channel), started when the
  party begins and stopped when it ends. Only the listener's own press is the
  party's pause; the player stopping for a call, headphones or the app being
  closed never pauses the party.
* Website guests can add songs from the catalogue (the rooms server's
  `TRYPT_HIFI_URL`); a song a guest added says so like any other, with
  "added by" the guest's name.
* The server's own chat lines (joined, left, added, skipped, now the host) arrive as kinds
  and names and are said in the listener's language; only what people type
  is shown as typed.

### Discord presence

The animated asset must stay under **~248 KB** or Discord's media proxy shows
the first frame and never animates — which reads as "the animation is broken".
`PresenceArtwork` fits itself to that budget by stepping quality down and then
dropping frames. Do not raise the canvas size or frame count without re-checking
the assembled size.

Local (`content://` / `file://`) artwork has no URL Discord can fetch, so it is
uploaded as an attachment and referenced by the media-proxy path — the same route
the animation takes. This needs the upload channel configured.

## The visualizer has two compositions, and only one at a time

The hero visualizer (`ProjectMRendererView`, a `GLSurfaceView`) **replaces** the
artwork. The ambient overlay (`ProjectMOverlayView`, a `TextureView`) **is** the
player's background, with the artwork composited into it. They are two framings
of the same engine and they must never be on screen together:
`ProjectMEngineRepository` refcounts attached surfaces and only the *first* owns
the native bridge, whose GL objects belong to that one context. A second view
would render from objects it does not have. `MainPlayerRoute` keeps them apart
with ambient winning: while the ambient visualizer is on, the view mode is moved
off VISUALIZER to the cover. It used to be the other way round, the overlay
standing down whenever the view mode was VISUALIZER, and switching ambient on
then seemed to do nothing, because the hero kept the engine.

The overlay is a `TextureView` for a reason that cannot be worked around: a
`SurfaceView` is composited by SurfaceFlinger either *behind* the window
(hole-punched, so opaque Compose content above hides it) or, with
`setZOrderOnTop`, *in front of the whole window* including the player's
controls. There is no "in the middle of the stack" for a SurfaceView. The same
fact is why the `graphicsLayer { alpha }` around the hero renderer does nothing:
view alpha never reaches a SurfaceView's buffer.

**projectM will not render into your framebuffer.** `ProjectM::RenderFrame` in
4.1.6 ends with a hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)` — the
`ToDo: Allow external apps to provide a custom target framebuffer` is on the
line above — and the C API has no FBO-taking variant. So the overlay lets it
draw where it insists, then `glBlitFramebuffer`s the result into a texture
before overwriting the surface with the composite. GPU-side throughout; nothing
is read back to the CPU, and preset feedback is untouched because that lives in
projectM's own internal FBOs.

**The blend happens in the fragment shader, not between views.** HWUI composites
a TextureView with plain source-over and `glBlendFunc` only reaches inside our
own surface, so a Screen blend against the artwork is only possible if the
shader has the artwork. It does: the overlay draws the blurred cover and the
scrim itself and outputs opaque pixels, which is why it replaces
`PlayerBlurredArtBackground` rather than layering over it. Two backdrops would
be the artwork darkened twice.

**A frame projectM did not draw is never presented.** `renderFrame` returns
whether it drew: it does not when the Target FPS cap drops a frame (vsync off)
or a paused frame is frozen. With vsync off the swap no longer waits for the
display, so the render threads wait out the interval themselves
(`nanosUntilNextFrame`) instead of drawing flat out and letting the cap drop;
the dropped frames used to be presented anyway, the ambient overlay
compositing its cleared, empty frame (the cover alone) and the hero swapping a
buffer nothing drew into, and the visualizer flashed and vanished between real
frames. When nothing new was drawn the overlay composites its last capture
(`freshFrame = false`).

**Alpha is inferred from the rendered image, never from the preset.** Presets
assume an opaque, usually black framebuffer and many depend on feedback; making
the target transparent breaks trails, warps and glow. `ambientAlpha` derives the
alpha from the pixels afterwards, which is what makes this work across a whole
`.milk` collection unmodified. Its Kotlin twin in `AmbientVisualizer.kt` is
tested; the GLSL is not runnable in this build, so the two must be changed
together.

## Build and test

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

`assembleDebug` needs git submodules (`third_party/projectm`, `libusb`) checked
out, recursively (projectM has a nested one; see AGENTS.md); without them it
fails for reasons unrelated to any change here.

## Commits

Author as `tryptz`. No co-author trailers, no tool attribution in commit
messages, PR bodies or code comments.
