# The library galaxy — architecture

Status: **built** (all seven steps below). Read `docs/ui-invariants.md` → "The
genre galaxy" and "The library galaxy" first. Every rule there applies to this
map too, and this document assumes them rather than repeating them.

Where the code lives:

| Piece | File |
|---|---|
| Star field seam | `ui/discover/galaxy/StarField.kt`, `GenreStarField.kt` |
| Library stars | `ui/discover/galaxy/LibraryStarField.kt` |
| Library systems | `ui/discover/galaxy/LocalSystems.kt` (and `PlanetSystem.kt`: `MoonRef`, `SystemLimits`) |
| Input records | `domain/model/LibraryGalaxyModel.kt` |
| Shared stage | `ui/discover/GalaxyStage.kt` (`GalaxyStage`, `GalaxyStageScope`, `GalaxyHandoff`) |
| Data | `data/local/galaxy/LocalGalaxyRepository.kt`, galaxy queries in `LocalMediaDao` and `PlayEventDao` |
| Screen | `ui/library/galaxy/LibraryMapScreen.kt`, `LibraryGalaxyViewModel.kt` |
| Tests | `LibraryStarFieldTest`, `LocalSystemsTest` (plus the genre galaxy's own, unchanged in substance) |

## The idea in one table

The genre galaxy is already three levels deep: genre → artist → track. The
library galaxy is the same three levels over the music on the device:
**artist → album → track**. Same renderer, same camera, same glass. The new
part is where the data comes from.

| Body | Genre galaxy (today) | Library galaxy (new) |
|---|---|---|
| **Star** | a genre | an artist (the album artist, else the track artist — the same name the Library's Artists tab groups by) |
| Star size | how well known the genre is (`reach`) | how much of them you own: track count, log scale |
| Star colour / arm | the genre's family | the family of the artist's main genre tag |
| Where it sits (*Sound* layout) | the baked genre map | **next to its genre's star** from the genre map, scattered round it |
| Where it sits (*Time* layout) | the year the genre began | the artist's earliest album year |
| Links between stars | parent genre | collaborations: a track whose artist ≠ its album artist |
| **Planet** | a charted artist (≤ 6) | an album |
| Planet size | MusicBrainz catalogue | the album's running time, log scale, full at 80 minutes (its track count when no track has a length) |
| Planet colour | the artist's name, hashed | the album's key, round the Camelot wheel: its commonest track key, tagged TKEY / INITIALKEY; the album's own key hashed when none is tagged |
| Planet orbit | chart heat this week | release order: debut nearest the star (see *Decisions*) |
| Planet texture | chart artwork URL | the album's cover (`artworkCacheKey`, through Coil) |
| Planet atmosphere | the colour, no tone | the key's colour, deeper for minor and paler for major; hazed wider the louder the master (ReplayGain / R128 as LUFS, -20 to -6) |
| Planet spin | none | the cover turns once every 32 beats of the album's median tempo (TBPM / BPM tags) |
| Planet shadow | full depth | as deep as the album's median dynamic range: lit flat at DR4, full depth from DR16 |
| **Moon** | a charted track (≤ 4) | a track |
| Moon size | its listens | its duration |
| Moon orbit pace | by its slot | its track's tempo: once round per 16 beats, outer slots slower; by its slot without a tempo |
| Moon glow | the star's light | its track's key, as a planet's (deeper for minor, paler for major); the star's light without a key |
| Extra albums / tracks | not shown | an **asteroid belt** past the last orbit / a **ring** round the planet |
| Black hole | the core | kept: the same core, so both maps share one frame |

Because the Sound layout puts each artist beside their genre, the two maps live in
**one coordinate frame**. Switching between them can keep the camera where it is.
Your library appears where it belongs in the galaxy you already know: your jazz
sits where jazz is.

## The shape of it

```
 local_tracks / local_albums / local_artists        play_events
            │  (aggregate SQL, no full-table load)       │
            ▼                                             ▼
 ┌──────────────────── LocalGalaxyRepository (@Singleton) ───────────────────┐
 │  observes library changes → debounce → reads off the main thread          │
 │  artists(): Flow<LibraryArtists>       music(artistId): ArtistMusic       │
 └────────────┬──────────────────────────────────────────┬───────────────────┘
              │ LibraryGalaxyViewModel: pure, JVM-tested builders            │
              ▼                                           ▼
   LibraryStarField.build(...)                 LocalSystems.build(...)
              │                                           │
              ▼                                           ▼
        StarField  ◄── GenreStarField.from(graph)    PlanetSystem (generalised)
              │          (the genre map, unchanged)       │
              ▼                                           ▼
         GalaxyScene ──────────────► GalaxyStage (shared: camera, view, glass,
                                      full screen, look sheet, pacer, lights)
                                       ▲                    ▲
                          GenreMapScreen            LibraryMapScreen
                          (dock: Play/Radio/        (dock: Play/Albums/
                           Top 100/History)          Artist page/collabs)
```

Three layers, each with one job:

1. **Data**: `LocalGalaxyRepository`. It reads the database and decides when
   to rebuild.
2. **Model**: pure builders (`LibraryStarField`, `LocalSystems`). They take
   plain rows and produce positions and sizes. They don't touch Android, so they
   are unit-tested on the JVM like `GalaxyScene` and `PlanetSystem` already are.
3. **Stage**: the existing renderer, opened up at a few seams so it draws any
   `StarField` and any `PlanetSystem`, not just genres and charts.

## Layer 3 first: the seams in the renderer

Cut these before any library code exists, and prove that the genre galaxy is
unchanged.

### `StarField` (new, pure data)

Everything `GalaxyScene` currently derives from `GenreGraph`:

```kotlin
class StarField(
    val ids: List<String>,          // "genre:<id>" / "artist:<normalizedName>"
    val names: List<String>,
    val families: List<String>,     // fixed order; family[i] indexes into it
    val family: IntArray,
    val prominence: FloatArray,     // 0..1 → star radius
    val startYear: IntArray,
    val sound: FloatArray,          // xyz per star, precomputed
    val time: FloatArray,           // xyz per star, precomputed
    val links: IntArray,            // (a, b) pairs
    val halo: BooleanArray,         // sits in the halo, not the disc (unknown genre)
)
```

- `GalaxyScene(field: StarField)` keeps everything decorative: dust, core, halo
  stars, sky, nebulae, flows. `GenreStarField.from(graph)` holds today's layout
  code (`bundleArms`, `timeRadius`, the baked x/y).
- **The genre galaxy must come out identical.** Today positions and dust share
  one sequential `Random(977)`. The genre adapter has to draw from it in the same
  order, or the dust reshuffles. `GalaxyMathTest` is the proof: it must pass
  untouched.
- The view's `scene.genres[i].id/name` reads become `scene.ids[i]` and
  `scene.names[i]`. `explored` / `hearted` / `hereId` stay as plain id sets. On
  the library map, *here* is the artist now playing and *hearted* is favourite
  artists.

### `PlanetSystem` (generalised, not forked)

- `genreId` → `starId`. `Planet.artist` → `key` + `label`.
- `Moon.entry: ChartEntry` → `Moon.ref: MoonRef`, a sealed type:
  `Chart(entry)` | `Local(trackKey, title, artworkUri)`. Tapping a moon
  dispatches on it.
- The caps (`MAX_PLANETS = 6`, `MAX_MOONS = 4`) become a `SystemLimits`
  parameter. `minOrbitGap` and `reachFor` are derived from it, so the "moons
  never cross" rule holds for any limits.
- New and optional: `Planet.ringCount` (tracks not shown as moons) and
  `PlanetSystem.beltCount` (albums not shown as planets). The ring sits between
  the planet's surface and its first moon orbit (`< 2.2 ×` the largest planet),
  so it cannot touch a moon. The belt sits past the last planet's reach, and
  `reachFor` includes it, so the camera frames it. Both are batched
  `drawPoints`, so they cost almost nothing.
- `PlanetSystem.from(chart…)` stays as the chart adapter, exactly as it is.

### `GalaxyStage` (extracted from `GenreMapScreen`)

`GenreMapScreen` is 1,700 lines, and most of it is not about genres: camera,
glide, reserves, full screen, orientation, pacer, look sheet, lifecycle. Pull
that into a `GalaxyStage` composable that takes a `StarField`, a system, and
slots for the dock and sheets. Then the genre screen and the library screen are
two thin screens over one stage.

**Don't** build one screen with a mode flag threaded through it. The two docks
have nothing in common (Top 100 / History / Radio vs Albums / Artist page), and a
flag through 1,700 lines is how the invariants get undone.

## Layer 2: the pure builders

### `LibraryStarField.build(artists, artistGenres, collabs, graph)`

1. **Main genre per artist**: the most frequent tag across their tracks that
   `GenreGraph.resolve` recognises. Split multi-genre tags the same way
   `MediaScanner` does (around `MediaScanner.kt:388`). No resolvable tag → the
   artist goes in the **halo**, above and below the disc, instead of on it:
   "genre unknown" is shown honestly rather than guessed. `resolve` returning
   null instead of guessing is the same principle.
2. **Sound position**: the genre star's sound position, plus a 3D Gaussian
   scatter (fixed σ ≈ 60 units, flattened like the disc). The scatter is seeded
   by the artist's own key, then pushed out radially to stay clear of
   `DISK_OUTER`.
3. **Time position**: the genre-map spiral, using the artist's first album year,
   on their genre family's arm (`bundleArms`, reused).
4. **Links**: collaboration pairs, deduplicated, kept only when both artists are
   stars.

**Stability is the rule this map adds.** The genre dataset is fixed, but a
library grows. Every per-star value (position, twinkle phase) is
seeded by a **stable hash of that star's own key** (`PlanetSystem.stableHash`
already exists), never by a shared sequential `Random`. Adding one artist must
not move any other star. The test below pins that.

### `LocalSystems.build(albums, tracks, plays, starRadius, limits)`

- **Which albums are planets**: up to `limits.maxPlanets` (proposed 8), picked by
  lifetime plays, ties broken by running time. They are laid out in release order
  (year, then title), with the debut on the innermost orbit. The rest go into the
  belt.
- **Which tracks are moons**: up to `limits.maxMoons` (proposed 6), picked by
  plays, ties broken by track number. Moon orbits follow the album's own running
  order. The rest go into the ring.
- **Heat** (plays in the last 30 days) is drawn as **glow**, not distance. An album
  you have on repeat burns brighter, and nothing moves when you play it.
- The phase, tilt and node of every body are hashed from its own key (album
  `groupingKey`, track `filePath`), the same way `PlanetSystem.from` hashes names
  today.

### Dynamic range, measured as a track plays

No tag carries dynamic range, and decoding the whole library to measure it is
not light, so each local track is measured the first time it plays through.

- **Where**: `DynamicRangeTapProcessor`, a passive tap next to the tempo tap at
  the head of all three chains (the main sink, USB exclusive, the hi-res path).
  It hears the track as decoded, before the mixer, the effects, the volume and the
  speed change, so the value is the file's own whatever the output. It passes
  audio through untouched: no latency, no gain.
- **How**: `cpp/dsp/meter/dynamic_range_meter.h`, the DR meter's method: 3 s
  blocks, RMS as sqrt(2 · mean x²), loudness the RMS of the loudest 20 % of
  blocks, peak the second highest block peak, averaged over the channels with the
  LFE left out. Header-only, fixed arrays, nothing allocated on the audio thread;
  `dynamic_range_meter_test` holds it to signals whose DR is known by hand.
- **When it is kept**: `DynamicRangeRecorder` turns the meter on only for a
  library track with no value yet, and keeps the reading only for a whole, clean
  play (`DynamicRangeRules`): the length measured within 2 s (or 2 %) of the
  track's, no seek on the way. A skip, a seek or a crossfade (whose last seconds
  play on another player) leaves the track unmeasured until a clean play.
- **Where it is kept**: `local_tracks.dynamicRange`, by a one-column UPDATE on the
  file's path. A rescan carries it over while the file is unchanged
  (`MediaScanner.keptDynamicRange`); a rewritten file is measured again.

## Layer 1: data

### `LocalGalaxyRepository` (`data/local/galaxy/`, `@Singleton`)

It returns plain records (`domain/model/LibraryGalaxyModel.kt`), never stars
or planets: laying them out is the ViewModel's, through the pure builders, so
the data layer imports nothing from the UI.

- **Grouped SQL, never `getAllTracks()`.** The artists are the `local_artists`
  rows (the scanner's own grouping: album artist, else artist, else "Unknown
  Artist", which is what the Library's Artists tab lists), plus three
  `LocalMediaDao` queries grouped by `artistId`:
  - `galaxyFirstYears`: `MIN(year)`, for the timeline
  - `galaxyGenreTags`: each (artist, genre tag) and its count, for the main genre
  - `galaxyCollabs`: each (album artist, track artist) where they differ, for links
- **Plays**: `PlayEventDao.playsOnAlbums`, grouped by album and track title,
  ever and in the last 30 days. A local play records the tags' titles but not
  the file's row and no source, so the titles are what joins.
- **When to rebuild**: `observeGalaxyStamp` (track count, artist count, the
  newest `lastScannedAt`; Room re-runs it whenever either table changes),
  distinct, debounced 400 ms, read on `Dispatchers.Default`.
  `LocalLibraryRevision` alone isn't enough, because it doesn't bump on removals.
- **Systems** are read once the selection has rested on a star for 150 ms
  (the genre map waits 0.7 s for a chart request; this is a local query). That
  is only so flying past fifty artists doesn't run fifty queries.
- **Fully offline.** No MusicBrainz, no charts, no network. It works in
  airplane mode, and that is the point of a local map.

### Identity

| Body | Key | Why |
|---|---|---|
| Star | `artist:` + `local_artists.normalizedName` | unique-indexed and survives a rescan; autoincrement ids may not |
| Planet | `local_albums.groupingKey` | already normalised title + artist + year |
| Moon | `filePath` (or `musicbrainzTrack` when present) | unique per file |

## Special cases

- **Compilations** ("Various Artists", or an album where most tracks have
  different artists): one *Compilations* star near the inner disc. Its planets
  are the compilations, and it links to every track artist who is also a star.
- **Unknown Artist**: one dim star in the halo.
- **Empty library**: no scene. Show the Library's empty state with its scan
  action, not an empty sky.
- **Classical**: composer-as-star would read better for some libraries. Leave it
  as a later option; it doesn't change the architecture, only the key the
  builder groups by.

## Performance budget

The genre map is tuned for **771 stars**. A library can have 5,000 artists.

- **Sprite budget about 800**: the most prominent stars get full sprites, rays
  and labels, as today. The rest are drawn in the batched `drawPoints` that dust
  already uses, and are **promoted** to sprites when the camera comes close,
  eased in rather than popping (`pickStarLights` already does this kind of fade).
- Stars-with-rays stay capped at `StarLights.MAX`. That is unchanged.
- Building the scene is O(artists), off the main thread. The scene object is
  immutable and swapped in whole.
- Read `GalaxyFrameStats` (`GalaxyFx` in the debug log) on a 3,000-artist library
  before and after, as the invariants ask.

## Tests (the guarantee, not a formality)

- `GalaxyMathTest`, `PlanetSystemTest`, `GalaxyFastPathsTest`: **pass unchanged**
  after the seam refactor. That is the proof the genre galaxy didn't move.
- `LibraryStarFieldTest`:
  - deterministic
  - **adding an artist leaves every other star's position unchanged**
  - no star inside `DISK_OUTER`
  - unresolved genres land in the halo
  - links are deduplicated and only between stars
- `LocalSystemsTest`:
  - planets + belt = all albums; moons + ring = all tracks
  - orbits in release order
  - moons never cross under `SystemLimits(8, 6)`
  - a play changes glow, never an orbit

## Build order (each step its own commit, each green)

1. **Seams**: `StarField`, `GenreStarField`, generalised `PlanetSystem`
   (`MoonRef`, `SystemLimits`). Genre map pixel-identical; existing tests
   untouched.
2. **Stage**: extract `GalaxyStage` from `GenreMapScreen`. Still only the genre
   map; the screen should look and behave exactly the same.
3. **Model**: `LibraryStarField` + `LocalSystems` with their tests. No UI yet.
4. **Data**: DAO aggregates, `LocalGalaxyRepository`, `LibraryGalaxyViewModel`.
5. **Screen**: `LibraryMapScreen` on the stage. Dock: Play (shuffle artist),
   Albums (the system sheet), Artist page, and up to three collaborators as pills
   (the "related genres" slot). Planet tap → Play album / Open album. Moon tap →
   play the track.
6. **Entry points**: a Library galaxy tile under Folders on the local Library tab, and a
   *Genres · Mine* switch on the genre map that keeps the camera (it's the same
   frame).
7. **Polish**: ring and belt art, heat glow, compilations star, level-of-detail
   promotion, then a "The library galaxy" section in `ui-invariants.md`.

## Decisions (settled)

1. **Orbits are release order.** The debut is nearest; heat (plays in the last
   30 days) is the atmosphere's glow, never a move.
2. **It opens from both places**: a "Library galaxy" tile in the local Library
   tab, the last one, under Folders (not a category: categories open in place,
   this opens its own screen), and a title-bar action on the genre map ("Your library galaxy"; the
   library map has "Genre galaxy" back). The switch hands the camera and the
   layout over (`GalaxyHandoff`) and replaces the map on the back stack
   (`switchSafe`), so switching back and forth never piles maps up.
3. **Caps**: `SystemLimits.LIBRARY` is 8 planets and 6 moons, with a belt, and
   with moons kept wholly apart (`apartMoons`: neighbouring orbits at least two
   moon systems' width apart).

## Where the build differs from the plan above

- **The dust reshuffles when the library changes.** Stars, their twinkle and
  their places are keyed per artist and never move; the dust grains round them
  are the scene's decoration and are dealt out again. Nothing you can find
  your way by moves.
- **Plays are matched by album and track title.** A local play records the
  tags' titles and not the file's row (and no source), so
  `PlayEventDao.playsOnAlbums` joins on those. Two albums with the same title
  share their plays; tracks are matched within their album, so that only
  matters for the planet's glow and its choice as a planet over the belt.
- **The switch is a title-bar action, not a segmented control.** It has to be
  there whatever is selected, and the HUD is only up with nothing selected.
- **Hearts and "explored" are empty on the library map.** There is no local
  equivalent yet; the renderer takes them as plain id sets if one comes.
- **The panel reserve is one measured height** for whichever bottom panel is up
  (HUD or dock), not one per kind. The camera's reserve eases either way.
