# Tryptify

> A native Android hi-fi music player: a C++17 DSP console, headphone AutoEQ from real measurements, bit-perfect USB-DAC output, and a player you can watch.

[![Downloads](https://img.shields.io/github/downloads/tryptz/Tryptify/total?label=downloads&color=2EA44F&logo=github)](https://github.com/tryptz/Tryptify/releases)
[![Latest release](https://img.shields.io/github/v/release/tryptz/Tryptify?label=latest%20release&color=8E5CFF)](https://github.com/tryptz/Tryptify/releases/latest)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-1.9.2-8E5CFF)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%C2%B7%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Native](https://img.shields.io/badge/native-C%2B%2B17%20%C2%B7%20JNI%20%C2%B7%20NEON-00599C?logo=cplusplus&logoColor=white)
![Audio](https://img.shields.io/badge/audio-Media3%20ExoPlayer%201.5.1-FF6F00)
![Languages](https://img.shields.io/badge/languages-7-2EA44F)

<p align="center">
  <a href="https://github.com/tryptz/Tryptify/releases/latest/download/tryptify.apk"><b>⬇ Download the latest APK</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/tryptz/Tryptify/releases/latest">Release notes</a>
  <br>
  <sub>Android 13+ · formerly <b>MonoTrypT</b>, existing installs upgrade in place</sub>
</p>

<table>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/screenshots/player-ridgeline-ambient.jpg" alt="Ridgeline spectrum. The player with the Ridgeline waterfall over the cover and the MilkDrop visualizer as the ambient background. BPM readout in the top bar, source pill under the artist.">
      <b>Ridgeline spectrum.</b> The player with the Ridgeline waterfall over the cover and the MilkDrop visualizer as the ambient background. BPM readout in the top bar, source pill under the artist.
    </td>
    <td width="33%" valign="top">
      <img src="docs/screenshots/player-glass-spectrum.jpg" alt="Glass spectrum. The player with the single-line Glass spectrum over the cover and the visualizer as the ambient background. Pitch readout in the top bar, glass dock with lyrics, shuffle, mixer and queue.">
      <b>Glass spectrum.</b> The player with the single-line Glass spectrum over the cover and the visualizer as the ambient background. Pitch readout in the top bar, glass dock with lyrics, shuffle, mixer and queue.
    </td>
    <td width="33%" valign="top">
      <img src="docs/screenshots/player-deezer-waterfall.jpg" alt="Waterfall spectrum. The player with the waterfall over the cover, a Deezer source pill and the pitch readout in the top bar.">
      <b>Waterfall spectrum.</b> The player with the waterfall over the cover, a Deezer source pill and the pitch readout in the top bar.
    </td>
  </tr>
  <tr>
    <td valign="top">
      <img src="docs/screenshots/home-nav-bar.jpg" alt="Home. Recently Played and Liked Songs with TIDAL, Deezer, Qobuz and Local source pills, the mini player, and the glass nav bar with the Search button.">
      <b>Home.</b> Recently Played and Liked Songs with TIDAL, Deezer, Qobuz and Local source pills, the mini player, and the glass nav bar with the Search button.
    </td>
    <td valign="top">
      <img src="docs/screenshots/mixer-add-plugin.jpg" alt="Mixer. Surround buses (Front, Centre, LFE), a Reverb plugin editor, and the Add Plugin sheet with Utility, EQ &amp; Filter and Dynamics processors.">
      <b>Mixer.</b> Surround buses (Front, Centre, LFE), a Reverb plugin editor, and the Add Plugin sheet with Utility, EQ &amp; Filter and Dynamics processors.
    </td>
    <td valign="top">
      <img src="docs/screenshots/autoeq.jpg" alt="AutoEQ. Original, Target and Corrected response curves, smoothing, preamp, Peaking or Shelf ends algorithm, tone, automatic preamp and 2-channel calibration.">
      <b>AutoEQ.</b> Original, Target and Corrected response curves, smoothing, preamp, Peaking or Shelf ends algorithm, tone, automatic preamp and 2-channel calibration.
    </td>
  </tr>
</table>

## What's in it

| | |
| --- | --- |
| 🎧 **Every source in one player** | Local files (FLAC, ALAC, WAV, MP3 …) plus TIDAL, Qobuz and Deezer streams through the servers you add in Settings › Connections. |
| 📈 **A spectrum you can read** | Waterfall styles Lines, Ridgeline, Heat and Neon, or single-line Glass and Legacy. Analysis modes: Live average, Live max, Average and Max. |
| 🎛️ **DSP mixing console** | 4–48 buses plus master, up to 16 processors each, sends between buses, a 9.1.6 Atmos upmix preset and EBU R128 loudness meters. |
| 📐 **Headphone AutoEQ** | A 10-band parametric correction from 4,000+ measurements against 10 target curves, or your own CSV/TXT. |
| 🔌 **Bit-perfect USB DAC** | A UAC1/UAC2 driver over libusb writes PCM straight to the DAC, past Android's mixer, and falls back to the system output if it stalls. |
| ⏩ **Speed, pitch, tempo** | Two pitch engines (WSOLA and a phase vocoder), detected BPM, and a turntable-style tempo bend. |
| 🌌 **MilkDrop visualizer** | projectM with 9,795 presets, every one crash-tested under AddressSanitizer before it ships. Runs as the hero view or as an ambient layer. |
| 🌍 **Seven languages** | English, 简体中文, 日本語, Français, Español, Türkçe, Deutsch. |
| 📱 **Everywhere else** | Home-screen widget, Android Auto, Discord presence, scrobbling, offline downloads, account sync. |

## How the sound flows

```mermaid
flowchart LR
    S["Local · TIDAL · Qobuz · Deezer"] --> X["ExoPlayer decode"]
    X --> EQ["AutoEQ /<br/>parametric EQ"]
    EQ --> M["DSP console<br/>4–48 buses × 16 processors"]
    M --> MA["Master<br/>EBU R128 meters"]
    MA -.-> V["projectM visualizer"]
    MA --> O{"Output"}
    O -->|default| A["Android audio"]
    O -->|USB DAC| U["libusb UAC1 / UAC2<br/>bit-perfect"]
```

The DSP runs in a native C++17 library inside ExoPlayer's audio pipeline. Parameter changes reach the audio thread through lock-free atomics, so the UI can never block playback. NEON SIMD and denormal flush-to-zero keep each buffer cheap.

### The 36 processors

| Category | Processors |
| --- | --- |
| **Utility** | Gain · Stereo · Channel Mixer · Haas |
| **EQ & Filter** | Filter · 3-Band EQ · 10-Band EQ · Comb · Formant · Ladder · Nonlinear · Resonator · Disperser |
| **Dynamics** | Compressor · Limiter · Gate · Dynamics · Compactor · Transient Shaper · Trance Gate |
| **Distortion** | Distortion · Misstortion · Shaper · Bitcrush · Phase Distortion |
| **Modulation** | Chorus · Ensemble · Flanger · Phaser · Ring Mod · Tape Stop · Frequency Shifter · Pitch Shifter |
| **Space** | Delay · Reverb · Reverser |

Each has bypass, dry/wet and parameter smoothing. Out-of-range and non-finite values are rejected before they reach the audio thread.

## Building

Needs Android Studio, JDK 17, and the NDK and CMake from the SDK Manager. libusb and projectM are submodules; projectM has a nested one, so clone recursively.

```bash
git clone --recursive https://github.com/tryptz/Tryptify.git
./gradlew assembleDebug                      # build
./gradlew :app:testDebugUnitTest             # unit tests
app/src/main/cpp/dsp/tests/run_host_tests.sh # native DSP tests, no device needed
```

Release builds read their signing config from `keystore.properties` (see `keystore.properties.example`). Translations live in [`app/l10n/strings.tsv`](app/l10n/strings.tsv). Contributors and coding agents start at [`AGENTS.md`](AGENTS.md). What changed in each version: [release notes](docs/releases/1.9.2.md) and [CHANGELOG](CHANGELOG.md).

Built by **[tryptz](https://github.com/tryptz)**.
