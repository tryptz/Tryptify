# Tryptify P0/P1 Implementation Roadmap — v2 (Baseline-Verified)

**Status:** current plan of record. Supersedes the v1 roadmap.
**Branch baseline:** `claude/new-session-5wfj0f` @ `8e8f7a0` · DB schema v13 · media3 1.5.1
**Effort units:** solo-developer weeks (assume full-time, includes tests + docs)
**Commit convention:** author as `tryptz`, no co-author trailers, no tool attribution.

---

## 0. Verified Current Baseline

Everything below was verified against source at the baseline commit. Cited paths are
real symbols; do not re-audit before starting a workstream — but do re-read the cited
function before editing it, because line numbers drift.

### 0.1 Playback architecture (single output seam)

- `player/PlaybackService.kt` — Media3 `MediaSessionService`, Hilt singleton. Builds a
  `NextRenderersFactory` subclass (FFmpeg extension, `EXTENSION_RENDERER_MODE_ON`,
  deliberate ALAC MediaCodec-selector override, `ImportanceMediaCodecAdapterFactory`
  to mitigate MediaCodec resource-manager dropouts).
- **`buildAudioSink` (from ~line 718) is the only output seam.** It constructs:
  - a `DefaultAudioSink` with `TryptifyAudioProcessorChain` (processors: channelDetector,
    atmosAudioProcessor, downmixProcessor, mixBusProcessor, autoEqProcessor,
    parametricEqProcessor, spectrumAnalyzerTap,
    `TeeAudioProcessor(ProjectMAudioTapProcessor(audioBus))`, plus `variRateProcessor`
    and `stretchProcessor`);
  - wrapped in `LibusbAudioSink` (delegate = the default sink; bypass path runs a
    subset of processors — ProjectM tap intentionally omitted);
  - exception fallback: `super.buildAudioSink()` (~793) inside `LibusbAudioSink` with
    no processors.
- `audio/usb/LibusbAudioSink.kt` (541 ln): bypass engage/de-engage, lazy engage with
  `lastEngageFailHash` throttle, wedged-pump watchdog. Fallback behavior exists but is
  ad-hoc — not transactional, not reported.
- `audio/usb/UsbExclusiveController.kt` (`@Singleton`, `Status` enum, `start()`, device
  picker), `LibusbUacDriver.kt` (JNI bridge), `BypassVolumeController.kt` (36 ln).
- `audio/UsbAudioRouter.kt`: route-change listener via `AudioDeviceCallback`.

### 0.2 Source resolution seam

- `player/StreamResolver.kt` (663 ln): `ResolvedMedia` data class (~27–41) is the de-facto
  provider return type; `resolveUnifiedTrack` (~153–192) dispatches on `PlaybackSource`
  subtype with a `when` — this is the SourceProvider dispatch point. Local-first:
  `LocalTrackLocator.findLocalSource` (~45, memoized + revision invalidation) wins before
  any network resolution.
- `domain/model/Models.kt`: `@Serializable sealed class PlaybackSource` — variants
  `HiFiApi`, `CollectionDirect`, `LocalFile`, `QobuzCached`, `AppleCached`, `RadioStream`.
  `UnifiedTrack` with `toLegacyTrack`.
- `player/LocalTrackMatching.kt`: fuzzy title/artist/duration matching, `pickBest`,
  `qualityScore`.

### 0.3 Network providers — Qobuz is a first-class, active catalogue

- **Qobuz is a primary, actively-used provider with its own track-id namespace.**
  `QobuzIdRegistry.isQobuzTrack()` guards resolution at `StreamResolver.kt:81` and `:141`,
  and the same guard is applied independently in `PlayerViewModel` (461, 1095),
  `HiFiApiClient` (952, 1049), `TrackDownloader` (118) and `TrackMappers` (135). The
  in-code reason is explicit and is the reason provider-scoped ids are invariant:
  *"Qobuz is its own catalogue, not a TIDAL fallback"* — handing a Qobuz id to TIDAL's
  `/track/` endpoint either 404s or streams a **different recording** under this track's
  title and artwork, with nothing downstream able to tell.
- **The "HiFi API" instances speak a Qobuz-shaped catalogue.** `data/api/HiFiApiClient.kt`
  (1674 ln, manifest/full-file) itself imports Qobuz models (`QobuzAlbumDetailEnvelope`
  et al.). Framing this stack as purely TIDAL is wrong; the TIDAL-namespace warning above
  survives as the *reason for* provider-scoped ids, not as a description of the catalogue.
- **Qobuz transport is two paths, both shipped:** `PlaybackSource.QobuzCached` whole-file
  park-then-play, **plus segmented partial streaming** — `data/cache/QobuzStreamUri.kt`
  (custom scheme), `QobuzPartialDataSource.kt`, `PartialStream.kt`,
  `SchemeRoutingDataSource.kt`, `QobuzStreamCacheManager.kt`, with JVM tests
  `QobuzStreamUriTest`, `PartialStreamTest`.
- Apple: `AppleCached` (Range-capable URL). Radio: `RadioBrowserClient`. Encrypted
  collections: `data/collections/*`.
- **Chromecast: does not exist.** `cast-framework` + media3-cast are declared dependencies
  (`app/build.gradle.kts:242,301`) with zero app-code usage — no `CastPlayer`/`CastContext`/
  `CastOptions` hits anywhere (grep-verified). The old roadmap's "keep Chromecast working"
  preserve-list entry described a capability that is not in the code.
- Credentials: instance URLs are user-configured (`CUSTOM_API_ENDPOINT`,
  `QOBUZ_INSTANCE_URL`, …) stored **plaintext in DataStore**.

### 0.4 DSP / processing (rich, shipped)

- `audio/resample/TryptifyAudioProcessorChain.kt`: custom `AudioProcessorChain` (ordering +
  `applyPlaybackParameters` routing), replaces `DefaultAudioProcessorChain`.
- Shipped processors: `MixBusProcessor` (TPDF dither for PCM16 quantization),
  `DspChain`/`DspEngineManager` (with `reapplyAfterEngineRecreated()`), `CrossfeedEffect`
  (**shipped** — `audio/dsp/crossfeed/CrossfeedEffect.kt`, injected in `PlaybackService`,
  tested in `CrossfeedEffectTest`), `AutoEqProcessor`, `ParametricEqProcessor`,
  `AtmosAudioProcessor`, `DownmixProcessor`, spectrum tap, ProjectM tap.
- `audio/resample/VariRateAudioProcessor.kt` + `SincKernel.kt`: windowed-sinc resampler,
  **speed/pitch only — not output-rate adaptation**. Output-rate conversion today is
  implicit in `DefaultAudioSink`/AudioTrack.
- USB bypass runs a processor subset (see §0.1); `LibusbAudioSink` takes its own
  `processors` list.

### 0.5 USB bypass / audio truth (partial)

- `audio/usb/BypassDiagnostics.kt` already captures the USB stream facts: sample rate,
  bit depth, channels, interface/alt-setting, endpoint address, maxPacketSize, bInterval,
  `uacVersion`, `clockSourceId`, feedback endpoint address, `isHighSpeed`, `bytesPerSample`;
  derived `hasFeedbackEndpoint`/`isUac2`; `ClockRateRange`; `StartError`/`StartFailure`
  taxonomy.
- `SettingsViewModel.usbBypassDiagnostics` → `BypassDiagnosticsCard` (`SettingsScreen.kt`)
  already surfaces it in UI.
- `LibusbAudioSink` knows whether bypass engaged.
- **"Bit-perfect" today is two label settings, not a state**: `usb_bit_perfect_enabled` /
  `usb_exclusive_bit_perfect_enabled` (`PreferencesManager.kt:349,351`).
- **No snapshot aggregates decoder → processing → output**, and DSP/EQ activity is reported
  into no truth model.

### 0.6 Data layer (one Room DB)

- **Exactly one Room database**: `data/db/MusicDatabase.kt`, `@Database` version **13**,
  24 entities (core library: favorites/history/play-events/playlists/downloads/lyrics/
  EQ+mix presets; local media: `LocalTrack/Album/Artist/Genre/Folder/ScanStateEntity`;
  collections: 6 entities). DAOs include `localMediaDao()` and `collectionDao()`. Only one
  `@Database` annotation exists in the app (grep-verified). Migrations present:
  `MIGRATION_8_9 … MIGRATION_12_13` — additive pattern established.
- **`exportSchema = false`** (`MusicDatabase.kt:72`). Room's `MigrationTestHelper` needs
  exported schema JSON, so the migration-test target in §7 is blocked until this is flipped
  to `true` with a `room.schemaLocation` KSP arg and the v13 schema checked in. That flip
  is part of PR-C, not a later cleanup.
- **The old roadmap's "two Room databases" claim is wrong.** Scanner entities were merged
  into `MusicDatabase`. (This contradicts one instruction in the original planning brief;
  the code is authoritative. See Appendix A, delta #10.)
- Scanner: `data/local/scanner/` (`MediaScanner`, `MediaStoreSource` — keeps `audio/dsf`
  MIME, `ScanCoordinator`/`ScanWorker`), `data/local/watcher/FileObserverService.kt`,
  `data/local/tags/TagReader.kt` (read-only `AudioTags`),
  `data/local/repository/LocalMediaRepository.kt`, `LocalLibraryRevision.kt`.
- Queue: `player/QueueManager.kt` (294 ln, in-memory; Fisher-Yates via
  `kotlin.collections.shuffle()` at ~226–253 preserving current track at index 0;
  **no persistence, no shuffle seed**). `player/UnifiedTrackRegistry.kt` (38 ln,
  `ConcurrentHashMap`) — **process-death fragile**.
- `PreferencesManager.kt` (2143 ln): all settings are hand-rolled DataStore keys;
  `SETTINGS_SYNC_KEYS` allow-list with per-key opt-out comments (e.g. 135, 177, 185);
  `DOWNLOAD_QUEUE_JSON` at 348/979. **No FeatureFlag registry exists anywhere**
  (grep-verified).
- Structured playback events: `PlayEventEntity`/`PlayEventDao` exist (history/analytics,
  consumed by `StatsViewModel`, `ListeningStatsViewModel`, `LibraryRepository`,
  `SupabaseSyncRepository`) but have no route/session-ID/error-code model. Closest error
  taxonomy: `BypassDiagnostics.StartError` and `debug/DebugLogBuffer`/`DebugLogCollector`.

### 0.7 Formats

- FFmpeg extension enabled with a broad codec set; `PlaybackService.kt` comment (~660):
  DSD, APE, TAK, WavPack, MPC, TrueHD, DTS. `MediaStoreSource.kt`: scanner accepts
  `audio/dsf` (the old 12-entry MIME allowlist silently dropped it — fixed).
- **Likely already decoding (verify, don't assume):** DSF→PCM, APE, WavPack, TAK, ALAC
  (MediaCodec override), plus whatever FFmpeg enables (AIFF, Opus…).
- **Genuinely absent:** CUE (zero hits), DoP/native DSD transport, DSD policy, SACD ISO,
  format sniffer / `DecodedAudioStream` normalization.

### 0.8 What exists elsewhere (so we don't rebuild it)

- Import: `PlaylistImportService` (fetch + match + progress), `CsvPlaylistParser`,
  `SpotifyImportForegroundService`. (`ApoProfileParser` is AutoEQ profile import — **not**
  playlists.) Export: none. M3U/PLS/WPL: none.
- Backup/sync: `BackupManager.exportLibrary/importLibrary` (JSON, legacy fallback),
  `LibraryRestoreCoordinator`, `SettingsSyncCoordinator` + `SettingsSyncCodec` +
  `SETTINGS_SYNC_KEYS` allow-list, `PresetSyncMapping`, `SupabaseSyncRepository` (913 ln),
  `PocketBaseClient`.
- Diagnostics: `debug/DebugLogBuffer`, `DebugLogCollector`, `CrashLogger`,
  `DebugScreenRecorder` (MediaProjection video). No `AudioPlaybackCapture` (zero hits),
  no diagnostic bundle ZIP, no USB detach-cycle harness.
- Local media persistence: `local_tracks/albums/artists/genres/folders/scan_state` tables +
  `LocalMediaDao`; folder roots via `USER_FOLDER_ROOTS_JSON`/`EXCLUDED_PATHS_JSON`; UI
  `ui/library/LocalLibraryTab.kt`.
- Tests: 74 JVM test files covering resample/EQ/dsp/cache (`CrossfeedEffectTest`,
  `VariRateAudioProcessorTest`, `QobuzStreamUriTest`, `PartialStreamTest`, plus
  `LightSchemesTest`, `CustomSchemeTest`, `GlobeLandClipTest`, `SettingsSearchIndexTest`
  which are invariant guards). **No PCM golden-fixture harness.**

---

## 1. Guiding Principles

1. **Extend, don't invent.** `ResolvedMedia` is the provider return type; `BypassDiagnostics`
   is the output-half of audio truth; `SincKernel` is the SRC kernel; `PlayEventEntity` is
   the event-seed; `QobuzStreamCacheManager`/`QobuzStreamUri` is the segmented-cache
   reference. New contracts wrap these.
2. **Preserve existing behavior.** Every seam change ships behind a feature flag defaulting
   **off**, with the legacy path as the flag-off branch.
3. **Additive Room migrations only** on `MusicDatabase` (v13 → v14+). There is one DB; every
   new table is one more additive migration following the `MIGRATION_12_13` pattern.
4. **Flags live in a registry, not a 2,100-line settings file.** New flags integrate with
   (or explicitly exclude themselves from) `SETTINGS_SYNC_KEYS`.
5. **Provider id namespaces are invariant.** No refactor may weaken the `isQobuzTrack`
   guard or merge Qobuz ids with HiFi/TIDAL ids.
6. Verify-then-scope: DSF/APE/WavPack/TAK/ALAC decoding is *verified by test*, not assumed,
   before declaring a codec gap.

---

## 2. Workstreams

### P0.0 — Contracts, feature flags, structured events *(net-new)*
- **SourceProvider** interface; adapters per `PlaybackSource` variant;
  `resolveUnifiedTrack`'s `when` becomes dispatch over providers; `ResolvedMedia` is the
  return type (extended, not replaced). The `isQobuzTrack` guard moves *into* the Qobuz
  provider's claim predicate — it does not disappear (risk R8).
- **OutputBackend** interface; `LibusbAudioSink` delegate/bypass refactored as two backends
  behind `buildAudioSink`.
- **ProcessingPlan**: an explicit, enumerable description of the active processor chain
  (channelDetector, atmos, downmix, mixBus, crossfeed, autoEq, paramEq, spectrum, ProjectM
  tap, variRate, stretch, bypass-subset) — DSP here is a rich snap-in chain, and the plan
  must enumerate it, not treat it as "a 3-band EQ".
- **FeatureFlag registry**: DataStore-backed, default-off, with an explicit
  `SETTINGS_SYNC_KEYS` policy (default: excluded; opt-in per flag).
- **Structured playback events**: extend the `PlayEventEntity` pattern with route (source
  type), session ID, sink/backend, error code (absorbing `BypassDiagnostics.StartError`
  codes). New table `playback_events` via additive migration.
- **Schema export enabled** (`exportSchema = true` + `room.schemaLocation`), v13 schema JSON
  committed, so every later migration is testable.
- **PCM golden-fixture harness** (JVM, `app/src/test`): reference WAV fixtures +
  bit-comparison utility, prerequisite for P0.1/P0.4 gates.

### P0.1 — Audio truth: migrate the label into a verified state
- **`AudioPathSnapshot`** aggregates decoder format (from `Format`), processing activity
  (each processor's `isActive()`/config via `TryptifyAudioProcessorChain`), and output truth
  (from `BypassDiagnostics` + `LibusbAudioSink` bypass state).
- **Migrate, don't reinvent:** `usb_bit_perfect_enabled` / `usb_exclusive_bit_perfect_enabled`
  become a migration (DataStore key read → classification flag) and the settings UI shows
  *verified* path state (`BypassDiagnosticsCard` extends into an AudioPath card) instead of
  a label toggle. The legacy keys keep working as the "requested" intent.
- Bit-perfect classification: PASS (decoder rate/depth == output, zero active DSP on the
  path), DEGRADED (with reason: resample / DSP active / bypass down), UNKNOWN.

### P0.2 — Device profiles + backends
- `OutputDeviceProfileEntity` (pattern: `MixPresetEntity`/`EqPresetEntity`) keyed on
  fingerprint: USB VID/PID (small addition to `LibusbUacDriver`/`UsbExclusiveController` —
  `BypassDiagnostics` already carries the rest), `UsbAudioRouter` device info,
  Bluetooth/wired name where available.
- **AAudio/Oboe output backend** (net-new; zero existing AAudio/Oboe code) as a third
  `OutputBackend` behind a flag.
- Transactional route switching: formalize `UsbAudioRouter` callbacks + `LibusbAudioSink`
  engage/fallback/watchdog into a state machine that emits structured events.
- **Chromecast: removed from scope.** `cast-framework`/media3-cast are unused deps —
  decision required (see Risk R1): remove in a housekeeping PR or flag for investigation.
  No Cast backend work in this roadmap.

### P0.3 — Formats, CUE, DSD *(scope reduced)*
- Step 1 is **verification, not implementation**: extend/add `FormatDecodingTest` asserting
  AIFF/ALAC/WavPack/APE/TAK/Opus/DSF/DFF decode through the FFmpeg extension (tiny real-file
  fixtures per codec, JVM where possible, instrumented if not). Record results in the plan
  appendix.
- Then implement the true gaps: CUE sheet parsing (per-track index/offset), format sniffer
  (extension-independent), DSD policy (DoP/native transport decision; DSD-over-PCM first),
  documented codec-support matrix.
- **Codec licensing gate** before shipping any bundled decoder binaries.

### P0.4 — SRC + dither
- **Reuse `SincKernel`** (proven windowed-sinc, tested via `VariRateAudioProcessorTest`) for
  a deterministic output-rate SRC processor; `VariRateAudioProcessor` stays speed/pitch-only.
  Insert as the final processor in `TryptifyAudioProcessorChain` (and in the bypass chain's
  processor list).
- Dither: generalize `MixBusProcessor`'s TPDF into a shared quantization stage with shape
  selection (TPDF/none initially); add headroom/clipping reporter on the processing half of
  `AudioPathSnapshot`.

### P0.5 — Network providers
- Formalize existing providers (HiFi/Qobuz catalogue, Qobuz partial streaming, Apple, radio,
  collections) as `SourceProvider` adapters — mostly refactor, low net-new.
- **Segmented caching is not net-new.** Any "shared segmented cache" must **extend
  `QobuzStreamCacheManager`**, not stand up a parallel one; `QobuzStreamUri` +
  `SchemeRoutingDataSource` + `QobuzPartialDataSource`/`PartialStream` is the reference
  pattern for every new range-read source adapter (UPnP/SMB/WebDAV). Generalizing the scheme
  and the cache manager is the first PR of this workstream, before any new protocol lands.
- **Net-new:** UPnP/DLNA client + renderer, SMB2/3 source, WebDAV source. All behind flags.
- **Credential security:** Keystore-backed storage for instance URLs/credentials (today
  plaintext in DataStore). Ship with a DataStore→Keystore migration for existing keys.

### P0.6 — Reliability & diagnostics
- Diagnostic bundle ZIP: `DebugLogBuffer`/`DebugLogCollector` output + `BypassDiagnostics` +
  recent `playback_events` + device/build info; explicit user consent flow.
- USB detach/reattach cycle test harness; long-run soak script on device matrix.
- `ImportanceMediaCodecAdapterFactory` already mitigates MediaCodec dropouts — keep, document.

### P1.1 — Unified catalog
- Additive migration on the **single** `MusicDatabase`: ratings, play/skip counters on local
  + unified tables, multi-value artist/genre normalization (`local_track_artists` cross-refs
  following the collections cross-ref pattern).
- **Identity namespacing is invariant:** unified identity must preserve `QobuzIdRegistry`
  namespacing — Qobuz ids are never merged with HiFi/TIDAL ids (see §0.3). Any unified key
  is `(provider, providerId)`, never a bare `Long`; cross-provider id routing keeps the
  `isQobuzTrack()` guard, covered by a golden routing test (risk R8).
- Extend `BackupManager`/`SettingsSyncCodec`/`PresetSyncMapping` scope to cover the new
  tables.

### P1.2 — Queue persistence + seeded shuffle
- Persist logical identity: `UnifiedTrack` is already `@Serializable` — persist queue as a
  `queue_items` table (additive migration; preferred) or JSON (pattern: `DOWNLOAD_QUEUE_JSON`
  at `PreferencesManager.kt:348/979`). Restore on process death; `UnifiedTrackRegistry`
  repopulates from it.
- Seeded shuffle: replace unseeded `shuffle()` in `QueueManager` (~226–253) with a seeded
  PRNG; persist the seed so un-shuffle restores original order.

### P1.2b — Smart playlists
- Rule AST (JSON), evaluator over `MusicDatabase` DAO queries, persisted as `smart_playlist`
  table (additive). Backup/sync coverage included.

### P1.3 — Tag writing
- Extend `data/local/tags/`: writer with atomic rewrite (temp-file + rename), respecting
  `LocalLibraryRevision`; `MetadataOverrideEntity` for non-destructive overrides where
  rewrite is unsafe. Artwork embedding alongside `AudioFileCoverFetcher`.

### P1.4 — Playlist export + M3U/PLS/WPL
- Import exists (CSV, Spotify). Add: M3U/M3U8/PLS/WPL parsers into `data/import_/`; export
  for all supported formats; extended-M3U metadata (duration/title) written losslessly.

### P1.6 — External audio capture
- `AudioPlaybackCapture` (API 29+): new `PlaybackSource.ExternalCapture` variant; DSP entry
  via `TryptifyAudioProcessorChain`; output via the `OutputBackend` seam. Usage-visibility
  gating surfaced in UI.

### P1.7 — Backup/sync completion
- Extend `BackupManager` + `SettingsSyncCoordinator` for: device profiles (P0.2), flags,
  smart-list ASTs, ratings/counters (P1.1), queue (P1.2). Versioned backup envelope; keep
  legacy fallback.

---

## 3. Effort Estimates (solo weeks)

| WS | v1 est. | **v2 est.** | Why it moved |
|---|---|---|---|
| P0.0 contracts/flags/events | 3 | **2.5** | `ResolvedMedia`/`PlaybackSource` already carry the contract shape; events seed exists; schema-export flip added |
| P0.1 audio truth | 3 | **2** | Output half is `BypassDiagnostics` extension; only aggregation + migration is new |
| P0.2 device profiles/backends | 5 | **4.5** | Chromecast backend dropped (didn't exist); AAudio backend unchanged |
| P0.3 formats/CUE/DSD | 4 | **2.5** | Codec breadth largely done via FFmpeg; verification + CUE + DoP remain |
| P0.4 SRC/dither | 3 | **2** | `SincKernel` reused; only adaptation + dither-stage generalization |
| P0.5 network providers | 6 | **5** | Providers formalization is refactor; segmented cache generalizes `QobuzStreamCacheManager` instead of being built; UPnP/SMB/WebDAV + Keystore unchanged |
| P0.6 diagnostics | 2 | **1.5** | Debug buffers + StartError taxonomy exist; bundle + harness remain |
| P1.1 catalog | 4 | **3** | One DB, additive migration pattern proven; normalization still real work |
| P1.2 queue | 3 | **2** | `UnifiedTrack` already serializable; persistence + seed only |
| P1.2b smart playlists | 3 | **3** | unchanged (net-new) |
| P1.3 tags | 3 | **2.5** | Reader/artwork exist; writer + atomicity + overrides remain |
| P1.4 playlists | 4 | **2** | CSV/Spotify import done; M3U/PLS/WPL + export remain |
| P1.6 capture | 2 | **2** | unchanged (net-new) |
| P1.7 backup | 2.5 | **1.5** | BackupManager + sync coordinators exist; scope extension only |
| **Total** | 44.5 | **35.5** | |

## 4. Epic Table (dependencies + exit criteria)

| Epic | Depends on | Exit criteria |
|---|---|---|
| E0.0 Contracts/flags/events | — | `SourceProvider`/`OutputBackend`/`ProcessingPlan` compiled; `FeatureFlag` registry with default-off + sync policy test; schema export on with v13 JSON committed; `playback_events` migration v13→v14; PCM fixture harness in `app/src/test` |
| E0.1 Audio truth | E0.0 | `AudioPathSnapshot` emitted per session; legacy bit-perfect keys migrated; UI card shows verified state; snapshot test |
| E0.2 Profiles/backends | E0.0, E0.1 | `OutputDeviceProfileEntity` migration; AAudio backend flag-gated; cast deps removed or ticketed; route switch emits events |
| E0.3 Formats/CUE/DSD | E0.0 | Codec decode verification table committed; CUE per-track playback; DoP flag-gated; support matrix in docs |
| E0.4 SRC/dither | E0.0 | Golden-fixture SRC parity test (SincKernel-based); dither shape option; clipping reporter feeding snapshot |
| E0.5 Providers | E0.0 | Segmented cache generalized from `QobuzStreamCacheManager` with Qobuz behavior unchanged; UPnP/SMB/WebDAV flag-gated; credentials in Keystore with migration; existing providers pass unchanged-behavior tests incl. the Qobuz routing golden test |
| E0.6 Diagnostics | E0.1 | Bundle ZIP with consent; USB cycle harness green 100 cycles |
| E1.1 Catalog | E0.0 | ratings/counters/normalization tables migrated; DAO tests; `(provider, id)` keying test |
| E1.2 Queue | E0.0 | queue survives process death; seeded shuffle round-trips |
| E1.2b Smart lists | E1.1 | AST eval + persistence + backup round-trip tests |
| E1.3 Tags | — | atomic write test incl. crash-mid-write; override entity |
| E1.4 Playlists | — | M3U/PLS/WPL import+export round-trip tests |
| E1.6 Capture | E0.0 | capture behind flag; DSP chain processes captured stream |
| E1.7 Backup | E0.2, E1.1, E1.2b | new scopes in export/import with version envelope tests |

## 5. Ordered PR Sequence

1. **PR-A** (E0.0): `FeatureFlag` registry + sync-allow-list policy + tests.
2. **PR-B** (E0.0): PCM golden-fixture harness + reference fixtures.
3. **PR-C** (E0.0): schema export on (v13 JSON committed) + `playback_events` table
   (v13→v14 additive migration) + `MigrationTestHelper` target + emission at
   `buildAudioSink`/`StreamResolver` failure points; absorb `StartError` codes.
4. **PR-D** (E0.0): `SourceProvider` interface + adapters over existing `PlaybackSource`
   resolvers; `ResolvedMedia` unchanged externally; **Qobuz id-routing golden test lands in
   this PR** (R8).
5. **PR-E** (E0.0): `ProcessingPlan` enumeration of `TryptifyAudioProcessorChain`
   (+ bypass chain).
6. **PR-F** (E0.1): `AudioPathSnapshot` + `BypassDiagnostics` extension + settings-UI
   verified-state card + legacy key migration.
7. **PR-G** (E0.0/E0.2): `OutputBackend` extraction of `LibusbAudioSink` delegate/bypass;
   route-switch transactionalization.
8. **PR-H** (E0.3): codec decode verification tests + documented matrix.
9. **PR-I** (E0.4): SincKernel-based output-rate SRC + dither stage + clipping reporter.
10. **PR-J** (E0.2): `OutputDeviceProfileEntity` (v14→v15) + profile store.
11. **PR-K** (E0.2): AAudio/Oboe backend (flag-gated).
12. **PR-L** (E0.5): Keystore credential migration.
13. **PR-M** (E0.6): diagnostic bundle + USB cycle harness.
14. **PR-N** (E1.2): queue persistence (v15→v16) + seeded shuffle.
15. **PR-O** (E1.1): ratings/counters/normalization (v16→v17).
16. **PR-P** (E1.2b): smart playlists (v17→v18).
17. **PR-Q** (E1.3): tag writer + overrides.
18. **PR-R** (E1.4): M3U/PLS/WPL import + export.
19. **PR-S1** (E0.5): generalize `QobuzStreamCacheManager`/`QobuzStreamUri` into a
    provider-agnostic segmented cache, Qobuz behavior byte-identical.
20. **PR-S2…S4** (E0.5): UPnP/DLNA, SMB2/3, WebDAV — each its own PR on top of PR-S1.
21. **PR-T** (E1.6): external capture.
22. **PR-U** (E1.7): backup/sync scope extension.
23. **PR-V** (housekeeping): cast-framework dep removal (pending go/no-go G2).

Each PR: `./gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest` green; invariant
tests (`LightSchemesTest`, `CustomSchemeTest`, `GlobeLandClipTest`, `SettingsSearchIndexTest`)
untouched unless the change is UI-scoped and re-verified.

## 6. Acceptance Gates per Workstream

- **P0.0:** every new flag defaults off and is exercised in both states by at least one test;
  flag-off build behaves byte-identically on golden fixtures.
- **P0.1:** for a known 44.1/16 file with DSP off and USB bypass engaged, snapshot reports
  PASS; enabling AutoEq flips to DEGRADED(reason=eq). Snapshot persisted with events.
- **P0.2:** route change (unplug USB → BT) completes without playback gap > threshold and
  emits event; profile reapplies on reconnect.
- **P0.3:** each claimed codec has a decode test; CUE image plays as per-track entries with
  correct offsets.
- **P0.4:** 44.1→48 golden fixture max sample deviation ≤ 1 LSB @ 24-bit; no clipping on
  0 dBFS input with dither on.
- **P0.5:** Qobuz partial-stream tests (`QobuzStreamUriTest`, `PartialStreamTest`) pass
  unmodified after the cache generalization; no plaintext credential reads remain for
  migrated keys; each new source has connect/browse/stream test (mock server OK).
- **P0.6:** bundle redacts credentials; harness passes 100 detach cycles.
- **P1.x:** every schema change additive; DAO round-trip tests; backup export→fresh-install→
  import restores all new scopes.

## 7. Quality Targets

- Unit test coverage on new modules ≥ 80% lines.
- No regression in existing suites; invariant tests stay authoritative.
- Migration tests for every Room version bump (v13→v18) using Room's `MigrationTestHelper`
  (requires the `exportSchema` flip in PR-C; add an `androidTest` target if absent).
- Audio-path changes verified by PCM fixtures, not just compile.
- No new ANR/jank on playback start (measure before/after PR-G, PR-K).

## 8. Risk Register

| # | Risk | Mitigation |
|---|---|---|
| R1 | cast-framework provenance unknown (dead dep vs. never-implemented feature) | G2 decision before PR-V; if uncertain, keep dep, do not build Cast work |
| R2 | FFmpeg codec verification may find DSF/APE gaps | Matrix from PR-H scopes P0.3 honestly before DoP work |
| R3 | Keystore migration could lock users out on restore | Migrate read-only fallback to plaintext until confirmed; write-through both stores for one release |
| R4 | Additive-migration discipline slips (e.g. altering `local_tracks`) | Every migration PR ships a `MigrationTestHelper` test; CI gate (needs PR-C schema export) |
| R5 | Flag registry drifts from `SETTINGS_SYNC_KEYS` | Policy test asserting registry↔allow-list consistency (E0.0) |
| R6 | Bypass chain processor additions change bypass timing | Golden fixtures + USB cycle harness after every `buildAudioSink` change |
| R7 | Queue persistence on v13 schema churn vs. concurrent P1 migrations | Serialize all migrations through one owner PR sequence (PR-N/O/P) |
| R8 | **A `SourceProvider` refactor (PR-D) silently drops the `isQobuzTrack` namespace guard** — the failure is silent: a Qobuz id resolved against the HiFi/TIDAL endpoint plays a *different recording* under the right title and artwork | Golden cross-provider id-routing test lands **in PR-D itself**, asserting every one of the seven existing call sites' behavior; the guard becomes the Qobuz provider's claim predicate, never an optional filter |
| R9 | A parallel segmented cache is built for UPnP/SMB/WebDAV, forking Qobuz's proven range-read path | PR-S1 generalizes `QobuzStreamCacheManager` first; new protocols may only land on top of it, with the Qobuz partial-stream tests unmodified |

## 9. Go/No-Go Checkpoints

- **G1 (after PR-F):** Audio truth visible in UI and bit-perfect migration complete →
  proceed to P0.2.
- **G2 (after PR-H):** codec matrix known + cast dep decision made → commit P0.3 scope;
  proceed only with matrix documented.
- **G3 (after PR-K):** AAudio backend parity on golden fixtures + no bypass regression →
  gate P0.5 network work on capacity.
- **G4 (after PR-P):** all migrations v14–v18 tested on a v8→v18 upgrade path → unlock
  P1.3–P1.7.

## 10. Non-Goals

- Chromecast: no code exists; not a preserve target (see G2).
- Crossfeed: already shipped — not a roadmap item.
- Qobuz segmented streaming: already shipped and in active use — the only work is
  generalizing it, never rebuilding it.
- DSD SACD ISO, native-DSD transport: DoP only in scope; native DSD is P2+.
- Two-DB unification: N/A — there is one DB.
- Rewriting `VariRateAudioProcessor` for output-rate SRC: reuse `SincKernel` in a new
  processor instead.

---

## Appendix A — Baseline Audit Deltas (corrections vs. the v1 roadmap)

1. **Two Room DBs → ONE.** v1 planned "unify MusicDatabase and local-media DB". Verified:
   single `@Database` in `MusicDatabase.kt` (v13) containing all local-media and collections
   entities. All P1 schema work is additive migration on one DB.
2. **Crossfeed is shipped**, not P2 (`CrossfeedEffect.kt` + `CrossfeedEffectTest`, injected in
   `PlaybackService`). Removed from non-goals/P2.
3. **Chromecast never existed in code.** `cast-framework`/media3-cast are unused dependencies
   (`app/build.gradle.kts:242,301`; zero `CastPlayer`/`CastContext` hits). Removed from the
   preserve-list; dep disposition is a go/no-go decision.
4. **`ResolvedMedia` already exists** (`StreamResolver.kt`) — the SourceProvider contract
   returns/extends it; no parallel type invented. **And Qobuz is a live first-class provider,
   not a fallback path**: its segmented streaming (`QobuzStreamUri`/`QobuzPartialDataSource`/
   `PartialStream`/`SchemeRoutingDataSource`/`QobuzStreamCacheManager`) is in active use with
   passing JVM tests, and the "HiFi API" instances serve a Qobuz-shaped catalogue
   (`HiFiApiClient` imports Qobuz envelope models). v1's TIDAL-only framing was wrong.
5. **Bit-perfect was a setting, not a state** — v1's P0.1 assumed a state existed. Reality:
   two booleans in `PreferencesManager.kt:349,351`. Plan migrates them into
   `AudioPathSnapshot` classification.
6. **SincKernel reuse** — v1 planned a new SRC engine; `SincKernel` (windowed-sinc, tested) is
   repurposed for output-rate SRC. VariRate remains speed/pitch-only.
7. **DSP chain scope corrected** — v1 treated DSP as thin; reality is `DspChain`/
   `MixBusProcessor`/`CrossfeedEffect`/`AutoEqProcessor`/`ParametricEqProcessor`/
   `AtmosAudioProcessor`/`DownmixProcessor` + spectrum/ProjectM taps. `ProcessingPlan` must
   enumerate all of it, including the bypass-chain subset.
8. **Codec gap smaller than claimed** — FFmpeg extension already enabled; DSF scanned;
   DSD/APE/TAK/WavPack/MPC/TrueHD/DTS noted as FFmpeg-handled (`PlaybackService.kt` ~660).
   P0.3 becomes verify-then-fill. Separately, the segmented-cache line item shrinks to a
   generalization of the shipped Qobuz path rather than net-new work.
9. **Playlist import partially exists** (CSV + Spotify); only M3U/PLS/WPL import + export
   remain. `ApoProfileParser` is EQ-profile import, not playlists.
10. **Feature flags absent** — v1 assumed flag plumbing existed; it doesn't. `FeatureFlag`
    registry is net-new, with a `SETTINGS_SYNC_KEYS` policy decision.
11. **UnifiedTrackRegistry is in-memory and process-death fragile** (`ConcurrentHashMap`,
    38 ln); QueueManager is unseeded, unpersisted — P1.2 is net-new persistence + seeded
    shuffle, not a refactor of existing persistence.
12. **Event schema seed exists** (`PlayEventEntity`/`PlayEventDao`); P0.0 event model extends
    rather than invents, absorbing `BypassDiagnostics.StartError`.
13. **ImportanceMediaCodecAdapterFactory** already addresses MediaCodec dropouts — removed
    from P0.6 scope.
14. **AAudio/Oboe**: confirmed zero existing usage — P0.2 backend is fully net-new.
15. **Credentials are plaintext** in DataStore (instance URLs); Keystore work is in scope and
    needs a data migration.
16. **Archive formats** (APE/WavPack/TAK) presumably decode via FFmpeg but are unverified —
    P0.3 verification PR covers them; v1 listed them as missing.
17. **`exportSchema = false`** — neither roadmap noticed. Room's `MigrationTestHelper` cannot
    run without exported schemas, so the migration-test gate (§7, R4) is unreachable until
    PR-C flips it and commits the v13 baseline JSON.

*— end of plan —*
