# MonoTrypT Android

A high-fidelity music player for Android with TIDAL HiFi streaming, local file playback, encrypted collections, and a **33-processor native DSP mixing console**. Built for audiophiles who want DAW-grade signal processing in their pocket.

[**Download APK**](https://github.com/tryptz/monotrypt.android/releases/latest) — sideload or install via ADB. Android 8.0+ (API 26).

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

## Features

- **TIDAL HiFi streaming** — lossless FLAC and 24-bit hi-res, quality auto-switching between WiFi and cellular
- **Local library** — MediaStore scanner with embedded tag reading, incremental sync, and filesystem watching
- **Encrypted collections** — AES-256-GCM manifest-based music with transparent decryption during playback
- **DSP Mixer** — 4 mix buses + 1 master, 16 plugin slots per bus, 33 native audio processors (see below)
- **AutoEQ** — 10-band parametric headphone correction from 4,000+ measurements, 10 target curves
- **ProjectM visualizer** — real-time OpenGL audio visualization with preset library
- **ReplayGain** — track/album modes with adjustable preamp and peak protection
- **Scrobbling** — dual Last.fm and ListenBrainz support
- **Offline downloads** — FLAC files via WorkManager with optional LRC lyric export
- **Android Auto** — full media browsing via `MediaBrowserService`
- **Chromecast** — Media3 Cast integration
- **Cloud sync** — favorites, history, and playlists via Appwrite/PocketBase
- **Glance widget** — Now Playing home screen widget

---

## DSP Mixer

A full mixing console built as a C++17 native library (`monochrome_dsp`), integrated into the ExoPlayer pipeline via JNI. The engine runs with ARM NEON SIMD auto-vectorization, denormal flush-to-zero, and lock-free atomic parameter updates between the UI and audio threads.

### Architecture

```
ExoPlayer → ReplayGain → AutoEQ → MixBusProcessor (JNI) → ProjectM Tap → AudioSink
                                        │
                                  Native DspEngine
                                  ├─ Bus 1  [up to 16 plugins]
                                  ├─ Bus 2  [up to 16 plugins]
                                  ├─ Bus 3  [up to 16 plugins]
                                  ├─ Bus 4  [up to 16 plugins]
                                  ├─ Sum ──────────────────────┐
                                  └─ Master [up to 16 plugins] ┘
```

Each bus has independent gain (dB), pan, mute, solo, and input enable controls. The master bus sums all active buses, applies its own plugin chain, and feeds the output with clipping detection. Real-time peak + hold meters with 1.5 s decay ballistics run on every bus.

Mixer state serializes to JSON for preset save/load, persisted in Room DB. State is preserved across track changes.

### 33 Processors

| Category | Processors |
|----------|------------|
| **Utility** | Gain · Stereo (M/S + equal-power pan) · Channel Mixer (2×2 routing matrix) |
| **EQ & Filter** | Biquad Filter (LP/BP/HP/Notch/Shelf/Peak, 1×–4× slope) · 3-Band EQ (Linkwitz-Riley crossover) · Comb Filter · Formant Filter · Ladder Filter (4-pole Moog/diode model, 2× oversampled) · Nonlinear Filter (SVF + 5 waveshapers) · Resonator |
| **Dynamics** | Compressor (RMS/peak, soft knee) · Limiter (5 ms lookahead, true peak) · Gate (hysteresis, lookahead, hold) · Dynamics (dual-threshold up/down compression) · Compactor (lookahead limiter/ducker) · Transient Shaper (attack/sustain gain) |
| **Distortion** | Distortion (6 modes: tanh/saturate/foldback/sine/hardclip/quantize) · Shaper (256-point transfer curve) · Bitcrush (sample rate + bit depth reduction, TPDF dither) · Phase Distortion (Hilbert-based self-phase modulation) |
| **Modulation** | Chorus (1–6 voice) · Ensemble (2–8 voice allpass) · Flanger (barberpole scroll) · Phaser (2–12 stage) · Ring Mod · Tape Stop · Frequency Shifter (SSB via Hilbert pair) · Pitch Shifter (granular overlap-add) · Haas (precedence-effect widening) |
| **Space** | Delay (up to 2 s, ping-pong, ducking) · Reverb (8-line FDN, 4 allpass diffusers, Hadamard mixing) · Reverser (segment capture → backwards crossfade) |
| **Sequenced** | Trance Gate (8-pattern step sequencer, ADSR, 1/4–1/32 resolution) |

Every processor supports bypass, dry/wet mix, and 5 ms parameter smoothing to prevent zipper noise.

### Shared DSP Utilities

The native layer includes reusable signal processing primitives: biquad filters (RBJ cookbook), cubic-interpolated delay lines, peak/RMS envelope followers, LFO (sine/square/saw), allpass chains, DC blocker, Hilbert transform, 2× half-band oversampler, lookahead buffers, Hann overlap-add crossfade, 256-point transfer curve LUT, and exponential parameter smoothers.

---

## AutoEQ Engine

A 10-band fully parametric equalizer that generates headphone correction filters from frequency response measurements.

**Algorithm** — greedy iterative peak-finding:
1. Normalize measurement against target over the 250–2500 Hz midrange window
2. Scan 20 Hz–16 kHz for the worst deviation (sub-50 Hz weighted 1.2×)
3. Invert deviation as gain (clamped ±12 dB, ±8 dB above 8 kHz), estimate Q from bandwidth
4. Subtract the new filter's biquad response from the remaining error
5. Repeat up to 10 bands, or stop early if max error < 0.05 dB

**Built-in target curves:** Harman Over-Ear 2018 · Harman In-Ear 2019 · Diffuse Field · Knowles · Moondrop VDSF · Hi-Fi Endgame 2026 · PEQdB Ultra · SEAP / SEAP Bass · Flat

Over 4,000 headphone measurements are available from a remote repository. Custom measurement files can be uploaded. EQ settings persist in DataStore and update in real time without interrupting playback.

---

## Architecture

Single-module Android app (`app/`), package `tf.monochrome.android`.

```
tf.monochrome.android/
├── audio/
│   ├── dsp/           # MixBusProcessor (JNI bridge), DspEngineManager, SnapinType enum
│   └── eq/            # AutoEQ engine (AutoEqEngine, EqProcessor, FrequencyTargets)
├── auto/              # Android Auto — MonochromeMediaBrowserService
├── data/
│   ├── ai/            # Gemini 2.0 Flash integration
│   ├── api/           # TIDAL HiFi API client + instance failover
│   ├── auth/          # Google Sign-In + Appwrite OAuth
│   ├── collections/   # Encrypted collection manifests (AES-256-GCM)
│   ├── db/            # Room database (22 tables), including MixPreset entities
│   ├── downloads/     # WorkManager offline download
│   ├── local/         # MediaStore scanner + tag reader + filesystem watcher
│   ├── preferences/   # DataStore-backed settings
│   ├── scrobbling/    # Last.fm + ListenBrainz
│   └── sync/          # Appwrite/PocketBase cloud sync
├── di/                # Hilt modules (App, API, Database, Network)
├── domain/            # Domain models + use cases
├── player/            # Media3 playback service, stream resolution, queue, ReplayGain
├── ui/
│   ├── mixer/         # DSP mixer UI (BusStrip, PluginSlot, PluginPicker, PluginEditor, DspCanvas)
│   └── ...            # All other screens and composables (Compose + Material 3)
├── visualizer/        # ProjectM OpenGL renderer + JNI audio tap
└── widget/            # Glance Now Playing widget
```

### Native Code

```
app/src/main/cpp/
├── dsp_engine.h / .cpp    # Core bus/plugin routing, metering, state serialization
├── dsp_jni.cpp            # JNI entry points for Kotlin ↔ C++ bridge
├── plugins/               # 33 processor implementations
└── util/                  # Shared DSP primitives (biquad, delay, envelope, LFO, etc.)
```

Compiled with `-O3 -ffast-math`, ARM NEON SIMD, and denormal flush-to-zero. Supports arm64-v8a, armeabi-v7a, and x86_64 ABIs.

### Database

Room v2 schema, 22 tables across four domains:

- **Core:** favorites, play history, playlists, downloads, cached lyrics, EQ presets
- **Local media:** tracks, albums, artists, genres, folders, scan state
- **Collections:** collection metadata, streaming URLs, artist/album cross-references
- **Mixer:** DSP mix presets (JSON-serialized engine state)

All queries are Flow-based for reactive UI updates.

### Networking

Ktor with OkHttp engine. 200-entry LRU cache with 30-minute TTL. Instance failover: pulls available API endpoints from uptime workers, falls back to hardcoded instances, shuffles per request. 429 responses rotate to the next instance.

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Language | Kotlin 2.1.0 |
| DSP Engine | C++17 via JNI (`monochrome_dsp`) |
| UI | Jetpack Compose, Material 3 (BOM 2024.12.01) |
| Audio | Media3 / ExoPlayer 1.5.1 |
| Casting | Media3 Cast, Google Cast Framework 22.0.0 |
| Visualizer | ProjectM (C++17 via JNI) |
| Images | Coil 3.0.4 |
| Database | Room 2.7.1 |
| Preferences | DataStore 1.1.1 |
| Network | Ktor 3.0.3 (OkHttp engine) |
| DI | Hilt 2.57.1 |
| Serialization | Kotlinx Serialization 1.7.3 |
| Auth | Appwrite 7.0.0, Google Credentials API |
| Encryption | AES-256-GCM (AndroidX Security Crypto) |
| Background | WorkManager 2.10.0 |
| Widgets | Glance 1.1.1 |
| Build | AGP 9.0.0, KSP 2.1.0-1.0.29, CMake 3.22.1 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| ABI | arm64-v8a, armeabi-v7a, x86_64 |

---

## Build

**Requirements:** JDK 17+, Android SDK 36, NDK `28.2.13676358`

```bash
git clone https://github.com/tryptz/monotrypt.android.git
cd monotrypt.android
./gradlew assembleDebug
```

**Optional — TIDAL streaming:** add credentials to `local.properties`:
```
TIDAL_CLIENT_ID=...
TIDAL_CLIENT_SECRET=...
```
Without these the app builds and runs — TIDAL content just won't load.

**Optional — release builds:** copy `keystore.properties.example` → `keystore.properties` and fill in signing keys, then:
```bash
./gradlew assembleRelease
```

**Install to device:**
```bash
./gradlew installDebug
```

The NDK is required for the ProjectM visualizer and DSP engine native code (`third_party/projectm/`, `app/src/main/cpp/`). Install NDK `28.2.13676358` via Android Studio SDK Manager.
