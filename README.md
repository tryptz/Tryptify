# Tryptify

A native Android hi-fi music player with TIDAL and Qobuz streaming, local-library
playback, encrypted collections, a 32-processor DSP mixing console, a 10-band
AutoEQ driven by 4,000+ headphone measurements (squig.link + AutoEq), a libusb
USB Audio Class bit-perfect bypass for external DACs, and a real-time ProjectM
visualizer.

> Formerly **MonoTrypT**. The launcher icon and About screen now read
> "Tryptify"; package id, storage paths, and existing installs are unchanged.

[**Download the latest APK**](https://github.com/tryptz/monotrypt.android/releases/latest)
— Android 8.0+ (API 26), sideload or `adb install`.

---

## Screenshots

<p align="center">
  <img src="docs/screenshots/player-now-playing.jpg" width="220" alt="Now Playing — album art, HD playback, and controls" />
  <img src="docs/screenshots/player-audio-tools.jpg" width="220" alt="Audio tools — output, AutoEQ, speed, mixer, visualizer, Oxford effects" />
  <img src="https://github.com/user-attachments/assets/359bf19e-1a79-4372-a7a0-1d841cc03387" width="220" alt="DSP Mixer — bus strips with gain, pan, mute, solo, and plugin chains" />
  <img src="https://github.com/user-attachments/assets/2670b38b-18cf-4aba-a71f-b3c1390761ec" width="220" alt="Plugin picker — categorized browser for all 32 audio processors" />
  <img src="https://github.com/user-attachments/assets/2a36210f-bd54-4bf7-98d1-aee0e556569b" width="220" alt="Plugin editor — per-effect parameter controls" />
  <img src="https://github.com/user-attachments/assets/e2d9b57d-ab5f-40c7-9eff-f8c9a741c942" width="220" alt="AutoEQ — headphone correction filters generator" />
  <img src="https://github.com/user-attachments/assets/58930158-4f00-45f2-a0bd-ae1d32961525" width="220" alt="AutoEQ — headphone model database browser with rig taxonomy" />
</p>

---

## What's new in 1.6.0

### Added

#### DevEdit layout mode
- **Drag-to-arrange editor** — New per-screen layout mode with an edit button and glow indicator. Move, resize, and rearrange UI elements directly on the screen.
- **Adopted app-wide** — Wired through the player first, then Settings, and every remaining screen (detail, mixer, Oxford, stats, home, library, EQ).
- **Grid snapping** — Optional grid snap for moving and resizing elements, with resize snapping to grid steps when enabled.
- **Resizable elements** — Album art and other elements can be resized in edit mode.
- **Bundled default layout** — Ships a default layout arrangement for all users, scaled across phone sizes (resolution-robust) against a 428×932dp design reference. Current layout can be exported to JSON.

#### Player
- **Rebuilt Now Playing screen** — `NowPlayingScreen` split into a composable shell for a cleaner main player layout.
- **Square album-art hero** — Album art rendered as a true square with sharp edges (rounded corners removed) and a drop shadow.
- **Dynamic album coloring** — Action dock, status cards, and transport side buttons now adopt the accent color extracted from the album art.
- **Swipe-up audio tools** — Audio-tools reworked into a dock with a pull-up panel that pulls from the bottom edge, freeing the area below the dock; pitch toggle added to the speed sheet.
- **Compact album-art controls** — Small, icon-only controls over the album art that auto-hide quickly; album-art pill shrunk.

#### Local library
- **Sorting** — Sort Albums, Songs, and Artists by date, name, file type, and time.
- **Artist recovery** — Recovers the artist from an `Artist - Title` filename when no artist tag is present.
- **Embedded cover art** — Shows embedded album art for local songs that have no cached cover.

#### Search
- **Deeper Qobuz results** — More Qobuz search results plus curated Qobuz recommendations.

### Changed
- **Status grid** — Replaced Sleep timer with Mixer; AutoEQ now routes to the AutoEQ page. Deduplicated AutoEQ and added a toggle to turn the visualizer off.
- **Glass outlines** — Clear, visible glass outlines enabled by default on every theme.
- **Songs list** — Local Songs list now uses the carded style to match Favorites.
- **projectM visualizer** — Control panel made more compact (and tightened further); visualizer locked to the album-art aspect ratio with a shrunk status badge and rounded corners removed.
- **Instances** — Only the user's own instance URL is used; the public instance pool and the inaccurate sync claim were dropped.

### Fixed
- **DSP knobs** — Fixed frozen DSP plugin knobs caused by stale value capture.
- **Album color** — Fixed album color extraction so action buttons no longer stay stuck on the default blue.
- **Player layout** — Fixed the player hero overflowing and overlapping the top bar and track info.
- **JVM signature clash** — Resolved an `applySort` signature clash with `@JvmName`.
- **CI** — Removed BlurView to fix the JitPack CI failure (album art now uses a shadow instead).

### Internal
- Bumped version to 1.6.0 (versionCode 160).

---

## What's inside

**Catalog & playback**

- Two streaming sources — TIDAL HiFi (lossless FLAC, 24-bit hi-res) and a
  user-configurable Qobuz instance (trypt-hifi compatible). Source mode is
  user-selectable; both run in parallel on search by default with Qobuz
  failures isolated so they can never block the TIDAL path.
- Local library via MediaStore with embedded-tag reader, incremental sync,
  and filesystem watcher.
- AES-256-GCM encrypted collections with transparent decryption at playback
  time.
- ReplayGain (track / album with preamp + peak protection), gapless
  playback, queue with drag-to-reorder + shuffle + 3-mode repeat, variable
  speed.
- Offline FLAC downloads via WorkManager, with optional LRC export and
  on-demand share-to-FLAC.
- Chromecast through Media3 Cast, Glance home-screen widget, Android Auto
  media browser.
- Last.fm and ListenBrainz scrobbling, Supabase-backed cloud sync of
  favorites / history / playlists.
- Synced karaoke lyrics (TIDAL + LRCLib fallback) with blurred-art backdrop.

**Audio processing**

- **DSP Mixer** — 4 buses + master, up to 16 plugin slots each, 32 native
  processors. User-selectable block size (4096 / 8K / 16K). True bypass
  when the mixer is off.
- **AutoEQ** — 10-band parametric generator, 10 target curves, 4,000+
  measurements from squig.link + AutoEq, rig-aware filtering, custom
  measurement upload.
- **Parametric EQ** — ±24 dB per band with bundled and user presets.
- **Oxford / Seap effects** — separate Inflator and Compressor screens for
  the dedicated processors (outside the bus plugin chain).
- **ProjectM visualizer** — OpenGL renderer with album-art color tinting,
  optional vsync disable.

**USB DAC bit-perfect bypass**

- libusb-backed UAC1 and UAC2 driver — claims the streaming interface from
  the kernel, sets the alt setting, negotiates the sample rate via the
  clock entity, and drives the iso endpoint directly with a preallocated
  transfer pool and a single-producer / single-consumer ring buffer.
- Sample-accurate UAC2 pacing using the asynchronous feedback endpoint;
  graceful UAC1 fallback for devices like the Focal Bathys that don't
  speak UAC2.
- Software-volume control on the bypass path with hardware-key handling,
  pause-silences-DAC, and a watchdog that falls back to the delegate sink
  if the iso pump wedges.
- DSP / EQ / spectrum / ProjectM tap all keep working when bypass is on —
  the mixer chain runs inline, then the post-DSP PCM goes to libusb
  instead of the Android audio HAL.
- Categorized error reporting (no-device, no matching alt, claim failed,
  set-alt failed, sample-rate failed, iso pump alloc / submit failed)
  surfaced to Settings instead of the old "kernel still owns it"
  boilerplate that was wrong half the time.

**Interface**

- 16 built-in palettes plus a true light "White" scheme.
- "System" theme follows the OS dark-mode toggle live; system bar icons
  adapt to the resolved background.
- Dynamic colors — Material You-style palette extracted from the current
  track's album art.
- Custom font loading, configurable text scale.
- Compose + Material 3 throughout, with a floating curved player UI
  designed against modern Android device screen radii.

---

## Catalog architecture

```
                  ┌─ TIDAL HiFi API client ──────┐
SearchViewModel ──┤                              ├─→ unified results
                  ├─ HiFiApiClient (Qobuz)       │
                  └─ Local + Collections         ┘
                              │
                              ▼
                       PlaybackSource
                       ├─ TidalStream
                       ├─ QobuzCached       ←  /api/download-music + LRU file cache
                       ├─ LocalFile
                       └─ EncryptedCollection
                              │
                              ▼
                       StreamResolver  →  Media3 ExoPlayer
```

`SourceMode` (BOTH / TIDAL_ONLY / QOBUZ_ONLY) gates which catalogs feed
search and discovery; per-track playback always follows the originating
`PlaybackSource`. Qobuz failures (instance unset, network error, schema
mismatch) are logged and dropped — the TIDAL path is never blocked behind
a slow or hung Qobuz instance.

`QobuzIdRegistry` records (numeric `qobuz_id` → alphanumeric slug) at search
time so the album-detail endpoint, which wants the slug, can be hit from a
navigation route keyed by `Long`. Track ids are also stamped so the player
routes Qobuz-album tracks through `QobuzCached` instead of falling through
to TIDAL with an id TIDAL doesn't recognize.

---

## DSP Mixer

A C++17 native library (`monochrome_dsp`) sitting inside the ExoPlayer audio
pipeline. ARM NEON SIMD, denormal flush-to-zero, lock-free atomic updates
between the UI and audio threads.

```
ExoPlayer → ReplayGain → AutoEQ / ParamEQ → MixBusProcessor (JNI) → ProjectM tap → AudioSink
                                                  │                                    │
                                            Native DspEngine             ┌─────────────┴─────────────┐
                                            ├─ Bus 1  [up to 16 plugins] │  default → DefaultAudioSink
                                            ├─ Bus 2  [up to 16 plugins] │  bypass  → LibusbAudioSink
                                            ├─ Bus 3  [up to 16 plugins] │             (libusb UAC)
                                            ├─ Bus 4  [up to 16 plugins] └───────────────────────────┘
                                            ├─ Sum ──────────────────────┐
                                            └─ Master [up to 16 plugins] ┘
```

Per-bus gain (dB), pan, mute, solo, and input-enable. Master sums active
buses, runs its own plugin chain, and meters the output with peak + hold
ballistics (1.5 s decay). Engine state serializes to JSON and persists in
Room.

### 32 mixer plugins

| Category | Processors |
|----------|------------|
| **Utility** | Gain · Stereo (M/S + equal-power pan) · Channel Mixer (2×2 routing) · Haas |
| **EQ & Filter** | Filter (RBJ biquad, LP/BP/HP/Notch/Shelf/Peak, 1×–4× slope) · Comb Filter · Formant Filter · Ladder Filter (Moog/diode, 2× OS) · Nonlinear Filter (SVF + 5 shapers) · Resonator |
| **Dynamics** | Compressor · Limiter (5 ms lookahead, true peak) · Gate · Dynamics (dual-threshold up/down) · Compactor (lookahead limiter / ducker) · Transient Shaper · Trance Gate (8-step, ADSR) |
| **Distortion** | Distortion (6 modes) · Shaper (256-pt transfer LUT) · Bitcrush (SR + bit-depth, TPDF dither) · Phase Distortion (Hilbert self-PM) |
| **Modulation** | Chorus · Ensemble · Flanger (barberpole) · Phaser (2–12 stage) · Ring Mod · Tape Stop · Frequency Shifter (SSB via Hilbert pair) · Pitch Shifter (granular OLA) |
| **Space** | Delay (up to 2 s, ping-pong, ducking) · Reverb (8-line FDN + 4 allpass diffusers) · Reverser |

Every plugin has bypass, dry / wet, and 5 ms parameter smoothing. All
setters clamp inputs and reject non-finite values before reaching the
real-time thread; biquads fall back to passthrough on pathological f / Q
combinations.

The Oxford / Seap **Inflator** and **Compressor** are dedicated effects
exposed on their own screens, separate from the bus plugin chain.

### Shared DSP primitives

Biquads (RBJ cookbook), cubic-interpolated delay lines, peak / RMS envelope
followers, LFOs, allpass chains, DC blocker, Hilbert transform, 2× half-band
oversampler, lookahead buffers, Hann overlap-add crossfade, 256-point
transfer-curve LUT, exponential parameter smoothers.

---

## AutoEQ

10-band parametric EQ that generates headphone-correction filters from
frequency-response measurements.

**Algorithm** — greedy iterative peak-finding:

1. Normalize measurement against target over the 250–2500 Hz midrange window.
2. Scan 20 Hz–16 kHz for the worst deviation (sub-50 Hz weighted 1.2×).
3. Invert the deviation as gain (clamped ±12 dB, ±8 dB above 8 kHz),
   estimate Q from the bandwidth.
4. Subtract the new filter's biquad response from the remaining error.
5. Repeat up to 10 bands, or stop early if max error < 0.05 dB.

**Target curves:** Harman Over-Ear 2018 · Harman In-Ear 2019 · Diffuse Field
· Knowles · Moondrop VDSF · Hi-Fi Endgame 2026 · PEQdB Ultra · SEAP · SEAP
Bass · Flat.

### Measurement sources

Twelve **squig.link** instances are queried in parallel on first fetch; the
existing AutoEq GitHub catalog is also bundled. Per-source failures are
silent so one dead host doesn't poison the list. Cache TTL is 24 h, matching
the AutoEq path. Sources currently include Super\* Review, Precogvision,
DHRME, Aftersound, Elise Audio, Jaytiss, CSI-Zone, Acho Reviews, kr0mka, and
others; the full list lives in `SquiglinkApi.kt`.

### Rig taxonomy

Each measurement is tagged with the rig it was captured on:

`Uploaded` (the user's own files, pinned leftmost) · `B&K 5128` ·
`GRAS 43AG-7` · `GRAS 43AC-10` · `GRAS 45CA-10` · `IEC 711 clone` ·
`MiniDSP EARS` · `Unknown`.

The HeadphoneSelectScreen shows a chip row of rigs across the top with
per-source rows below; switching rig instantly re-filters without a
network round-trip. User uploads support long-press delete and have the
headphone name auto-filled from the picked filename so saved profiles
keep their identity.

### Custom measurements

CSV / TXT with auto-detected delimiter (comma, semicolon, tab, whitespace),
header row detection, and European decimal format conversion. The picked
file's frequency response is cached so the curve survives across sessions
without re-import.

### Audio integration

The EQ processor wraps Android's system `Equalizer` AudioEffect, bound to
the ExoPlayer audio session id. Parametric bands are mapped to the device's
fixed system bands using a Gaussian gain estimation model — for each system
band frequency, the contribution of every parametric band is summed based
on its distance in octaves from the band centre, shaped by Q. Gains clamp
to the device's hardware limits (in millibels). Preamp clamps against peak
band gain so the cascade can't exceed safe headroom. EQ updates in real
time via an atomic snapshot read on each audio block — no clicks during
live tweaking.

---

## USB DAC bit-perfect bypass

A libusb-backed Audio Class driver that takes the streaming interface from
the kernel and writes PCM directly to the DAC's isochronous endpoint,
bypassing the Android audio HAL.

**Owns:**
- One process-wide libusb context, lazily created.
- Device handle wrapping a Java-supplied `UsbDeviceConnection` fd.
- Streaming-interface alt-setting selection + claim, with an
  AudioControl-interface claim so `SET_CUR` requests can reach the device.
- A fixed pool of preallocated isochronous transfers.
- An SPSC ring buffer the audio thread writes into and the iso completion
  callback drains from.
- An event-handling thread driving `libusb_handle_events`.

**Negotiates:**
- UAC1 *and* UAC2 — auto-detected from the descriptors. UAC1 path supports
  devices like the Focal Bathys that don't speak UAC2.
- Sample rate via UAC2 clock entity + `GET_RANGE`, walking
  Selector → Source units when needed; UAC1 has its own `SET_CUR`
  handling. Brute-forces every clock candidate before failing.
- Iso `bInterval` is honoured; alts whose maxPacketSize can't fit the
  configured rate are rejected up-front.

**Pacing:** UAC2 asynchronous feedback endpoint reader for sample-accurate
iso scheduling. UAC1 falls back to fixed-rate iso pacing.

**Sink integration:** `LibusbAudioSink` is a Media3 `ForwardingAudioSink`
wrapping a `DefaultAudioSink`. When bypass is hot, it runs the same
`AudioProcessor` chain `DefaultAudioSink` would (mixer DSP, AutoEQ,
parametric EQ, FFT spectrum, ProjectM tap), then writes the post-DSP PCM
to libusb. Software volume is applied on the bypass path and follows the
hardware volume keys; pause silences the DAC instantly. A watchdog with a
brief grace window falls back to the delegate sink if the iso pump wedges.

**Failure surfacing:** start failures are categorised (no-device, no
matching alt, claim failed, set-alt failed, sample-rate failed, iso pump
alloc / submit failed) and surfaced to Settings as actionable text instead
of one boilerplate error string.

---

## Themes

Sixteen built-in palettes (Monochrome, Ocean, Midnight, Crimson, Forest,
Sunset, Cyberpunk, Nord, Gruvbox, Dracula, Solarized, Lavender, Gold,
Rosewater, Mint, White, Clear), plus:

- **System** — follows the OS dark-mode setting live.
- **Dynamic colors** — primary / secondary slots extracted from the current
  track's album art via AndroidX Palette and cached in memory.
- **Material You hook** — SDK-gated helper ready for a future Android 12+
  wallpaper-based scheme.

Status- and navigation-bar styles are bound to the resolved theme background,
so icons stay legible when switching palettes or track art.

---

## Architecture

Single-module app, package `tf.monochrome.android`, application id
`tf.monotrypt.android` (kept stable through the Tryptify rebrand so existing
installs upgrade in place).

```
tf.monochrome.android/
├── audio/
│   ├── dsp/           # MixBusProcessor (JNI), DspEngineManager, SnapinType
│   │   └── oxford/    # Oxford / Seap Inflator + Compressor
│   ├── eq/            # AutoEqEngine, EqProcessor, FrequencyTargets
│   └── usb/           # LibusbUacDriver, LibusbAudioSink, exclusive controller
├── auto/              # Android Auto media browser service
├── data/
│   ├── api/           # TIDAL HiFi client, Qobuz (HiFiApiClient), squig.link, instance failover
│   ├── auth/          # Google Sign-In + Appwrite OAuth
│   ├── cache/         # QobuzStreamCacheManager
│   ├── collections/   # Encrypted collection manifests (AES-256-GCM)
│   ├── db/            # Room v8, 11 tables
│   ├── downloads/     # WorkManager offline downloader
│   ├── local/         # MediaStore scanner + tag reader + fs watcher
│   ├── preferences/   # DataStore settings (incl. SourceMode, Qobuz endpoint)
│   ├── scrobbling/    # Last.fm + ListenBrainz
│   └── sync/          # Supabase cloud sync
├── di/                # Hilt modules
├── domain/            # Models + use cases (incl. MeasurementRig)
├── player/            # Media3 PlaybackService, QueueManager, StreamResolver, ReplayGain
├── share/             # TrackShareHelper (download-on-demand share-to-FLAC)
├── ui/
│   ├── mixer/         # BusStrip, PluginSlot, PluginPicker, PluginEditor, DspCanvas
│   ├── eq/            # Parametric + AutoEQ screens, FrequencyResponseGraph, rig chips
│   ├── oxford/        # Inflator + Compressor screens
│   ├── theme/         # Color schemes, Dimens, DynamicColorExtractor
│   └── ...            # Remaining screens (Compose + Material 3)
├── visualizer/        # ProjectM OpenGL renderer + JNI audio tap
└── widget/            # Glance Now Playing widget
```

### Native code

```
app/src/main/cpp/
├── dsp/
│   ├── dsp_engine.{h,cpp}   # Bus / plugin routing, metering, state serialization
│   ├── dsp_jni.cpp          # Kotlin ↔ C++ bridge
│   ├── snapins/             # 32 plugin implementations + the AutoEq biquad chains
│   └── util/                # Shared DSP primitives
├── usb/
│   ├── libusb_uac_driver.{h,cpp}   # UAC1 + UAC2 streaming driver
│   └── usb_jni.cpp                 # Kotlin ↔ libusb bridge
├── projectm_bridge.{h,cpp}  # ProjectM visualizer wrapper
├── projectm_jni.cpp
└── audio_ring_buffer.{h,cpp}
```

Built with `-O3 -ffast-math`, ARM NEON SIMD, denormal flush-to-zero. ABIs:
`arm64-v8a`, `armeabi-v7a`, `x86_64`. Explicit `libc++_shared` runtime.

### Database

Room v8, 11 tables:

`favorite_tracks` · `favorite_albums` · `favorite_artists` · `history_tracks`
· `play_events` · `user_playlists` · `playlist_tracks` · `downloaded_tracks`
· `cached_lyrics` · `eq_presets` · `mix_presets`.

All DAOs expose `Flow<T>` for reactive UI.

### Networking

Ktor with the OkHttp engine. 200-entry LRU cache, 30-minute TTL. Instance
failover pulls live API endpoints from uptime workers, falls back to a
hardcoded pool, shuffles per request; 429 responses rotate to the next
instance. Qobuz fan-out has a hard 6 s per-request ceiling so a slow Qobuz
instance can't block the TIDAL flow.

---

## Tech stack

| Component | Version |
|-----------|---------|
| Language | Kotlin 2.1.0 |
| DSP engine | C++17 via JNI (`monochrome_dsp`) |
| USB driver | libusb 1.0 (UAC1 + UAC2) |
| UI | Jetpack Compose, Material 3 (BOM 2024.12.01) |
| Audio | Media3 / ExoPlayer 1.5.1 |
| Casting | Media3 Cast, Google Cast Framework 22.0.0 |
| Visualizer | ProjectM 4.1.6 (C++17 via JNI) |
| Images | Coil 3.0.4 |
