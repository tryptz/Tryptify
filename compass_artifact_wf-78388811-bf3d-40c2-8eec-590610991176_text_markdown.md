s# Building a 1:1 Dolby Atmos renderer in Electron

**A fully open-source Dolby Atmos binaural renderer is technically achievable today** — the complete JOC/OAMD specification is freely published by ETSI, one open-source DD+ Atmos decoder already exists (Cavern), and the DSP primitives (HRTF convolution, VBAP panning, room modeling) are well-understood. The primary barrier is **patent exposure, not missing documentation**. Dolby's core JOC and binaural rendering patents expire around 2032–2034, and Canada's 2024 copyright amendments explicitly permit TPM circumvention for interoperability. This report provides the complete technical blueprint for monochrome.tf's Electron + Web Audio stack.

---

## The EC-3+JOC bitstream is fully documented in public ETSI standards

The entire Dolby Digital Plus with Atmos format is specified across two freely downloadable ETSI documents: **TS 102 366** (base AC-3/E-AC-3) and **TS 103 420** (JOC extension). No reverse engineering of binaries is needed.

### Syncframe structure

Each E-AC-3 syncframe begins with the 16-bit sync word `0x0B77`, followed by BSI (Bit Stream Information) containing channel configuration (`acmod`), LFE flag, dialog normalization, and critically the **`addbsi` field** where JOC presence is signaled. The frame contains 1–6 audio blocks of 256 samples each (typically 6 blocks = 1536 samples per frame at 48 kHz). JOC is flagged via `flag_ec3_extension_type_a` (1 bit) and `complexity_index_type_a` (8 bits, value 1–16 indicating decodable object count).

### JOC encoding mechanism

JOC operates on a fundamentally clever principle: the encoder **downmixes all objects into a backward-compatible 5.1/7.1 channel bed**, then computes parametric side information describing how to **reconstruct individual objects from that downmix**. Both the JOC upmix parameters and OAMD spatial metadata travel inside **EMDF (Extensible Metadata Delivery Format)** containers within the E-AC-3 frames. The JOC decoder works in the **QMF (Quadrature Mirror Filter Bank) domain** — it transforms the decoded channel audio into a time-frequency representation, applies per-band upmix matrices (Huffman-coded and differentially encoded per TS 103 420 Clause 6), and reconstructs up to **16 discrete object signals** for consumer delivery.

The JOC bitstream hierarchy is: `joc()` → `joc_header()` (downmix config, object count) → `joc_info()` (clip gains, band count, quantization) → `joc_data_point_info()` (interpolation slopes, timestamps) → `joc_data()` (Huffman-coded upmix matrix coefficients per frequency band per object per channel). Complete Huffman tables are in Annex A of TS 103 420.

### OAMD: every spatial parameter is specified

OAMD (Clause 5 of TS 103 420) carries per-object metadata with sub-frame timing granularity (updates can occur every 256 samples within a 1536-sample frame). Object positions use **3D Cartesian coordinates** (X = left/right, Y = front/back, Z = up/down with sign bit), normalized to room dimensions. The full parameter set per object includes:

- **Position**: `pos3D_X_bits`, `pos3D_Y_bits`, `pos3D_Z_bits` with optional extended precision (`ext_prec_pos3D_*`) and differential encoding (`diff_pos3D_*`)
- **Gain**: `object_gain_idx` selecting from a predefined table, or `object_gain_bits` for custom values
- **Size/spread**: `object_size_idx` (isotropic) or separate `object_width_bits`, `object_depth_bits`, `object_height_bits` for anisotropic spread
- **Priority**: `object_priority_bits` — used by the renderer when speaker count is insufficient for all objects
- **Channel lock**: `b_object_snap` — snap to nearest speaker
- **Zone constraints**: `zone_constraints_idx` — restrict rendering to specific speaker zones
- **Divergence**: `object_div_mode` and associated parameters
- **Screen/room anchoring**: `b_object_use_screen_ref` toggles between screen-relative and room-relative coordinates

### EC-3+JOC versus AC-4 IMS

**EC-3+JOC** (Dolby Digital Plus Atmos) maintains full backward compatibility — any DD+ decoder plays the 5.1 bed. It operates at **384–768 kbps** for Atmos content, supports up to 16 consumer objects, and carries JOC/OAMD in EMDF containers. **AC-4 IMS** (ETSI TS 103 190) is a next-generation codec ~50% more efficient, with a 2-channel stereo/binaural core (not backward-compatible with DD+), Advanced JOC (A-JOC) with improved decorrelation, native OAMD substreams, and — critically — **respects the mix engineer's binaural render settings** (Near/Mid/Far per object). DD+JOC ignores binaural metadata from the Atmos master, applying a generic "Mid" setting instead. AC-4's frame structure uses a Table of Contents (TOC) approach supporting multiple presentations in a single bitstream.

### Existing parsers and the FFmpeg gap

**FFmpeg detects but does not decode JOC**. Its `eac3dec.c` reads `flag_ec3_extension_type_a` and reports the profile as "Dolby Digital Plus + Dolby Atmos," but performs zero EMDF parsing, zero QMF processing, and zero upmix matrix application. You get only the 5.1/7.1 channel bed. The one open-source project that has implemented JOC decoding is **Cavern** (github.com/VoidXH/Cavern), a .NET audio engine that claims to be "the world's first open-source Dolby Digital Plus Atmos decoder." Dolby's own GitHub releases (`dlb_mp4demux`, `dlb_mp4base`, `dlb_pmd`) handle only container demuxing and production metadata — not JOC decoding.

---

## How the Dolby Atmos renderer actually works

### Signal flow from bitstream to binaural output

The renderer processes **up to 128 simultaneous inputs** — typically a 7.1.2 bed (10 channels) plus up to 118 discrete mono objects in cinema, or a 7.1 bed plus spatially coded elements in consumer delivery. The complete chain is:

1. **Bitstream decode**: Standard E-AC-3 decoding produces channel-based PCM
2. **JOC reconstruction**: QMF analysis → upmix matrix application → QMF synthesis yields individual object PCM signals
3. **OAMD parsing**: Extract per-object position, gain, size, priority for each metadata update interval
4. **Object Audio Renderer (OAR)**: Compute VBAP-based panning gains from (x,y,z) coordinates to the target speaker layout
5. **Bed remapping**: Map fixed bed channels to the output configuration
6. **Summation**: Object speaker feeds + bed feeds
7. **Binaural path** (headphones): Replace speaker routing with per-object HRTF convolution + distance modeling + room simulation

For consumer delivery via DD+JOC, **spatial coding** reduces the full object count to 12–16 "elements" through dynamic perceptual clustering (Dolby patent US9805725B2). The clustering algorithm considers spatial proximity, content type (dialog/music/effects), priority metadata, and "media intelligence" analysis. Clusters move dynamically — audio migrates between them as the mix evolves.

### Panning, interpolation, and decorrelation

Dolby uses a proprietary variant of **Vector-Base Amplitude Panning (VBAP)** with extensions for the size/spread parameter. When an object has nonzero size, decorrelation is automatically applied across activated loudspeaker feeds to create a wider, more diffuse image. Patent WO2016210174A1 describes a "Solo-Mid" panning architecture that decomposes signals into wall-projected (direct) and origin (diffuse/decorrelated) components.

Position interpolation between metadata updates prevents "zipper noise" from discontinuous rendering-matrix changes. Patent US9883311 reveals that legacy systems updated rendering matrices at **40-sample intervals**, and the patent describes smoothing methods for coarser metadata update rates. In practice, the renderer likely interpolates panning coefficients at the audio buffer rate (every 128–512 samples) using linear or cosine ramps.

### Binaural rendering specifics

Dolby's binaural renderer uses a **proprietary generic HRTF** (undisclosed dataset) with four distance modes per object: **Off** (no spatialization), **Near** (~1m), **Mid** (default), and **Far** (~2.6m). Each mode applies differently scaled room simulation — early reflections + late reverb — along with HRTF directional filtering and spectral shaping. The renderer distinguishes **headlocked** objects (bypass head tracking; static relative to ears — used for UI/narration) from **worldlocked** objects (HRTF filters update dynamically with head orientation).

Dolby's Personalized HRTF (PHRTF) Creator captures ~50,000 points of head/ear/shoulder geometry via iPhone camera. Apple implements its **own independent spatial audio engine** with a different proprietary HRTF, completely bypassing Dolby's binaural renderer for Apple Music Atmos playback.

---

## HRTF implementation: datasets, convolution, and room modeling

### Choosing the right HRTF dataset

For a music player targeting studio-quality binaural rendering, the **Bernschütz KU100 dataset** (TH Köln) is the strongest choice. The Neumann KU100 is the industry-standard high-end dummy head. This dataset provides **2,702 measurement directions** on a Lebedev grid with precisely **128-tap impulse responses** (post-processed for phase compensation and low-frequency extension), measured at 3.25m using a Genelec 8260A via a VariSphear robotic system. It supports spherical harmonic decomposition up to order ~50, carries a CC BY-SA 3.0 license, and was perceptually preferred over individual human HRTFs in the SADIE II study. The 128-tap length is ideal — it matches the Web Audio API's render quantum exactly.

**SADIE II** (University of York, Apache 2.0) provides 31 subjects including KU100 and KEMAR dummy heads at up to 8,802 directions, with BRIRs and headphone compensation filters for Beyerdynamic DT990. For future HRTF personalization, the **HUTUBS database** (96 listeners + anthropometric data + 3D head models) or **SONICOM** (200 listeners) provide the population diversity needed to train a selection/individualization algorithm.

### SOFA file parsing

SOFA (AES69) files use NetCDF-4/HDF5 containers. The essential fields are `Data.IR` (M×R×N array of impulse responses: M measurements × R=2 receivers × N samples), `SourcePosition` (M×3 in spherical or Cartesian coordinates), and `Data.SamplingRate`. The C library **libmysofa** (github.com/hoene/libmysofa) is the go-to parser — it provides KD-tree indexed lookup with weighted nearest-neighbor interpolation:

```c
struct MYSOFA_EASY *hrtf = mysofa_open("ku100.sofa", 48000, &filter_length, &err);
mysofa_getfilter_float(hrtf, x, y, z, leftIR, rightIR, &leftDelay, &rightDelay);
```

A **WASM port exists** (ColumbiaCEAL/libmysofa-wasm) for direct browser integration.

### Partitioned convolution engine

The optimal algorithm for 128-tap HRIRs at 128-sample block size is **Uniformly Partitioned Overlap-Save (UPOLS)**. With a single partition, each object requires: one **256-point forward FFT** of the input block (128 new + 128 old samples), two **complex multiplications** (256 bins × 2 ears) with the pre-computed HRTF spectra, and two **256-point inverse FFTs**. The last 128 samples of each IFFT output are valid. Total cost per object per block: ~5,000 complex multiply-accumulates, running 375 times/second at 48 kHz. With WASM SIMD (`-msimd128` in Emscripten, processing 4 floats simultaneously), a modern CPU core handles **50–100 simultaneous objects** in real time.

For moving sources, **frequency-domain crossfading** between old and new HRTF spectra avoids the doubled computation cost of time-domain crossfading. Critically, **ITDs must be managed separately** via fractional-delay lines — interpolating full HRIRs with different ITDs creates comb-filtering artifacts. The 3D Tune-In Toolkit demonstrates this: extract ITDs before interpolation, apply only minimum-phase magnitude interpolation, then reapply ITDs via dedicated delay lines.

### Spherical harmonic interpolation

For smooth continuous positioning, decompose the HRTF dataset into **spherical harmonics** (SH):

```
H(θ,φ,f) = Σ_{n=0}^{N} Σ_{m=-n}^{n} aₙᵐ(f) · Yₙᵐ(θ,φ)
```

The Bernschütz L2702 grid supports SH order 32+, which covers the full audible bandwidth (~20 kHz). At order 32, you store **(N+1)² = 1,089 coefficients** per frequency bin per ear — compact and enabling instant HRTF reconstruction at any arbitrary direction via a simple dot product with the SH basis evaluated at that direction. For the 128-point HRIR, this means 1,089 × 129 (frequency bins) × 2 (ears) ≈ 281K complex coefficients total, fitting comfortably in a SharedArrayBuffer.

### Distance rendering and room simulation

Distance cues require three components: **gain rolloff** (inverse-square law: -6 dB per doubling), **air absorption** (frequency-dependent low-pass, ~0.01–0.1 dB/m at 1–10 kHz), and **direct-to-reverberant ratio (DRR) modulation** — the strongest distance cue beyond a few meters. For near-field sources (<1m), the **Distance Variation Function (DVF)** model applies a first-order shelving filter per ear plus gain corrections, achieving <1 dB spectral distortion.

Room simulation uses a hybrid **Image Source Method (ISM) + Feedback Delay Network (FDN)** architecture. ISM computes 2nd-order early reflections (~30 image sources for a shoebox room), each convolved with its own HRTF for direction plus wall absorption filtering. An 8–16 line FDN with Hadamard feedback matrix generates late reverb with frequency-dependent T60 control. This maps directly to Dolby's Near/Mid/Far binaural modes — each mode scales the virtual room dimensions and reflection timing.

---

## Electron and Web Audio implementation architecture

### The critical interception problem

Chromium's media pipeline decodes EC-3 internally — by the time audio reaches the Web Audio API, it's decoded PCM with all JOC metadata stripped. **You must bypass Chromium's pipeline entirely.** The recommended architecture for monochrome.tf:

```
┌─────────────── MAIN PROCESS (Node.js) ────────────────┐
│                                                         │
│  File Reader (fs) → MP4/MKV Demuxer → EC-3+JOC Track  │
│                                    ↓                    │
│         Native Addon (N-API) or WASM Module             │
│         ├─ EC-3 Bed Decoder (FFmpeg libavcodec)         │
│         ├─ JOC Upmixer (ETSI TS 103 420 Clause 6)      │
│         ├─ OAMD Parser (TS 103 420 Clause 5)            │
│         └─ Output: Object PCM[] + Metadata[]            │
│                        ↓                                │
│              SharedArrayBuffer (IPC)                     │
└─────────────────────────┬───────────────────────────────┘
                          ↓
┌─────────── RENDERER PROCESS (AudioWorklet) ────────────┐
│                                                         │
│  AudioWorkletProcessor (WASM+SIMD)                      │
│  ├─ Read object PCM + positions from SharedArrayBuffer  │
│  ├─ Per-object: SH-interpolated HRTF lookup             │
│  ├─ Per-object: UPOLS convolution (256-pt FFT)          │
│  ├─ Distance: gain rolloff + LPF + DRR                  │
│  ├─ Room: ISM early reflections + FDN reverb             │
│  └─ Sum all objects → stereo binaural output            │
│                        ↓                                │
│              AudioContext.destination                     │
└─────────────────────────────────────────────────────────┘
```

### Container demuxing

Parse MP4 containers in Node.js using **mp4box.js** (or Bento4's `mp4dump` for analysis). Extract the EC-3 audio track by locating the `dec3` box within the sample entry, which contains the JOC signaling flags. Raw EC-3 frames are then passed to the decoder. For MKV containers, use a lightweight Matroska parser to extract the `A_EAC3` codec-tagged track.

### Native addon vs. WASM for the decoder

Two viable approaches exist for the decode + JOC reconstruction pipeline:

**Option A — N-API native addon (recommended for performance)**: Build a C++ addon using `node-addon-api` that wraps FFmpeg's `libavcodec` for EC-3 bed decoding plus a custom JOC implementation per ETSI TS 103 420. Use `Napi::AsyncWorker` or `napi_create_threadsafe_function` to run decoding on a dedicated thread. Communicate with the renderer process via SharedArrayBuffer. This approach gives native performance and avoids the ~12–25x overhead of ffmpeg.wasm.

**Option B — Focused WASM module**: Compile only the E-AC-3 decoder from FFmpeg's libavcodec (not the full ffmpeg binary) plus your JOC implementation to WASM using Emscripten. The `@mediabunny/ac3` project demonstrates this pattern — extracting just the AC-3/E-AC-3 codecs into a minimal WASM build. This is more portable than a native addon but slower.

### AudioWorklet + WASM convolution engine

The HRTF rendering runs on the audio thread via an AudioWorkletProcessor backed by a WASM module compiled with Emscripten flags `-sAUDIO_WORKLET -sWASM_WORKERS -msimd128`. The WASM module contains the partitioned convolver, SH interpolation engine, distance processing, and room simulation. Pre-load the KU100 HRTF SH coefficients into a SharedArrayBuffer at startup — the AudioWorklet thread reads them directly with zero copy.

The AudioWorklet's fixed **128-frame render quantum** aligns perfectly with the 128-tap KU100 HRIRs, enabling single-partition UPOLS with no additional ring buffering. Expected pipeline latency: EC-3 decode (~32ms per frame) + AudioWorklet double buffer (~5.8ms) + OS audio (~15ms) = **~53ms total** — imperceptible for music playback.

### Leveraging existing spatial audio libraries

Several open-source libraries can accelerate development:

- **3D Tune-In Toolkit** (C++, GPLv3): The most complete binaural renderer — HRTF convolution with SOFA support, ITD management, near-field compensation, room reverb. Compile to WASM for the AudioWorklet. Its `overlap-save` convolver with per-ear parallax is directly applicable.
- **Google Resonance Audio** (Web SDK): Provides ambisonics-based spatial rendering with room modeling. Useful if you adopt an ambisonics intermediate representation for scalability beyond ~50 objects — encode each object cheaply via gain matrix to a 3rd-order ambisonic bus (16 channels), then decode to binaural with a fixed 16-channel convolution.
- **JSAmbisonics**: Flexible HOA library supporting custom SOFA-loaded HRTFs. Excellent for the ambisonics decode stage.
- **libmysofa**: Essential for SOFA file reading. The WASM port (ColumbiaCEAL/libmysofa-wasm) integrates directly.

---

## Prior art and reference architectures worth studying

### Cavern: the proof of concept

**Cavern** (github.com/VoidXH/Cavern) is the single most important reference project. This .NET audio engine has implemented DD+JOC decoding from the ETSI specification, parsing OAMD metadata and reconstructing audio objects. It supports `.ec3`, `.m4a`, `.mkv`, `.mp4` containers and includes HRTF binaural rendering. It does **not** support TrueHD Atmos (lossless). Its license "heavily discourages commercial usage." While you cannot directly use its code in a C++/WASM project, studying its JOC implementation against the ETSI spec is invaluable for understanding practical parsing decisions.

### The MPEG-H reference decoder

Fraunhofer's **mpeghdec** (github.com/Fraunhofer-IIS/mpeghdec) is an open-source ISO/IEC 23008-3 decoder in C/C++ under the FDK license. It implements a complete object-based audio renderer with binaural output — the same architectural pattern needed for Atmos: object metadata parsing → position interpolation → VBAP panning → HRTF convolution. While it handles MPEG-H bitstreams (not EC-3+JOC), its rendering pipeline is directly transplantable. The key architectural difference: MPEG-H transmits objects natively in the bitstream, while Atmos uses JOC parametric reconstruction from a channel downmix.

### The ADM renderer as a legal safe harbor

The **EBU ADM Renderer** (github.com/ebu/ebu_adm_renderer) implements ITU-R BS.2127 — a complete, standardized rendering specification developed collaboratively by BBC, IRT, Fraunhofer, and Dolby. Open-source libraries **libadm** and **libbw64** (both Apache 2.0) handle ADM metadata and BW64 file I/O. The **IRT Binaural NGA Renderer** (github.com/IRT-Open-Source/binaural_nga_renderer) adds binaural output.

ETSI TS 103 420 Annex D provides explicit guidance for converting OAMD to ADM format. This creates a legally defensible pipeline: decode EC-3 + parse JOC/OAMD per the published ETSI spec → convert to ADM → render through the standardized ITU renderer. The ADM Renderer specification is patent-unencumbered for the rendering algorithm itself.

---

## Legal landscape favors this project in Canada

### Copyright: section 30.61 explicitly permits this

Canada's Copyright Act **section 30.61** allows reproducing a computer program "for the sole purpose of obtaining information that would allow the person to make the program and another computer program interoperable." Subsection (2) explicitly states this applies **even if the resulting interoperable program is sold or distributed**. In November 2024, **Bills C-244 and C-294** received Royal Assent, adding exceptions for TPM circumvention for interoperability purposes — removing the last copyright-based barrier.

Critically, implementing from the **freely published ETSI TS 103 420 specification** may not constitute "reverse engineering" at all — it is implementing from an open standard. This is the safest legal path.

### Patents: the real constraint

Dolby holds extensive patents with expiration dates around **2032–2034**:

- **US9805725B2**: Object clustering for spatial coding (filed 2013)
- **US9933989B2 + continuations**: Binaural rendering using authoring metadata (filed 2014, continuations through 2022)
- **US9883311**: Rendering matrix interpolation for smooth transitions (filed 2014)
- **WO2016210174A1**: Solo-Mid panning architecture with decorrelation

Patent claims are narrow and specific. **Generic VBAP panning** predates Dolby's work (Pulkki, 1997). **HRTF-based binaural rendering** is a decades-old technique. Dolby's patents cover specific combinations: JOC parametric reconstruction, OAMD-specific metadata transport in EMDF, and their particular authoring-metadata-driven binaural pipeline. Workarounds include: using ADM-based rendering (ITU-R BS.2127), implementing generic HRTF convolution without Dolby's specific binaural metadata interpretation, and avoiding Dolby's exact spatial coding clustering algorithm for any re-encoding paths.

---

## Complete implementation roadmap for monochrome.tf

### Phase 1: Foundation (weeks 1–4)

Build the container demuxer and EC-3 bed decoder. Use mp4box.js for MP4 parsing and FFmpeg's libavcodec (compiled as a native N-API addon) for E-AC-3 channel decoding. Set up the AudioWorklet scaffold with a WASM-backed processor that receives decoded PCM via SharedArrayBuffer and passes it through to `AudioContext.destination`. Verify end-to-end audio playback of the 5.1 bed from an Atmos-encoded file.

### Phase 2: JOC reconstruction (weeks 5–10)

Implement the JOC decoder per ETSI TS 103 420 Clauses 5–7 in C++, compiled into the native addon. This requires: an EMDF parser to extract JOC and OAMD payloads from EC-3 frames, a **64-band QMF analysis filterbank** (coefficient tables in TS 103 420 Clause 7), Huffman decoding of upmix matrix coefficients (tables in Annex A), temporal interpolation of JOC parameters, matrix multiplication in the QMF domain to reconstruct object signals, and QMF synthesis back to time domain. Use Cavern's .NET implementation as a cross-reference against the spec. Output: N mono object PCM streams + parsed OAMD metadata per frame, delivered to the renderer process.

### Phase 3: HRTF binaural renderer (weeks 8–14, overlapping with Phase 2)

Compile the core rendering engine to WASM: libmysofa for SOFA reading, a custom UPOLS convolver with SIMD, and SH interpolation. Load the Bernschütz KU100 L2702 dataset, pre-compute SH coefficients to order 32 offline, store in a binary blob loaded into SharedArrayBuffer at startup. The AudioWorkletProcessor reads object positions from shared memory, evaluates SH at each object's direction to reconstruct the HRTF pair, and convolves via UPOLS. Implement crossfading (32–128 sample ramp) when HRTF filters change between frames. Add distance rendering: inverse-square gain, 1st-order LPF for air absorption, DRR scaling.

### Phase 4: Room simulation and polish (weeks 12–18)

Add ISM early reflections (2nd order, ~30 image sources for a configurable shoebox room) with per-reflection HRTF convolution and wall absorption filtering. Implement an 8-line FDN with Hadamard feedback matrix for late reverb. Map Dolby's Near/Mid/Far concept to three room size presets: Near = small room (T60 ~0.3s), Mid = medium room (T60 ~0.6s), Far = large room (T60 ~1.2s). Add headphone compensation filtering (load EQ curves for common headphone models from SADIE II).

### Phase 5: Optimization and scalability (weeks 16–20)

Profile the AudioWorklet under load. For >16 simultaneous objects, implement the **ambisonics fallback path**: encode low-priority objects to a 3rd-order ambisonic bus (16 channels, ~cheap gain-matrix operation per object), then decode the bus to binaural with 16 fixed convolutions. High-priority objects (especially dialog) retain per-object HRTF convolution. This hybrid approach scales to **200+ objects** with graceful degradation. Enable WASM Relaxed SIMD (`-mrelaxed-simd`) for FMA instructions providing 1.5–3x additional throughput on supported hardware.

### Key library choices

| Component | Library | Language | License |
|-----------|---------|----------|---------|
| MP4 demuxing | mp4box.js | JS | LGPL |
| EC-3 bed decode | FFmpeg libavcodec | C (N-API addon) | LGPL |
| JOC reconstruction | Custom (per ETSI spec) | C++ (N-API addon) | Your license |
| SOFA reading | libmysofa | C → WASM | BSD |
| HRTF dataset | Bernschütz KU100 L2702 | SOFA file | CC BY-SA 3.0 |
| Convolution engine | Custom UPOLS | C++ → WASM+SIMD | Your license |
| Room reverb | Custom ISM+FDN | C++ → WASM | Your license |
| Ambisonics fallback | JSAmbisonics or custom | JS/WASM | BSD |
| Reference renderer | EBU ADM Renderer (libear) | C++ | Apache 2.0 |

---

## Conclusion

The technical path to a 1:1 Dolby Atmos binaural renderer in Electron is clearer than most assume. **ETSI TS 103 420 is a complete, freely published specification** — not a guarded secret — and Cavern has already demonstrated open-source DD+JOC decoding. The DSP is standard: UPOLS convolution at 128-sample partitions with the KU100 dataset provides studio-grade binaural quality using ~5,000 complex operations per object per block. The novel insight from this analysis is that the **legally safest architecture converts OAMD to ADM metadata** (per TS 103 420 Annex D) and renders through the ITU-R BS.2127 pipeline, sidestepping Dolby's renderer-specific patents while using their own spec's conversion guidance. The Electron-specific architecture — N-API addon for decode/JOC, SharedArrayBuffer bridge, WASM+SIMD AudioWorklet for rendering — hits the ~53ms latency target with 50–100 objects on a single CPU core. Patent risk remains the primary concern (expiry ~2034), but the combination of Canada's s.30.61 interoperability exception, the published ETSI specifications, and ADM-based rendering creates a defensible position for the monochrome.tf project.