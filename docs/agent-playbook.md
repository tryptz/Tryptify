# Tryptify Agent Playbook

Repository: `tryptz/Tryptify`
Prepared: 2026-08-21 · Review targets re-verified against `main` on 2026-08-21

This file is durable project context for coding agents working on Tryptify. Treat the repository itself as the source of truth whenever a path, field, version, or command has changed.

## Paste into Project Instructions

You are working on Tryptify, an Android music player with a native audio engine, Media3 playback, Jetpack Compose UI, radio/discovery ranking, and configurable glass surfaces.

Before changing code:

1. Read the repository-root `AGENTS.md` and any nearer `AGENTS.md` that governs the files you will touch.
2. Inspect the current implementation, tests, Gradle configuration, and call sites. Never rely on an old field list or remembered API.
3. Route the task through the matching playbook in `docs/agent-playbook.md`:
   - themes, haze, glass, mini-player visuals -> Glass Surfaces
   - DSP, JNI, Atmos, loudness, normalization -> Realtime Audio
   - player service, Media3, Cast, Auto, intents, deep links -> Playback Routing
   - radio, recommendation, scoring, discovery -> Radio Ranking
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
| Native DSP, Atmos, JNI, buffers, audio callbacks | Realtime Audio | Native tests and realtime-safety audit |
| Media3 session/service, notification, Cast, Auto | Playback Routing | Intent/security review for exported surfaces |
| Radio queue, ranking, discovery, recommendations | Radio Ranking | Deterministic tests and observability |
| AGP/Kotlin/KSP/Hilt upgrade | Engineering Gates | Full clean build and migration review |
| R8/ProGuard or release-size work | Engineering Gates | Release build plus keep-rule evidence |

The repository also ships a `player-visuals-themes` skill under `.claude/skills/`. It is the detailed reference for authoring Lyrics FX and Player Glass themes; this playbook's Glass Surfaces section is the process around it. Where the two disagree, the model's own `clamped()` wins — see the Known Review Targets below, which record where that skill is currently wrong.

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

## Playbook: Realtime Audio

Use for DSP, Atmos/spatial processing, equalization, loudness, normalization, native code, JNI, buffers, resampling, or audio callback changes.

### Non-negotiable invariants

- No allocation, blocking I/O, locks with unbounded wait, logging storms, or exception construction in realtime audio callbacks.
- Buffer sizes, channel counts, sample rates, frame counts, ownership, and lifetimes must be explicit at Kotlin/native boundaries.
- Parameter updates crossing threads must be atomic, lock-free, or safely handed off outside the realtime callback.
- Bypass and fallback paths must remain audible and safe when a feature is unavailable.
- DSP changes must not silently alter gain, clipping behavior, latency, or channel layout.

### Workflow

1. Trace the full signal path from Media3/decoder input to device output, including JNI calls and bypass paths.
2. State the processing contract: sample format, interleaving, channels, frames, latency, and valid parameter ranges.
3. Inspect callback code for allocation and synchronization hazards.
4. Add deterministic native unit tests for math and boundary behavior. Use fixtures or generated signals with tolerances, not subjective listening claims.
5. Add Kotlin/JNI contract tests where practical: invalid sizes, unsupported layouts, lifecycle/reinitialization, and rapid parameter changes.
6. Register native test executables with CMake/CTest (or the repository's chosen runner) so existing native test sources actually run in CI.

### Completion gates

- Silence, impulse, constant tone, near-clipping input, and invalid-parameter cases are covered when relevant.
- Bypass output is verified.
- No new realtime allocation or blocking path is introduced.
- Native tests are discoverable and executable, not merely present as source files.
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

Anything that assembles an APK — `assembleDebug`, any release build, the R8 and clean-build rows above — needs the git submodules (`third_party/projectm`, `libusb`) checked out. Without them it fails for reasons unrelated to the change, so do not report that as a regression, and do not claim a shrinker or release-build gate passed on a tree where it could not have run.

Several JVM tests exist specifically to hold the UI invariants in `docs/ui-invariants.md` — `LightSchemesTest`, `CustomSchemeTest`, `GlobeLandClipTest`, `SettingsSearchIndexTest`. They are the guarantee, not a formality. If one fails, fix the code; do not loosen the threshold.

## Known Review Targets

Re-verified against `main` on 2026-08-21; every item below was still true at that commit. Re-check again before acting — and treat each as a candidate for its own focused pull request, not as a batch.

**Glass Surfaces — the shipped skill contradicts the model.** `.claude/skills/player-visuals-themes/` is what an agent is pointed at for theme work, and four of its statements are wrong against `domain/model/PlayerGlassSettings.kt` and `domain/model/LyricsFxSettings.kt`:

- `hazeBlurDp` (clamped `0f..80f`, default `40f`) and `hazeTint` (`0f..2f`, default `1f`) appear nowhere in `SKILL.md` or in the validator's `GLS_RANGES`, so nothing checks them.
- `miniProgressBar` is carried by `withPersonalFrom`, but the validator's `GLS_PERSONAL` set is `{sampleRings, tintColor, previewBg}`. The validator therefore treats a personal field as material — the exact class of mistake the Glass Surfaces gate "presets do not overwrite unrelated fields" exists to catch.
- `LYR_RANGES` carries `popAmount` (0, 0.2), which no longer exists on `LyricsFxSettings`; the real field is `pumpAmount`, and that one *is* validated correctly at (0, 0.25). The stale key is dead weight rather than a live failure, but it is what the skill's prose still documents.
- `GLS_RANGES` floors `bodyOpacity` at `0.2`, while `PlayerGlassSettings.clamped()` allows `0f..1f` and the shipped default *is* `0.2f` — a ghost-thin body is the documented intent of the field, so the validator rejects legitimate themes at the low end. The floor is correct for the Lyrics FX sibling `glassBodyOpacity`, which the model really does clamp to `0.2f..1f`; it looks copied across. `SKILL.md` separately states the `bodyOpacity` default as `0.5`.

**Instrumentation coverage is absent.** `app/src/` contains only `main` and `test`; there is no `androidTest` source set at all. Navigation, settings persistence and session integration have no device-level coverage.

**Native Atmos tests are source files, not a test target.** Twelve test sources sit in `app/src/main/cpp/atmos/tests/` (QMF, JOC, OAMD, EMDF, E-AC-3 header, object engine, HRTF render/motion/polish, pipeline), and `app/src/main/cpp/CMakeLists.txt` contains no `enable_testing`, `add_test`, or a test `add_executable`. Nothing builds or runs them.

**Build-toolchain migration debt.** `gradle.properties` carries `android.builtInKotlin=false` and `android.newDsl=false` against AGP 9.0.0 with Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / Hilt 2.57.1, alongside Media3 1.5.1 and Ktor 3.0.3. These opt-outs are deliberate and load-bearing today; they are also the thing that has to be resolved rather than deleted.

**Keep rules are package-wide, and one of them keeps nothing at all.** `app/proguard-rules.pro` holds `-keep class io.ktor.** { *; }` and `-keep class androidx.media3.** { *; }`, which keep two large dependencies whole rather than keeping their reflective and serialized entry points. It also holds `-keep class io.appwrite.** { *; }` while there is no Appwrite dependency in `app/build.gradle.kts` or `gradle/libs.versions.toml` — the only surviving references are prose comments in `MainActivity.kt` and `PocketBaseClient.kt` describing OAuth history, so that rule matches nothing. Narrowing the first two needs release-build evidence; dropping the third needs only a check that no serialized name still depends on it.

**Exported surfaces and cleartext are unreviewed.** `AndroidManifest.xml` declares `android:usesCleartextTraffic="true"` application-wide and five components with `android:exported="true"` (one activity, two services, two receivers). Each needs its caller assumptions checked, and the cleartext allowance is a candidate for a scoped `networkSecurityConfig`.

## Definition of Done

A Tryptify agent task is done when:

- the requested behavior is implemented without unrelated feature loss;
- model, persistence, UI, runtime, and compatibility paths remain consistent;
- relevant automated tests pass and manual/device checks are reported honestly;
- performance, realtime, lifecycle, and security invariants affected by the change were reviewed;
- documentation or playbooks are updated when their contract changed;
- remaining risk is concrete and visible to the reviewer.
