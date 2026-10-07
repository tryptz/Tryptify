# UI invariants — do not revert

Guidance for anyone (human or agent) changing this codebase's interface. None of
this is style preference: every rule is a bug that was found the hard way,
usually from a screenshot, and reverting one brings the bug back.

The build these describe is commit `5ec5b072` ("Match the search bars to the mini
player, and undo the player haze"), plus the glass tab bar that replaced the page
list on Home (see "Pages and the tab bar"), so the accepted state can be diffed
against rather than argued about:

```
git diff 5ec5b072 -- app/src/main/java/tf/monochrome/android/ui
```

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

**A rounded-rect slab passes its corner as `lensCorner`.** The alpha
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

**Prototype: the mini player, the tab bar, `GlassPanel` and the full player's disc and dock bend the live screen**
(`LiveGlassLens.kt`, behind `LIVE_LENS_GLASS`). **The mini player and the tab bar are one
material and must match exactly:** same lens, same blur share
(`LIVE_LENS_CHROME_BLUR_SHARE`, now the same share as every other pane's, kept
as its own name so the two bars always pass one value), same frost, and the
same tint — the nav host takes the tab bar's tint *outside* `DynamicColorScope`
and hands it to the mini player
(`glassTintColor`), because inside it `primary` is the album's colour and the
bar came out a different hue from the tab bar under it. Their haze pane is replaced by a
layer that draws Haze's own capture of the screen behind them
(`HazeState.areas[i].contentLayer`, offset by `positionOnScreen`), blurs it (a
fifth of `hazeBlurDp`, 6.4dp on Clear) and bends it with the same lens rim,
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
surfaces start from the `Clear` preset (`PlayerGlassSettings.INITIAL`), as does
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

**Do not put a haze pane under a punched glass slab** (the transport disc, the
action dock, the mini player). Those are drawn solid and made see-through by the
shader's body opacity, and what shows through is whatever is composited
underneath — an opaque frosted pane there gives them an opaque backdrop and they
come out as flat slabs with the artwork nowhere in them. Haze belongs under a
sheet that slides *over* a page, like the audio-tools sheet.

**A pane that needs haze cannot live in a dialog, popup or `ModalBottomSheet.`**
Those are separate windows. The backdrop was captured into a layer belonging to
the window behind them, and a haze effect cannot sample a layer from another
window — handing the state across is the same "solid slab" failure as sampling
your own layer, and there is no setting that fixes it. The pane has to be
rendered in the window whose background it is meant to blur, as a sibling of that
window's haze source: `MainPlayerScreen`'s `overlay` slot exists for exactly
this, and the speed panel goes through it. The cost is owning the scrim, the
slide and Back by hand, and that is the cheaper half of the trade.

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
  In the player that means **only while the blurred album background is on**,
  because that is the only time the artwork is really behind the glass; with it
  off the backdrop is the flat wash, which the shader reconstructs exactly.

The **mini player is the exception, and deliberately so.** Away from the player
the artwork is not behind the bar — the app's own content is — so its glass uses
`BackdropArtFit.PANE`: the cover is fitted to the bar rather than positioned
behind it, and its colours sweep along the length of it. Two reasons it cannot
use the honest mapping. A 64dp bar is about a twelfth of a phone, so the slice
of a 64px thumbnail behind it is roughly five pixels and refraction moves it by
a fraction of one — the effect would be invisible. And there is nothing honest
to map: PANE is a material property of the bar, not a window onto something, so
it is not gated on the blurred-background setting either. Its scrim is flat
(zero height in `uArtScreen`) and read off the bar's own position on screen,
because the gradient it stands in for is not really there.

The **Glass spectrum on the hero art is the other exception**. It lies on the
cover itself, so the cover is behind it whatever the blurred-background setting
says, and the hero hands it the art unconditionally while that style is on. The
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

It is a bitmap and not a live layer capture because it cannot be one:
`RenderEffect.createRuntimeShaderEffect` binds exactly one input, this shader
spends it on `content` (the alpha heightfield every bevel normal comes from),
and no public API turns a `GraphicsLayer` into an `android.graphics.Shader` for
the second. The thumbnail is 64px on purpose — it stands in for a 64dp blur, and
lensing a sharp cover would refract detail that is nowhere on the screen.

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

**God rays wrap the lyric list outside its side inset and outside the glass.**
`lyricGodRays` is a RenderEffect, and a layer cannot draw past its own bounds,
so a rays layer inside the inset cuts every shaft off in a hard vertical line
14dp from the edge. Outside the glass, so the shafts are gathered from the
finished glass letters. The effect is rebuilt in the layer block every draw,
like the glass: a RuntimeShader effect takes its uniforms when it is created.

**Only what is being sung shines, and it is dimmed on itself.** The band is
the active line from `layoutInfo` (`item.offset - viewportStartOffset`, read in
the draw phase so the shafts follow the scroll glide) or, with "Follow the sung
word", the word the karaoke line reports. Letting every visible line emit made
the screen a wash; dropping the 65% guard inside the band buried the sung line
under its own light (the first prototype, at 90° elevation). The band's sides
are `GodRayGeometry.UNBOUNDED`, not infinity: an infinity reaching
`smoothstep` comes back NaN.

**Backlight decays from the light, Letters from the pixel.** The article's
decay weights samples by their distance from the pixel. In Backlight the light
is always at the far end of the march, so that weighting all but erased it;
there the march is averaged plainly and the decay is counted out from the light.
Letters keeps the article's weighting. Both normalise by the sample count, so
the quality setting changes grain, not length or brightness (`GodRayGeometryTest`).

The rays are off by default, and the 17 presets ahead of `Sunburst` set no
glass optic and no ray field, so nobody's lyrics change on upgrade. The
low-performance glass switch drops the rays with the glass.

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
`rememberLiquidGlassAvailable()` before drawing that slab, falling back to the
frosted pane with ordinary icons, because a solid slab without the shader is an
opaque block with holes in it. Its titles are plain text, not punched: the
glyphs are chunky because the bevel needs about 3dp of stroke to read as an
edge, and an 11sp title's strokes are thinner than the bevel.

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

**The bar is on every screen but four**: the player, the mixer, Oxford and car
mode, whose own controls run to the bottom edge (`chromeHiddenRoutes`). The
download pill follows the same list.

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

### List rows

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

**The subtitle is capped at one line** in both `ClickableArtists` and
`TrackArtistAlbumLine` — the outer `FlowRow` too, not just the inner one. The
album segment is appended outside `ClickableArtists`, so capping only the inner
row still let "artist • album" wrap. With a fixed row height a second line is
clipped rather than shown, so the cap is what keeps the text intact.

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

The tint goes through `customScheme` rather than swapping slots the way
`DynamicColorScope` does. Slot-swapping works on the player, whose foregrounds
are hardcoded white, and falls apart across the menus, where the album accent
lands on surfaces the base theme derived from a different colour entirely.

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

## The visualizer has two compositions, and only one at a time

The hero visualizer (`ProjectMRendererView`, a `GLSurfaceView`) **replaces** the
artwork. The ambient overlay (`ProjectMOverlayView`, a `TextureView`) **is** the
player's background, with the artwork composited into it. They are two framings
of the same engine and they must never be on screen together:
`ProjectMEngineRepository` refcounts attached surfaces and only the *first* owns
the native bridge, whose GL objects belong to that one context. A second view
would render from objects it does not have. `MainPlayerRoute` gates the overlay
on `viewMode != VISUALIZER`.

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
out; without them it fails for reasons unrelated to any change here.

## Commits

Author as `tryptz`. No co-author trailers, no tool attribution in commit
messages, PR bodies or code comments.
