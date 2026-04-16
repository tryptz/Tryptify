# MonoTrypT Android

A hi-fi music player with TIDAL streaming, local library, encrypted collections, a 33-processor native DSP mixing console, a 10-band AutoEQ driven by 4,000+ headphone measurements, and a real-time ProjectM visualizer.

[**Download the latest APK**](https://github.com/tryptz/monotrypt.android/releases/latest) — Android 8.0+ (API 26), sideload or `adb install`.

---

## Screenshots

<p align="center">
  <img src="https://github.com/user-attachments/assets/0a23a50e-6dfe-482f-987d-42c3c7536dfb" width="220" alt="Now Playing — album art, HD playback, and controls" />
  <img src="https://github.com/user-attachments/assets/359bf19e-1a79-4372-a7a0-1d841cc03387" width="220" alt="DSP Mixer — bus strips with gain, pan, mute, solo, and plugin chains" />
  <img src="https://github.com/user-attachments/assets/2670b38b-18cf-4aba-a71f-b3c1390761ec" width="220" alt="Plugin picker — categorized browser for all 33 audio processors" />
  <img src="https://github.com/user-attachments/assets/2a36210f-bd54-4bf7-98d1-aee0e556569b" width="220" alt="Plugin editor — per-effect parameter controls" />
  <img src="https://github.com/user-attachments/assets/e2d9b57d-ab5f-40c7-9eff-f8c9a741c942" width="220" alt="AutoEQ — headphone correction filters generator" />
  <img src="https://github.com/user-attachments/assets/58930158-4f00-45f2-a0bd-ae1d32961525" width="220" alt="AutoEQ — headphone model database browser" />
</p>

---

## What's inside

**Playback**
- TIDAL HiFi streaming (lossless FLAC, 24-bit hi-res, WiFi/cellular quality auto-switching)
- Local library via MediaStore with embedded-tag reader, incremental sync, and filesystem watching
- AES-256-GCM encrypted collections with transparent decryption at playback time
- ReplayGain (track/album with preamp + peak protection)
- Offline FLAC downloads via WorkManager with optional LRC export
- Chromecast through Media3 Cast, Glance home-screen widget, Android Auto
- Last.fm and ListenBrainz scrobbling

**Audio processing**
- **DSP Mixer** — 4 buses + master, up to 16 plugin slots each, 33 native processors
- **AutoEQ** — 10-band parametric generator against 10 target curves, 4,000+ measurements
- **Parametric EQ** — ±24 dB per band with bundled and user presets
- **ProjectM visualizer** — OpenGL renderer with album-art color tinting

**Interface**
- 16 built-in color themes plus a true light "White" scheme
- "System" theme follows the OS dark-mode toggle
- **Dynamic colors** — Material You-style palette extracted live from album art
- Status / navigation bar icons follow the active theme background
- Custom font loading, configurable text scale
- Compose + Material 3 throughout

---

## DSP Mixer

A mixing console built as a C++17 native library (`monochrome_dsp`) sitting inside the ExoPlayer audio pipeline. ARM NEON SIMD, denormal flush-to-zero, and lock-free atomic updates between the UI and audio threads.

```
ExoPlayer → ReplayGain → AutoEQ → MixBusProcessor (JNI) → ProjectM tap → AudioSink
                                         │
                                    Native DspEngine
                                    ├─ Bus 1  [up to 16 plugins]
                                    ├─ Bus 2  [up to 16 plugins]
                                    ├─ Bus 3  [up to 16 plugins]
                                    ├─ Bus 4  [up to 16 plugins]
                                    ├─ Sum ───────────────────────┐
                                    └─ Master [up to 16 plugins] ─┘
```

Per-bus gain (dB), pan, mute, solo, and input enable. Master sums active buses, runs its own plugin chain, and meters the output with peak + hold ballistics (1.5 s decay). Engine state serializes to JSON and persists in Room DB.

### 33 processors

| Category | Processors |
|----------|------------|
| **Utility** | Gain · Stereo (M/S + equal-power pan) · Channel Mixer (2×2 routing) |
| **EQ & Filter** | Biquad Filter (LP/BP/HP/Notch/Shelf/Peak, 1×–4× slope) · 3-Band EQ (Linkwitz-Riley) · Comb Filter · Formant Filter · Ladder Filter (Moog/diode, 2× OS) · Nonlinear Filter (SVF + 5 shapers) · Resonator |
| **Dynamics** | Compressor · Limiter (5 ms lookahead, true peak) · Gate · Dynamics (dual-threshold up/down) · Compactor (lookahead limiter/ducker) · Transient Shaper |
| **Distortion** | Distortion (6 modes) · Shaper (256-pt transfer LUT) · Bitcrush (SR + bit depth, TPDF dither) · Phase Distortion (Hilbert self-PM) |
| **Modulation** | Chorus · Ensemble · Flanger (barberpole) · Phaser (2–12 stage) · Ring Mod · Tape Stop · Frequency Shifter (SSB via Hilbert pair) · Pitch Shifter (granular OLA) · Haas |
| **Space** | Delay (up to 2 s, ping-pong, ducking) · Reverb (8-line FDN + 4 allpass diffusers) · Reverser |
| **Sequenced** | Trance Gate (8-step, ADSR, 1/4–1/32) |

Every processor has bypass, dry/wet, and 5 ms parameter smoothing. All setters clamp inputs and reject non-finite values before reaching the real-time thread; biquads fall back to passthrough on pathological f/Q combinations.

### Shared DSP primitives

Biquads (RBJ cookbook), cubic-interpolated delay lines, peak/RMS envelopes, LFOs, allpass chains, DC blocker, Hilbert transform, 2× half-band oversampler, lookahead buffers, Hann overlap-add crossfade, 256-point transfer-curve LUT, exponential parameter smoothers.

---

## AutoEQ

10-band parametric EQ that generates headphone-correction filters from frequency-response measurements.

**Algorithm** — greedy iterative peak-finding:

1. Normalize measurement against target over the 250–2500 Hz midrange window
2. Scan 20 Hz–16 kHz for the worst deviation (sub-50 Hz weighted 1.2×)
3. Invert deviation as gain (clamped ±12 dB, ±8 dB above 8 kHz), estimate Q from bandwidth
4. Subtract the new filter's biquad response from the remaining error
5. Repeat up to 10 bands, or stop early if max error < 0.05 dB

**Target curves:** Harman Over-Ear 2018 · Harman In-Ear 2019 · Diffuse Field · Knowles · Moondrop VDSF · Hi-Fi Endgame 2026 · PEQdB Ultra · SEAP · SEAP Bass · Flat

Bundled against 4,000+ measurements, with case-insensitive name resolution and optional custom measurement upload. Preamp is clamped against the peak band gain so the cascade stays inside safe headroom. EQ updates in real time via an atomic snapshot read on each audio block — no clicks during live tweaking.

---

## Themes

Sixteen built-in palettes (Monochrome, Ocean, Midnight, Crimson, Forest, Sunset, Cyberpunk, Nord, Gruvbox, Dracula, Solarized, Lavender, Gold, Rosewater, Mint, White, Clear), plus:

- **System** — follows the OS dark-mode setting live
- **Dynamic colors** — primary / secondary slots extracted from the current track's album art via AndroidX Palette and cached in memory
- **Material You hook** — SDK-gated helper ready for a future Android 12+ wallpaper-based scheme

Status- and navigation-bar styles are bound to the resolved theme background, so icons stay legible when switching palettes or track art.

---

## Architecture

Single-module app, package `tf.monochrome.android`.

```
tf.monochrome.android/
├── audio/
│   ├── dsp/           # MixBusProcessor (JNI), DspEngineManager, SnapinType
│   └── eq/            # AutoEqEngine, EqProcessor, FrequencyTargets
├── auto/              # Android Auto media browser service
├── data/
│   ├── api/           # TIDAL HiFi client + instance failover
│   ├── auth/          # Google Sign-In + Appwrite OAuth
│   ├── collections/   # Encrypted collection manifests (AES-256-GCM)
│   ├── db/            # Room v4, 22 tables
│   ├── downloads/     # WorkManager offline downloader
│   ├── local/         # MediaStore scanner + tag reader + fs watcher
│   ├── preferences/   # DataStore settings
│   ├── scrobbling/    # Last.fm + ListenBrainz
│   └── sync/          # Appwrite / PocketBase cloud sync
├── di/                # Hilt modules
├── domain/            # Models + use cases
├── player/            # Media3 PlaybackService, QueueManager, StreamResolver, ReplayGain
├── ui/
│   ├── mixer/         # BusStrip, PluginSlot, PluginPicker, PluginEditor, DspCanvas
│   ├── eq/            # Parametric + AutoEQ screens, FrequencyResponseGraph
│   ├── theme/         # Color schemes, Dimens, DynamicColorExtractor
│   └── ...            # Remaining screens (Compose + Material 3)
├── visualizer/        # ProjectM OpenGL renderer + JNI audio tap
└── widget/            # Glance Now Playing widget
```

### Native code

```
app/src/main/cpp/
├── dsp_engine.h / .cpp    # Bus/plugin routing, metering, state serialization
├── dsp_jni.cpp            # Kotlin ↔ C++ bridge
├── plugins/               # 33 processor implementations
└── util/                  # Shared DSP primitives
```

Built with `-O3 -ffast-math`, ARM NEON SIMD, denormal flush-to-zero. ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`.

### Database

Room v4, 22 tables:

- **Core** — favorites, play history, playlists, downloads, cached lyrics, EQ presets
- **Local media** — tracks, albums, artists, genres, folders, scan state
- **Collections** — collection metadata, streaming URLs, artist/album cross-refs
- **Mixer** — DSP mix presets (JSON-serialized engine state)

All DAOs expose `Flow<T>` for reactive UI.

### Networking

Ktor with the OkHttp engine. 200-entry LRU cache, 30-minute TTL. Instance failover pulls live API endpoints from uptime workers, falls back to a hardcoded pool, shuffles per request; 429 responses rotate to the next instance.

---

## Tech stack

| Component | Version |
|-----------|---------|
| Language | Kotlin 2.1.0 |
| DSP engine | C++17 via JNI (`monochrome_dsp`) |
| UI | Jetpack Compose, Material 3 (BOM 2024.12.01) |
| Audio | Media3 / ExoPlayer 1.5.1 |
| Casting | Media3 Cast, Google Cast Framework 22.0.0 |
| Visualizer | ProjectM (C++17 via JNI) |
| Images | Coil 3.0.4 |
| Palette extraction | AndroidX Palette |
| Database | Room 2.7.1 |
| Preferences | DataStore 1.1.1 |
| Network | Ktor 3.0.3 (OkHttp engine) |
| DI | Hilt 2.57.1 |
| Serialization | Kotlinx Serialization 1.7.3 |
| Encryption | AES-256-GCM (AndroidX Security Crypto) |
| Background | WorkManager 2.10.0 |
| Widgets | Glance 1.1.1 |
| Build | AGP 9.0.0, KSP 2.1.0-1.0.29, CMake 3.22.1 |
| Min / target SDK | 26 / 36 |

---

## Build

**Requirements:** JDK 17+, Android SDK 36, NDK `28.2.13676358`.

```bash
git clone --recurse-submodules https://github.com/tryptz/monotrypt.android.git
cd monotrypt.android
./gradlew assembleDebug
```

The `--recurse-submodules` flag pulls the ProjectM v4.1.6 submodule used by the native visualizer.

**Optional — TIDAL streaming:** add credentials to `local.properties`:

```
TIDAL_CLIENT_ID=...
TIDAL_CLIENT_SECRET=...
```

Without these the app still builds and runs; TIDAL content just won't resolve.

**Optional — signed release:** copy `keystore.properties.example` → `keystore.properties`, fill in the signing config, then:

```bash
./gradlew assembleRelease
```

**Install to a connected device:**

```bash
./gradlew installDebug
```

The NDK is required for the ProjectM visualizer (`third_party/projectm/`) and the native DSP engine (`app/src/main/cpp/`). Install `28.2.13676358` via Android Studio → SDK Manager.
