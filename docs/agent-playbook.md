# Tryptify Agent Playbook

Repository: `tryptz/Tryptify`
Prepared: 2026-08-21 · Re-verified against `281c07a2` (1.9.4) on 2026-10-19: paths, test names and every review target

This file is durable project context for coding agents working on Tryptify. Treat the repository itself as the source of truth whenever a path, field, version, or command has changed.

## Paste into Project Instructions

You are working on Tryptify, an Android music player with a native audio engine, Media3 playback, Jetpack Compose UI, radio/discovery ranking, configurable glass surfaces, visuals that move with the music, and Trypt Mania, a rhythm game built in.

Before changing code:

1. Read the repository-root `AGENTS.md` and any nearer `AGENTS.md` that governs the files you will touch.
2. Inspect the current implementation, tests, Gradle configuration, and call sites. Never rely on an old field list or remembered API.
3. Route the task through the matching playbook in `docs/agent-playbook.md`:
   - themes, haze, glass, mini-player visuals -> Glass Surfaces
   - a visual setting or mixer knob that moves with the music -> Music Links
   - DSP, JNI, Atmos, loudness, normalization -> Realtime Audio
   - player service, Media3, Cast, Auto, intents, deep links -> Playback Routing
   - radio, recommendation, scoring, discovery -> Radio Ranking
   - Supabase sync, synced settings, favourites, playlists, presets -> Cloud Sync
   - Trypt Mania, chart generation, its servers, multiplayer, the CRT effect -> Trypt Mania
   - build upgrades, testing, performance, shrinking, security -> Engineering Gates
4. Preserve existing behavior unless the request explicitly changes it. Do not remove features, settings, compatibility paths, or tests merely to simplify an implementation.
5. Keep UI state, persisted settings, serialization, defaults, clamping, previews, and runtime consumers in sync.
6. Prefer small, reviewable changes. Avoid unrelated formatting or refactors.
7. Add or update tests for behavioral changes. Run the narrowest relevant checks first, then the broader suite justified by the change.
8. Report what changed, what was verified, and any unverified risk. Never claim a check passed unless it ran.

Repository conventions in `AGENTS.md` override this file. When opening a pull request, use a focused branch and commit history; do not add AI/tool attribution unless the repository explicitly asks for it.

## Skill Router

| Trigger | Primary playbook | Required secondary check |
|---|---|---|
| Player glass, theme, haze, opacity, progress visuals | Glass Surfaces | Compose performance when rendering changes |
| A setting or knob that follows the music | Music Links | `VisualLinkTargetsTest`, `MusicLinkFollowerTest`, `mod_route_test` |
| Native DSP, Atmos, JNI, buffers, audio callbacks | Realtime Audio | Native tests and realtime-safety audit |
| Media3 session/service, notification, Cast, Auto | Playback Routing | Intent/security review for exported surfaces |
| Radio queue, ranking, discovery, recommendations | Radio Ranking | Deterministic tests and observability |
| Supabase sync, favourites/playlists/presets persistence, settings sync | Cloud Sync | `SyncOutboxTest`, `SettingsSyncCodecTest`, `PresetSyncMappingTest` |
| Trypt Mania game, charts, servers, multiplayer, CRT effect | Trypt Mania | `docs/ui-invariants.md` "Trypt Mania", JVM and `glyph/tests` engine tests |
| AGP/Kotlin/KSP/Hilt upgrade | Engineering Gates | Full clean build and migration review |
| R8/ProGuard or release-size work | Engineering Gates | Release build plus keep-rule evidence |

The repository also ships a `player-visuals-themes` skill under `.claude/skills/`. It is the detailed reference for authoring Lyrics FX and Player Glass themes; this playbook's Glass Surfaces section is the process around it. Where the two disagree, the model's own `clamped()` wins. Its validator now holds the skill's tables to the model (see the Known Review Targets below), so a disagreement there fails loudly.

## Playbook: Glass Surfaces

Use for changes to player glass, theme presets, haze, tint, opacity, blur, borders, corners, mini-player visuals, or progress indicators.

### Source of truth

Locate and inspect the current definitions and consumers before editing. At minimum, search for:

- `PlayerGlassSettings`
- its `clamped()` or equivalent validation method
- serialization/persistence adapters
- settings UI and previews
- player and mini-player renderers
- theme/preset application code
- `bodyOpacity`, `hazeBlurDp`, `hazeTint`, and `miniProgressBar`
- the two stored copies: the Studio's Player tab (`player_glass_json`: the play disc, chevrons and dock) and its UI panels tab (`mini_player_glass_json`: the mini player, tab bar and every panel and pill), which offer the same `PRESETS`; `LocalGlassArea` says which one a renderer is drawing
- the glass fields a music link can move (`VisualLinkTargets`, see Music Links)

Do not reintroduce removed settings such as `popAmount`. Do not copy ranges from documentation when the model's current clamping logic differs.

### Workflow

1. Map the complete setting lifecycle: default -> persisted value -> migration -> state holder -> editor/preview -> runtime renderer.
2. For a new or renamed field, update every lifecycle stage together.
3. Use the model's clamping/default logic as the validation contract. Make UI controls match that contract.
4. Verify both full player and mini-player behavior, including dark/light themes and extreme values.
5. Keep expensive work out of recomposition. Memoize derived brushes, shapes, shaders, and transforms with the right keys.
6. Preserve accessibility: legible contrast, meaningful labels, touch targets, and reduced-motion behavior where applicable.

Note that `PlayerGlassSettings` distinguishes *material* fields, which a theme sets, from *personal* fields, which it must carry across unchanged — see `withPersonalFrom` and `matchesPreset`. A preset that overwrites a personal field is a bug even when every value it writes is in range.

### Completion gates

- Round-trip persistence works.
- Presets and custom settings do not overwrite unrelated fields.
- Preview and runtime visuals agree.
- Boundary values are tested from the actual clamp contract.
- No stale setting name remains in docs, validators, or tests.

## Playbook: Music Links

Use for anything that makes a setting move with the music: a visual link (a Studio slider's ♪, the Music links page, a theme pack's links) or a mixer knob's link.

### Source of truth

- `domain/model/VisualLinkTargets.kt`: every visual setting a link may move, its key (`lyrics.`, `player.`, `panels.`, `tv.`, `visualizer.`, by `LinkArea`), its range, and what has to be on for it to show. Its `VisualLinkTarget` doc lists what may not be linked, and why.
- `domain/model/MusicLink.kt`: `LinkShape`, `VisualLink`, `MusicLinkFollower` (how a band of the music becomes a value) and `VisualLinkCodec` (the saved form); `LinkFormula.kt` for a custom formula.
- `data/repository/MusicLinksRepository.kt`: every edit goes through it under one lock, so the Studio, the Music links page and a theme import cannot race; it also holds the master pause and the link an open editor is running.
- `ui/settings/StudioMusicLinks.kt`: the Studio's editing (`VisualLinkEditing`), overlay and undo. `ui/musiclinks/` is the page and the editor; `ui/player/VisualLinks.kt` publishes the live values the renderers read.
- Mixer knobs: `audio/dsp/model/KnobLink.kt`, `ui/mixer/KnobLinks.kt`, and natively `dsp/mod_route.h`.
- The rules: `docs/ui-invariants.md` "### Music links".

### Invariants

- A link never moves the saved value. Renderers fold the live values in inside their draw and layer blocks; composition may read only `linkedKeys`. Read in composition, a linked lyric layer recomposes every frame.
- A target's `min` and `max` are its field's own `clamped()` bounds. The one exception is `lyrics.curvatureSideDeg`, which starts at 0, because a link sets the sides' own angle, never "the same as the top and bottom".
- What is read in composition, multiplied by time, gating a clock or a sensor, stepped quality, and projectM's own settings are not targets. Do not add one to make a request work; say why it cannot move.
- The CRT's curve and tilt are targets, because taps are bent through the shape the tube is drawn with: `MainActivity.dispatchTouchEvent` takes it at the first finger down and holds it to the last finger up, so a tap lands where it was seen and a held finger does not drift with the beat.
- The cover glow's two (`lyrics.artGlowRadiusDp`, `lyrics.artGlowBrightness`) are drawn in cover-art view, so the player runs them there (`VisualLinkTargets.COVER_KEYS`), not only while the lyrics show. Their sliders are in Settings › Appearance, inside `StudioMusicLinkScope`.
- Every target has a Studio slider that names it (`linkKey = "…"`, or `glassKey(linkArea, "…")` on the glass tabs), and every name has a target.
- A linked knob keeps and saves its own value (`Route::base`), and the native follower gives the same numbers as the Kotlin one.
- Links sync with the settings (`VISUAL_LINKS_JSON` and `MUSIC_LINKS_PAUSED` are in `SETTINGS_SYNC_KEYS`) and travel in theme packs (`ThemePack.links`, cleaned by `VisualLinkCodec.sanitize`). A file from a newer version still opens; a target this build does not know is dropped.

### Workflow

1. For a new target: the field must be read in a draw or layer block. Add it to `VisualLinkTargets` with its clamp bounds and its `Need`, and give its slider the key.
2. Check the drivers that run links: the app's (`MainActivity`: `panels.` and `tv.`), the player's (`MainPlayerRoute`: `player.`, `lyrics.`, `visualizer.`), and the Studio's preview (which `StudioMusicLinkScope` also runs over Settings › Appearance).
3. Walk the edges: the master pause, a link paused on its own, an editor closed without saving, a theme pack applied and undone.

### Completion gates

- `VisualLinkTargetsTest` (every target reads and writes its own setting, moves nothing else, stays in range, has its slider) and `MusicLinkFollowerTest` pass; for knobs, `run_host_tests.sh` runs `mod_route_test` against the same numbers.
- A linked setting moves on screen with no recomposition per frame.

## Playbook: Realtime Audio

Use for DSP, Atmos/spatial processing, equalization, loudness, normalization, native code, JNI, buffers, resampling, or audio callback changes.

### Non-negotiable invariants

- No allocation, blocking I/O, locks with unbounded wait, logging storms, or exception construction in realtime audio callbacks.
- Buffer sizes, channel counts, sample rates, frame counts, ownership, and lifetimes must be explicit at Kotlin/native boundaries.
- Parameter updates crossing threads must be atomic, lock-free, or safely handed off outside the realtime callback.
- Bypass and fallback paths must remain audible and safe when a feature is unavailable.
- DSP changes must not silently alter gain, clipping behavior, latency, or channel layout.
- The DSP library builds with `-ffast-math -fno-exceptions`. Under the first, `std::isfinite` may compile to `true`, and on the phone's clang 21 so may a plain bit test: test finiteness with `dspIsFinite`/`dspAllFinite` (`dsp/util/finite.h`), which read the bits behind an asm barrier the compiler cannot see through. `finite_guard_test` checks them with the NDK under qemu; `engine_stress_test` on the host cannot catch this fold. Under the second, a throw is an abort: parse with `strtof`, never `stof`.
- An oversampled effect delays its output by `latency()` samples. The engine aligns the dry blend and the other buses to it; anything new that mixes an oversampled path with an unoversampled one must do the same, or it comb-filters.

### Workflow

1. Trace the full signal path from Media3/decoder input to device output, including JNI calls and bypass paths.
2. State the processing contract: sample format, interleaving, channels, frames, latency, and valid parameter ranges.
3. Inspect callback code for allocation and synchronization hazards.
4. Add deterministic native unit tests for math and boundary behavior. Use fixtures or generated signals with tolerances, not subjective listening claims.
5. Add Kotlin/JNI contract tests where practical: invalid sizes, unsupported layouts, lifecycle/reinitialization, and rapid parameter changes.
6. Register native test executables with CMake/CTest (or the repository's chosen runner) so existing native test sources actually run. The Atmos tests now have a CTest project of their own (`atmos/tests/`); note that CI runs no tests at all yet (see Known Review Targets).

### Completion gates

- Silence, impulse, constant tone, near-clipping input, and invalid-parameter cases are covered when relevant.
- Bypass output is verified.
- No new realtime allocation or blocking path is introduced.
- Native tests are discoverable and executable, not merely present as source files.
- `dsp/tests/run_host_tests.sh` passes, including `engine_stress_test` under ASan/UBSan (with `-ffast-math`, as on the phone) and its chaos run under TSan. A new effect gets swept automatically once `snapin_ranges.csv` (`app/src/test/resources/`) carries its parameters.
- For Atmos changes, the `atmos/tests` CTest project passes (commands in `AGENTS.md`).
- The change documents any measurable latency or gain impact.

## Playbook: Playback Routing

Use for player services, MediaSession/Media3, notification actions, audio focus, queues, deep links, OAuth callbacks, Cast, Android Auto, exported components, or playback intents.

### State model

Treat playback as one authoritative state machine. UI, notification, headset controls, Auto, Cast, and restored sessions are command sources or projections—not independent players.

For every command or state change, identify:

| Concern | Questions |
|---|---|
| Authority | Which component owns the canonical queue and playback state? |
| Lifecycle | What happens after process death, service recreation, disconnect, or device change? |
| Routing | Is the command local, remote/Cast, or ignored because the route is unavailable? |
| Identity | Are media IDs stable and safely decoded? |
| Security | Can an untrusted app invoke this intent, service, receiver, or deep link? |
| UX | Do UI, notification, Auto, and Cast show the same item and position? |

### Workflow

1. Build a command/state matrix for play, pause, seek, next, previous, queue replace, route connect/disconnect, and restore.
2. Follow each affected command from entry point to session/player and back to observers.
3. Validate intent/deep-link data before using it. Minimize exported surfaces and require appropriate permissions or explicit targeting.
4. Test local playback first, then route transitions, process recreation, notification controls, and Auto/Cast integrations touched by the change.
5. When upgrading Media3, read the official migration notes for every crossed version and update extension compatibility together.

### Completion gates

- There is one canonical queue/state owner.
- Commands are idempotent where retries can occur.
- Local <-> remote transitions preserve the intended item, position, and play state.
- Exported components and deep links reject malformed or unauthorized input.
- Recovery behavior is tested for process/service recreation.

## Playbook: Radio Ranking

Use for radio, discovery, recommendations, candidate generation, scoring, diversity, deduplication, history, or queue seeding.

### Ranking contract

Before implementation, define:

- the candidate sources and eligibility filters;
- every score feature, range, direction, and default;
- how missing metadata is treated;
- deduplication identity;
- recency/repetition penalties;
- diversity constraints;
- tie-breaking and random seeding;
- fallback behavior when too few candidates remain.

Keep candidate generation, feature extraction, scoring, and selection as separable stages. Prefer pure functions for scoring and deterministic selection under a fixed seed.

### Workflow

1. Write the ranking contract in code comments or tests before changing weights.
2. Normalize features before combining them; prevent one unbounded signal from dominating accidentally.
3. Preserve explainability with structured debug output outside hot/user-facing paths: candidate ID, feature values, penalties, final score, and rejection reason.
4. Test invariants instead of only snapshots: excluded items never appear, duplicates collapse, penalties reduce rank, ties are deterministic, and fallbacks return usable queues.
5. Measure changes on fixed fixtures representing sparse libraries, large libraries, missing metadata, repeated artists, and exhausted candidates.

### Completion gates

- Same inputs and seed produce the same result.
- Missing metadata cannot yield NaN/infinite scores or crashes.
- Deduplication and diversity rules have explicit tests.
- Weight changes include before/after fixture results.
- Ranking diagnostics do not expose sensitive user data or flood production logs.

### Discovery: what a row is allowed to claim

Discovery rows carry a stated reason, and the reason is a claim about evidence. "Ranked by plays" means a chart; "its most-played artists" means the artist tier; "by way of X" means the row was borrowed from a neighbouring genre; "matched by name" is reserved for the one curated seed that names no genre at all. A row that silently falls back to a catalogue search for a genre's *name* is the bug this contract exists to prevent — a name search ranks records that *say* the genre above records that *are* it, which is exactly the query machine-generated filler is written to win. If a source cannot fill a row, the honest outcomes are to borrow and say so, or to come up short; never to substitute a weaker source under a stronger row's reason line.

Budgets are part of that contract. A per-shelf timeout only means something if the work it bounds is actually gated, so shared network permits, request coalescing and cache lifetimes belong in the ranking review, not just in the performance one: a shelf that misses its budget does not degrade gracefully, it falls through.

## Playbook: Cloud Sync

Use for anything that writes a favourite, playlist, playlist entry, EQ or mixer preset, or a synced setting, and for anything in `data/sync/`. The device (Room + DataStore) and Supabase each hold a full copy, and a pull runs by itself on every launch, so a write that reaches only one side is undone by the other on the next launch — local deletes resurrected by the pull, or cloud settings wiped by a push.

### Non-negotiable invariants

- **Every library edit is queued, not fired.** Write Room first, then `SupabaseSyncRepository.queueChange(kind, key, op)`. It holds the edit in `SyncOutbox` until Supabase accepts it. A bare `syncScope.launch { push… }` loses the edit whenever the network does, and a lost delete comes back on the next pull.
- **The pull never re-inserts a pending delete.** `pullLibrary` flushes the outbox first and skips any key still in `SyncOutbox.pendingDeletes`. A new synced table needs both.
- **Set membership is insert-if-not-exists; documents are newest-edit-wins.** Favourites and playlists never overwrite local rows on pull; presets compare device clocks via `cloudCopyIsNewer`. Don't swap one policy for the other.
- **A settings push never removes a cloud key and never blind-writes.** `pushSettings` reads the cloud row, sends only keys edited since the last agreed snapshot (`SettingsSyncCodec.changedSince`), merges them in (`merge`), and writes nothing if the cloud can't be read or parsed.
- **Nothing is pushed until a settings pull has reached the cloud** (`SettingsSyncCoordinator`). A pull that fails retries with backoff; it doesn't fall through to the watcher.
- **Edits are scoped to the account that made them.** Nothing is recorded while signed out, and a flush stops if the account changes under it.
- **No request goes out under the anon key.** `SupabaseAuthManager` sets `enableLifecycleCallbacks = false`, so the session stays signed in while the app works in the background, and `requireValidSession = true`, so a call with no session throws on the device. Turn either back on and background writes go out as anon: inserts fail with 401, and updates and deletes match no rows yet report success, which settles outbox deletes that never happened.
- **Plays upload idempotently.** A play whose push failed keeps a null `cloudRowId`, and `uploadUnsyncedPlayEvents` sends it on the next launch or Sync now. Each batch matches its plays to rows the cloud already holds (`matchPlays`, by track and millisecond) before inserting, and skips plays from the last ten minutes, which their own push may still be sending. Don't upload `play_events` any other way, or a lost reply becomes a duplicate play.
- **A synced setting is a choice, not state.** Anything the app rewrites by itself as it runs (the preset on screen, a position, a counter) stays off `SETTINGS_SYNC_KEYS`: every change to a synced key is a read and a write of the settings row.
- **A saved song says which service its id is from.** `favorite_tracks.source` and `playlist_tracks.source` (`TrackSource`: tidal, qobuz, deezer, apple, subsonic, local) are filled by `LibraryRepository.sourceOf` and taught back to `QobuzIdRegistry` on read (`rememberSource`). Qobuz, Deezer, Apple and TIDAL ids are plain numbers in overlapping ranges, and the registry is per device, so without it a fresh install or the website plays every one as TIDAL's. Rows from before it have none; the website (tryptz/tryptify.com) then reads the service from the cover's host (`wp-sources.js` `serviceOf`).
- **Push helpers report, they don't pretend.** They return `false` when Supabase refused; `pushAll` counts refusals so the Sync button never reports a backup that isn't there.

### Workflow

1. List every local write the change adds or touches, and for each: which `SyncKind` it is, what its cross-device key is, and what the pull does with the cloud copy of that row.
2. Queue the write after the Room write. For a new table: a `SyncKind` (ordered before anything RLS makes depend on it), a `send` branch, pending-delete filtering in `pullLibrary`, and a `pushAll` section.
3. Check the Supabase side read-only first: unique keys, `ON DELETE CASCADE`, and RLS policies decide whether a delete or upsert can succeed at all.
4. Walk the offline cases: edit offline then launch online; edit on two devices; delete on one device while the other is offline; sign out and in as a different account.

### Completion gates

- An edit made offline reaches the cloud on the next flush, and a delete made offline is not resurrected by any pull.
- A push can't remove or overwrite a cloud value this device didn't change.
- The pure rules have JVM tests (`SyncOutboxTest`, `SettingsSyncCodecTest`, `PresetSyncMappingTest`).

Known limitation: a delete made on one device reaches another only if that device hasn't pushed the row since. The manual Sync (`pushAll`) re-uploads every local row, so it can re-create a row deleted elsewhere. Fixing that needs a server-side tombstone (a `deleted_at` column), which is a schema change.

## Playbook: Trypt Mania

Use for the rhythm game: its screens (`ui/glyph/`); its engine, charts, packs, timing and multiplayer (`glyph/`); chart generation (`audio/stepmania/`); its native engine (`cpp/glyph/`); its servers; and the CRT effect, which began as its TV shader and now also draws over the app or the lyrics.

### Source of truth

- The look: `docs/ui-invariants.md` "### Trypt Mania". The playfield is the mode's own (`GlyphTheme`); the menus follow the app's theme, glass and components. Read it before any screen change.
- The rules of play exist twice: `cpp/glyph/glyph_engine.cpp` (its own `.so`, loaded the first time the mode opens, built without `-ffast-math` because it compares against window edges exactly and keeps NaN as "no offset") and its Kotlin twin, `glyph/engine/GlyphKotlinEngine.kt`, which the JVM tests drive and which plays if the native library cannot load. Every comparison and float operation runs in the same order in both.
- Charts and packs: `glyph/chart/` (SSC parsing and writing, timing) and `glyph/pack/` (StepMania packs). Generated charts: `audio/stepmania/`, below.
- The CRT effect: `GlyphTvShader` and `GlyphTvTuning` (`glyph/engine/GlyphPlayerOptions.kt`), drawn by `ui/glyph/GlyphTvShader.kt`. Its presets (`glyph/engine/CrtPreset.kt`, `ui/settings/crt/`) serve the app-wide effect, the Lyrics FX tab and Trypt Mania's Graphics alike.
- Multiplayer: `glyph/multiplayer/` (`RoomClient`, `RoomProtocol`), against a server that serves `ApiService.MULTIPLAYER`. The server is `tryptify-servers/rooms` (Cloudflare; ported from `tryptz/tryptify-party`'s Rust, whose `src/protocol.rs` is still the wire format it keeps), and a field added on one side is added on the other. A song the host shares goes over HTTP beside the socket (`/api/multiplayer/song`, with the phone's `X-Room-Key`), never through it.
- The StepMania noteskin and theme art are generated by scripts (see `AGENTS.md`); never hand-edit the PNGs.
- The built-in chart and rooms server addresses are build secrets (`AGENTS.md`). Never print or commit them, in code, tests or docs.

### Workflow

1. Read the "Trypt Mania" invariants and the code that draws the screen you are changing.
2. A change to the rules of play changes the C++ and the Kotlin together, in the same order of operations, and runs both test sets. Change one and a run is judged differently on and off the phone.
3. A change to the CRT effect is checked in all three places it draws: over the whole app, over the lyrics only, and over the playfield. While it reshapes the whole app (a curve, a tilt, a frame or a zoom), taps are mapped through the tube (`tubeSource`), and while it curves the app the lyrics' own curve is set aside.
4. New user-facing text goes in all eight languages; `trypt_mania` stays untranslated. No look is named after where its shader came from, except CRT-Royale.

### Completion gates

- The mode's JVM tests pass: everything under the `glyph` packages (the `Glyph*Test`s, `StepManiaPackTest`, `StepManiaScoringTest`, `RoomProtocolTest`, `CrtPresetTest`, `GlyphTvTuningTest`) and `audio/stepmania` (`ChartQueueTest`, `ChartPaceTest`, `EnvelopeParityTest`, `RemoteStemAnalysisTest`, `StepManiaMapGeneratorTest`, …): `./gradlew :app:testDebugUnitTest --tests '*.glyph.*' --tests '*.audio.stepmania.*'` (35 classes at 1.9.4).
- The native engine test passes: `glyph/tests` under CTest (commands in `AGENTS.md`).
- A screen change is checked against its invariants on a device, in a light and a dark theme.

### Charting is on servers, never the phone

Charts are separated by [TryptMania Parser](https://github.com/tryptz/TryptMania-Parser) servers. A server under Settings › Connections is detected as `ApiService.STEMS` (its `/api/stems/info` answers `{success: true}`), and the probe records what it separates on, `cuda` or `cpu` (`ApiServer.stemDevice`); a server added before that learns it from its first song (`RemoteStemAnalysis.learnDevice`). Trypt Mania's **Fast** mode tries the GPU servers, then any that has not said, then the CPU ones; **Slow** goes to CPU servers only, since an unknown server may be a GPU and Slow must not bill one (`RemoteStemAnalysis.planFor`). The song file goes up; the drums' and each layer's envelope come back (`RemoteStemAnalysis.parse`), not stems. No server, or none that manages the song, and the chart fails with that said: there is no separator on the phone.

The phone's separators were removed: ONNX Runtime with Qualcomm's QNN (the NPU and GPU backends, over 150 MB of native libraries), the model import and download code, and the built-in DSP separator. The server now runs those models. Do not bring a runtime back for a fallback; the fallback is the CPU server. `MonochromeApp` deletes the old model files (`stem-models`, `stem_npu_cache`) on start.

Charting is a line, `ChartQueue`, kept on disk and drained by one `StepManiaGenerationWorker`, with as many songs in flight as the server says it separates at once plus one uploading ahead, at most 43 (`StepManiaGenerationWorker.lanes`, held by `ChartPaceTest`). Generate joins the line; a folder heading's pill and "Chart every song without one" put many in at once, after `GlyphBatchDialog` shows each mode's time and cost (`ChartPace`, learned from the servers' own timings and `pricePerHour`/`idleSeconds`, never by waking one to ask). A song's failure is counted and the batch goes on. `ChartQueueTest` holds the rule that a song added as the worker stops still gets a worker.

The server's envelopes are a port of `TransientEnvelopeBuilder`, held to it by `stemsapi/envelope_parity.json`, which both repositories test against (`EnvelopeParityTest` here): change the builder and the server's port with it, regenerate the fixture there, and copy it here. `STEMS` is not a catalog, and neither are `LYRICS` (the same servers timing lyrics) and `MULTIPLAYER` (the rooms server): every `when` over `ApiService` gives them no search and no quality, and a `STEMS` pill says GPU or CPU.

Lyrics timing runs on the same servers: `ServerLyricsEngine` keeps its own line, drained by `LyricsSyncWorker`, the way charts are, and a song with no file on the phone is downloaded first.

## Engineering Gates

Use these focused external skill categories when they are available in the agent environment. Their recommendations are inputs, not substitutes for inspecting Tryptify's current code.

### Android testing setup

Close the instrumentation gap with a small, high-value `androidTest` foundation before broad UI automation. Prioritize navigation smoke tests, settings persistence, service/session integration, and critical player flows. Keep JVM tests for pure logic and native tests for DSP math.

### Android performance profiling

Profile before optimizing. For Compose work, inspect recomposition and allocation. For playback, inspect startup, underruns, CPU, memory, and battery under realistic sessions. Record the scenario and device/build type with results.

### AGP 9 migration

Treat the current opt-outs for built-in Kotlin and the new DSL as temporary migration debt. Upgrade AGP, Gradle, Kotlin/KSP/Hilt, and affected plugins as a coordinated change. Read current official compatibility guidance and use a clean build; never delete opt-outs without resolving the reported incompatibilities.

### R8/ProGuard analysis

Replace broad package-wide keep rules with evidence-based rules. Confirm reflective/serialized entry points, generate a release build, inspect warnings and mapping/usage outputs, and exercise startup, playback, OAuth/deep links, Cast/Auto, and persistence. Remove obsolete Appwrite rules only after verifying no dependency or serialized name still requires them.

### Intent security

Review every exported service/activity/receiver and all OAuth/playback deep links. Validate scheme, host, path, extras, and caller assumptions; use explicit intents where possible; apply permissions and `exported=false` where external invocation is not required. Reassess whether global cleartext traffic is necessary or can be scoped with network security configuration.

### Compose performance

Use stable parameters, keyed lazy items, remembered derived values, and immutable collections where they materially reduce recomposition. Validate with tooling rather than mechanically adding annotations.

## Test Matrix

Run only applicable rows while developing; before merging, run the broadest reliable checks for the affected layers.

| Changed layer | Minimum verification |
|---|---|
| Pure Kotlin/domain logic | Targeted JVM tests, then module JVM suite |
| Compose visuals/settings | JVM logic tests, relevant UI/instrumentation tests, manual boundary preview |
| Media3/service/routing | JVM tests plus device/emulator session and lifecycle checks |
| Native DSP/JNI | Registered native test target plus Kotlin/JNI integration check |
| Manifest/security | Manifest inspection, malformed-intent/deep-link tests, affected user flow |
| Gradle/dependencies | Clean sync/build, unit tests, release build when shrinker/runtime behavior may change |
| R8/ProGuard | Release build, warning review, mapping/usage inspection, release smoke test |

The two commands that work from a bare clone are the ones in `AGENTS.md`:

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

The native suites need only a desktop compiler and CMake, no device or NDK: `app/src/main/cpp/dsp/tests/run_host_tests.sh`, and the `atmos/tests` and `glyph/tests` CTest projects (commands in `AGENTS.md`).

Anything that assembles an APK — `assembleDebug`, any release build, the R8 and clean-build rows above — needs the git submodules (`third_party/projectm`, `libusb`) checked out. Without them it fails for reasons unrelated to the change, so do not report that as a regression, and do not claim a shrinker or release-build gate passed on a tree where it could not have run.

Several JVM tests exist specifically to hold the UI invariants in `docs/ui-invariants.md` — `LightSchemesTest`, `CustomSchemeTest`, `GlobeLandClipTest`, `SettingsSearchIndexTest`. They are the guarantee, not a formality. If one fails, fix the code; do not loosen the threshold.

## Known Review Targets

Re-verified at `281c07a2` (1.9.4) on 2026-10-19; every item below is true at that commit. Items marked resolved are kept as worked examples. Re-check the rest before acting — and treat each as a candidate for its own focused pull request, not as a batch.

**Glass Surfaces — resolved.** The `player-visuals-themes` skill's tables and its `validate_theme_ranges.py` had drifted from the model: fourteen wrong Player Glass defaults, `hazeBlurDp`/`hazeTint` undocumented, `miniProgressBar` missing from the personal set, a `popAmount` field that no longer exists, and a `bodyOpacity` floor copied from the Lyrics FX sibling. The validator now reads the bounds, the personal set and the defaults **out of the Kotlin** and checks `SKILL.md`'s tables against them, so the drift cannot recur silently — a field it cannot check is reported, and an unreadable model exits 2 rather than passing. Run it alongside `./gradlew :app:testDebugUnitTest --tests '*LyricsFxSettingsTest' --tests '*PlayerGlassSettingsTest'`. Re-checked at 1.9.4, after the lyric curve fields (`curvatureDeg`, `curvatureSideDeg`, `curvatureTarget`) arrived: the validator passes and both tests are green. It parses 17 of the 19 glass presets; Float and Clear are references to `FLOAT` and `CLEAR`, which it cannot read, so only the unit tests cover them.

**The "missing" ORDER BY indexes are measured and not worth adding.** Six list
queries plan as `SCAN … USE TEMP B-TREE FOR ORDER BY` — `favorite_tracks`,
`favorite_albums`, `favorite_artists` (`data/db/entity/Entities.kt:10,27,41` have
no `indices` at all), `user_playlists`, `downloaded_tracks` and `collections`.
The plan looks alarming and the fix does not pay. Measured against the real
schema (taken from the generated `MusicDatabase_Impl`) on SQLite 3.45: at 5,000
favourites an index on `addedAt` takes the query from 9.14 ms to 8.47 ms, and on
the 200–800 row tables it changes nothing. At 50,000 rows it is *slower* —
125.4 ms scanning, 143.9 ms through the index — because `SELECT *` via an index
scan does indirect row lookups where a table scan is sequential. Six indexes
plus a Room migration would buy under a millisecond, cost a write on every like,
download and playlist edit, and lose time on a large library. Do not add them
without a measurement that contradicts this one.

The same probe found where the time actually goes: `getAllTracks`
(`data/local/db/LocalMediaDao.kt:19`) returns the whole library on every
emission and costs ~118 ms at 20,000 tracks *already using its index*, and
`searchTracks` (`:150`) is ~63 ms per search, a leading-`%` LIKE no index can
serve (it now matches `genre` too, so it is if anything slower). Neither is an
indexing problem; both are "return fewer rows" problems. Paging 3 has since
come in for the library's song list (`LocalMediaDao.pagedBy…`, through
`LocalMediaRepository.pagedTracks`), but `getAllTracks` is still there, and
its remaining caller takes the whole list: Trypt Mania's song list
(`glyph/data/GlyphSongRepository.kt:58`, through
`LocalMediaRepository.getAllTracks`). The search is not paged.

**Instrumentation coverage is absent.** `app/src/` tracks only `main` and `test`; there is no `androidTest` source set at all. Navigation, settings persistence and session integration have no device-level coverage. The `:baselineprofile` module drives the app on an emulator or phone, but it records a profile and asserts nothing.

**Native Atmos tests — resolved as a target, but nothing runs them.** `app/src/main/cpp/atmos/tests/CMakeLists.txt` is now a host CMake project that builds every test source there as its own CTest test: sixteen (the foundation's `atmos_tests`, QMF, JOC, OAMD, EMDF, E-AC-3 header, object engine, HRTF render/motion/polish, the two pipelines, the speaker renderer, the channel placer), all passing on a desktop compiler at 1.9.4. The app's `app/src/main/cpp/CMakeLists.txt` still only type-checks the headers for Android. Nothing runs the suite by itself: `run_host_tests.sh` runs only `channel_placer_test` from it, and CI runs no tests (next item).

**CI runs no tests, and builds without Trypt Mania's servers.** `.github/workflows/main.yml` is the only workflow. On a `v*` tag, or by hand, it builds and signs a release and checks the signature. It runs no JVM unit tests and none of the native suites (`run_host_tests.sh`, `atmos/tests`, `glyph/tests`), and it sets neither `TRYPTMANIA_CPU_SERVER` nor `TRYPTMANIA_ROOMS_SERVER`, so the APK it builds has no built-in chart or rooms server (see `AGENTS.md`).

**No baseline profile is committed.** `app/src/release/generated/baselineProfiles/` holds no tracked file at 1.9.4, so a release installs only the profiles the AndroidX libraries ship, and nothing of this app's own startup. Recording one needs an emulator or a phone (`AGENTS.md`, Baseline profile).

**Build-toolchain migration debt.** `gradle.properties` carries `android.builtInKotlin=false` and `android.newDsl=false` against AGP 9.0.0 with Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / Hilt 2.57.1, alongside Media3 1.5.1 and Ktor 3.0.3. These opt-outs are deliberate and load-bearing today; they are also the thing that has to be resolved rather than deleted.

**Two model comments name the Studio's old places.** `PlayerGlassSettings` says it is tuned in the Studio's "Player Glass" tab, and `LyricsFxSettings` that the Studio is under Settings › Interface. The tabs are Player and UI panels, and the Studio opens from Settings' Visual Studio chip or from the player. Comments only; the `player-visuals-themes` skill has the current names.

**Keep rules are package-wide, and one of them keeps nothing at all.** `app/proguard-rules.pro` holds `-keep class io.ktor.** { *; }` and `-keep class androidx.media3.** { *; }`, which keep two large dependencies whole rather than keeping their reflective and serialized entry points. It also holds `-keep class io.appwrite.** { *; }` while there is no Appwrite dependency in `app/build.gradle.kts` or `gradle/libs.versions.toml` — the only surviving reference is a prose comment in `MainActivity.kt` describing OAuth history, so that rule matches nothing. Narrowing the first two needs release-build evidence; dropping the third needs only a check that no serialized name still depends on it.

**Exported surfaces and cleartext are unreviewed.** `AndroidManifest.xml` declares `android:usesCleartextTraffic="true"` application-wide and five components with `android:exported="true"`: one activity (`MainActivity`), two services (`PlaybackService`, `MonochromeMediaBrowserService`) and two receivers (`MediaButtonReceiver`, `NowPlayingWidgetReceiver`). Each needs its caller assumptions checked, and the cleartext allowance is a candidate for a scoped `networkSecurityConfig`.

## Definition of Done

A Tryptify agent task is done when:

- the requested behavior is implemented without unrelated feature loss;
- model, persistence, UI, runtime, and compatibility paths remain consistent;
- relevant automated tests pass and manual/device checks are reported honestly;
- performance, realtime, lifecycle, and security invariants affected by the change were reviewed;
- documentation or playbooks are updated when their contract changed;
- remaining risk is concrete and visible to the reviewer.
