# `cpp/atmos` — clean-room Dolby Atmos renderer foundation

This directory holds the first, **fully self-contained and verifiable** layer of
the E-AC-3 JOC → object → binaural/bed renderer described in the renderer plan.
Everything here is clean-room from the public ETSI specifications
(TS 102 366 / A/52 for E-AC-3 + EMDF, TS 103 420 for OAMD + JOC) and depends on
nothing but the C++17 standard library, so it builds under the NDK for every
shipped ABI and is unit-tested on a host toolchain.

## What is here (and verified)

| File | Purpose | Verification |
|---|---|---|
| `bit_reader.h` | MSB-first bit reader + ETSI `variable_bits` escape | round-trips against a matching writer for many values / group widths |
| `emdf.h` | EMDF container **framing** walk; locates OAMD (id 11) / JOC (id 14) payload byte ranges | round-trips a synthesized two-payload container |
| `oamd.h` | OAMD absolute/differential position **coordinate math** + normalized-cube → render-space mapping | boundary + center + corner values checked numerically |
| `vbap.h` | `VBAPBedPanner` back-end: object direction → 5.1 / 7.1 / 7.1.4 speaker gains (2-D adjacent-pair + 3-D tightest-triplet VBAP) | source-at-speaker, bracketed-pair, energy-preservation and non-negativity invariants over an angular sweep |

Run the host tests:

```sh
cd tests
c++ -std=c++17 -I.. atmos_tests.cpp -o atmos_tests && ./atmos_tests
```

The `monochrome_atmos` CMake target compiles `atmos.cpp` (which includes every
header) so the NDK type-checks the whole set for `arm64-v8a`, `armeabi-v7a` and
`x86_64` on each CI build. It has no runtime consumers yet.

## What is deliberately **not** done here

These need reference Dolby content on-device to validate and are intentionally
not stubbed to look finished:

- **E-AC-3 syncframe / substream walk** and the EMDF location inside the audio
  block skip field (plan A1–A2). `emdf.h` walks the container once you hand it
  the EMDF bytes; extracting those bytes from a real bitstream is the next step.
- **EMDF per-payload config block** (sample offset, duration, group id, codec
  data, `emdf_protection`). `walk_emdf` reads the id/size framing only — see the
  scope note in `emdf.h`. A renderer only *reads* EMDF, so `emdf_protection`
  validation is out of scope.
- **OAMD frame parsing** (object count, bed vs dynamic split, per-block info
  ramps) — the coordinate math in `oamd.h` is the reusable core; the bitstream
  framing that feeds it is TODO.
### Ported from Cavern (see `cavern/` — NOT clean-room)

- **QMF analysis/synthesis filterbank** (plan A5) — `cavern/quadrature_mirror_filterbank.h`.
  Host-verified: analysis → synthesis reconstructs a broadband signal at
  correlation 1.00000 (577-sample delay).
- **JOC decoder + upmix applier** (plan A6) — `cavern/joint_object_coding.h`
  (frame decode, Huffman, dequant, per-timeslot matrix interpolation) and
  `cavern/joint_object_coding_applier.h` (QMF-domain channel→object mix +
  inverse QMF). `cavern/joc_tables.h` holds the Huffman/band tables. Cavern's
  ThreadPool fan-out is sequential here; exceptions become a `valid()` flag.
  Host-tested for Huffman round-trip, dequant recurrence, band mapping and an
  end-to-end decode→matrices→apply run. **Numeric bit-exactness still needs A/B
  against reference Atmos content** — the host tests prove self-consistency, not
  equivalence to a real Dolby stream.

**License obligation:** Cavern's licence is non-commercial, no-ads, attribution
+ source-link, and requires the creator's permission for public/commercial use.
See `cavern/NOTICE.md`. The rest of this directory stays clean-room; the
`cavern/` subtree does not.

- **OAMD object-info-block decode** (plan A4) — `cavern/object_info_block.h`
  decodes a per-object OAMD update: absolute/differential position, gain, size,
  anchor (bed/room/screen). Bitstream decode is reproduced exactly; Cavern's
  `UpdateSource()` render integration (Listener/Source) is not ported —
  `resolved_position()` applies the differential + normalized-cube→render-space
  map instead. Host-tested (16 checks): absolute position, gain, bed anchor.
- **OAMD frame decode** (plan A4) — `cavern/object_audio_metadata.h` decodes the
  frame above the info blocks: object count, program / bed-channel assignment
  (dynamic-only, standard/non-standard beds, ISF) and the object-audio elements
  (`OAElementMD`) that own the per-object info-block grid and its ramp timing.
  Bit decode is reproduced exactly — including BitExtractor.ReadBits's reverse
  array fill and the element `Position` padding seek; Cavern's `UpdateSources()`
  render integration is not ported. Host-tested (25 checks): full frame → info
  blocks, standard bed assignment, LFE position, unsupported-version guard.
- **EMDF container decode** — `cavern/extensible_metadata_decoder.h` scans the
  E-AC-3 skip field for the EMDF syncword, walks the payload list (version/key
  escape, per-payload config bits, VariableBits payload sizing) and routes OAMD
  (id 11) and JOC (id 14) to the decoders above, skipping other payloads by
  size. `emdf_protection` is not validated (a renderer only reads EMDF).
  Host-tested (12 checks): syncword scan past a garbage prefix, payload sizing,
  OAMD routing, and a no-syncword no-op.

- **E-AC-3 syncframe header** — `cavern/enhanced_ac3.h` parses the fixed
  syncframe fields (decoder version/bsid, strmtyp, sample rate, channel mode,
  block count, LFE, frame size, plus the AC-3 "repackaged" frame-size recalc).
  Cavern's streaming BlockBuffer/Expand is replaced by a flat BitReader over a
  caller-supplied frame. `reference_channel.h` holds the shared channel enum.
  Host-tested (20 checks): E-AC-3 independent 5.1 header, dependent-substream id
  offset, bad-syncword rejection.

Still to port from Cavern: the rest of the E-AC-3 core decode. Note the Atmos
EMDF payload does NOT ride in the header `addbsi` — Cavern's decoder runs the
EMDF walker over the audio frame's **auxiliary data** (`body.GetAuxData()`, the
frame-end skip field), matching `emdf.h`. So reaching the Atmos metadata on a
real frame needs the body framing, and producing the bed PCM JOC upmixes needs
the full audio-block decode (BSI alignment, bit allocation, exponents,
mantissas, coupling, spectral extension, inverse transform). That audio-block
numeric decode is self-consistency-testable here but needs reference Atmos
content for the bit-exactness A/B the plan's phase 2 calls for. Then the
JNI/Media3 wiring.
- **HRTF binauralizer** back-end (plan A7) — will reuse libmysofa + a NEON
  convolver and the app's existing AutoEQ/HRTF infra.
- **JNI surface + Media3 `AtmosAudioProcessor`** wiring into `monochrome_dsp`.

See the phased acceptance criteria in the renderer plan (§7). This commit lands
Phase-0-adjacent foundations: the pieces that are provable in isolation.

## Render pipeline — as shipped

The above "still to port" notes predate plan Option B2, which is what actually
shipped: NextLib's FfmpegAudioRenderer decodes the E-AC-3 core bed, a Kotlin
sample tap preserves each raw access unit, and `AtmosPipeline`
(`atmos_pipeline.h`) reconstructs and renders the objects per frame. Behaviors
worth knowing when debugging:

- **Metadata association is time-keyed.** The `AtmosAudioProcessor` matches
  raw frames to decoded PCM by presentation time against an anchored clock
  (re-anchoring on discontinuities), not by arrival order. JOC/OAMD hold
  across payload-less frames (expiring after 32) per the DD+ hold-until-update
  rule.
- **Both output paths are latency-aligned.** The object render's bed passes
  through QMF analysis+synthesis — a measured 577-sample delay (pinned by
  `cavern_qmf_test`). The fallback ITU downmix runs every frame behind the
  same delay, and render<->downmix switches are equal-power crossfades, so a
  seam is neither a timbre cut nor a time jump.
- **Motion is crossfaded.** The HRIR renderer dual-convolves against the
  previous and current interpolated IR on any direction change (raised-cosine
  blend, per-sample ITD ramp), and object positions use the OAMD frame's LAST
  info block. Per-object OAMD gains are applied with per-frame ramps.
- **DRC is honest.** The FFmpeg bed decode always applies Line-mode `dynrng`
  (drc_scale=1.0, no NextLib option surface). OFF adds nothing on top;
  LIGHT/STANDARD are the renderer's own leveling; HEAVY applies the stream's
  `compr` RF gain word (parsed in `cavern/enhanced_ac3.h`) with the envelope
  compressor kept only as overload protection.
- **Bass and LFE.** Bass management is an LR4 (Linkwitz-Riley) split, flat to
  <0.1 dB; the LFE is always summed diffusely to both ears, never HRIR-placed.
- **Wet/dry blending is time-aligned.** The dry band shares the wet path's
  per-ear fractional ITD delays, so binaural strength is continuous
  (strength 0 = ITD-only render).

Host tests live in `tests/` (each file states its compile line); the render
suite is `hrtf_render_test`, `hrtf_motion_test`, `hrtf_polish_test`,
`atmos_pipeline_test`, `object_engine_test`.

## Loudspeaker render (5.1 .. 9.1.6)

`speaker_renderer.h` renders the reconstructed objects to a physical layout —
5.1, 7.1, 5.1.2, 5.1.4, 7.1.2, 7.1.4, 9.1.4, 9.1.6 — for HDMI receivers and
multichannel USB interfaces (`AtmosPipeline::process_frame_speakers`, JNI
`nativeSetOutputLayout` / `nativeProcessFrameSpeakers`). Output is interleaved
in Android channel-mask order so the Kotlin side hands it to the AudioTrack
unchanged (`AtmosAudioTrackProvider` sets the exact mask; 9.1.x travels as a
24-channel frame whose extra positions are silent, because Media3 1.5 only
accepts 1-8, 10, 12 and 24 channels).

- **Positions**: OAMD room-cube coordinates are warped onto the Dolby home
  speaker angles (cube azimuth 45 -> 30 deg, 135 -> 150 deg; the top of a wall
  lands on the 45-degree top speakers) before panning — raw cube angles would
  put the front corners at 45 degrees, between L and the side.
- **Panning**: VBAP over the precomputed convex hull of the layout, closed with
  imaginary zenith/nadir speakers whose gain is shared out, so every direction
  has a solution on every layout (the constant-power grid test checks this).
- **Object properties (TS 103 420 clause 5.2)**: size (box sampling, power
  sum), divergence (power-preserving centre + two sides, BS.2127-style), zone
  constraints (one hull per constraint over the permitted speakers, speaker ->
  zone map of Table A.7), channel lock (nearest speaker). Bed objects play from
  their own channel when the layout has it; the LFE object goes to the LFE.
- **Fallback**: frames without JOC play the decoded bed on the layout, delayed
  by the same 577-sample QMF latency and crossfaded, exactly like the stereo path.

Host tests: `speaker_renderer_test`, `atmos_speaker_pipeline_test`.

## OAMD decode fixes against TS 103 420

Cavern's decode (and this port) disagreed with the spec in places that change
what is heard. Fixed, with tests in `cavern_oamd_resolve_test`:

- `read_signed` returned 0 for every input (a C# precedence bug upstream), so
  every differential position update was dropped and moving objects froze
  between absolute updates. It is now the spec's two's-complement delta.
- Differential positions are relative to the PREVIOUS metadata block (clause
  5.3), not the same block slot of the previous frame; Z clamps to [-1, 1].
- `oa_element_size` counts BYTES after the size field (clause 5.6.4.3). The port
  seeked `size + 1` BITS from before it — harmless with one element, but it
  desynchronised any element after the first. Seeking is now forward-only.
- `object_gain_idx == 3` is the previous OBJECT's gain in the same block
  (Table 18), not a hold; inactive objects are silent (Table 28).
- Blocks follow the default / full / reuse / mixed rules of Tables 28-29, so
  unsignalled fields hold instead of resetting.
- Newly decoded and applied: zone constraints + elevation, per-axis size, snap,
  screen anchoring (clause 5.2.1.3, with Cavern's default screen), room-distance
  projection (clause 5.2.1.2), and the extended_object_element (divergence and
  extended-precision position).
- Bed objects take their channel's position (they used to sit at the cube
  origin, the front-left corner), in the binaural path as well.

Still open / approximate:

- ISF (stacked-ring) objects: the spec gives ring sizes and order but not the
  ring azimuths, so rings are spaced evenly from the front.
- The obj_render_info[] bit order: the syntax (index 0 = position) and Table 31
  (index 3 = position) disagree; the port keeps Cavern's reading, which matches
  the syntax and the other flag arrays. Confirm against reference content.
- Not compared with Dolby's reference renderer or certified test content (both
  need a Dolby licence). What IS checked against Dolby is below.
- AC-4 is not decoded natively (there is no AC-4 decoder in FFmpeg); it plays
  through the platform decoder or HDMI passthrough.

Run every host test with CTest: see `tests/CMakeLists.txt`.

## Real-content reference check

`tools/reference_check/run.sh` decodes the bundled `assets/atmos_test.mp4` (a
real 768 kb/s E-AC-3 JOC stream, the profile TIDAL serves) with FFmpeg, runs
this code over it, renders the objects to 5.1 and compares with the stream's
own 5.1 core — which is Dolby's encoder rendering the same objects to 5.1. It
exits non-zero when an audible moving object's path stops tracking the core,
when a speaker stops tracking the same speaker of the core, or when the energy
distribution across speakers diverges.

It found two bugs that no synthetic test could, both in ObjectEngine and both
present in the binaural path too:

- JOC's inputs are FL FR FC SL SR [RL RR] with no LFE, but the decoded bed
  (FL FR FC LFE SL SR ...) was fed straight in, so JOC's left surround was the
  LFE and its right surround the left surround.
- OAMD numbers objects with the LFE bed object included; JOC reconstructs only
  the others. Objects were paired 1:1, so every object got the next one's
  metadata — on the test clip the audible voice sat frozen at the front-left
  corner while the silent objects moved. The core LFE is now spliced in at the
  OAMD LFE slot (latency-matched), as Cavern's EnhancedAC3Renderer does.

Results on the test clip after the fixes: the moving objects' paths track the
core at +0.91..+0.99; each 5.1 speaker tracks the core at +0.88..+0.96 (LFE
+1.00); the per-window speaker-energy distribution matches with median
similarity 1.00; on 7.1.4, top-speaker energy follows object height at +0.999.
Levels sit about 3 dB under the core on every channel — Cavern's anti-clip
trim — with the centre about 1 dB lower still. Before the fixes the same check
fails outright (everything out of L; similarity 0.02). It also picked the
layout-aware cube warp in speaker_renderer.h over a fixed one (5.1 surrounds
+0.86 -> +0.95).

The clip exercises no mixed-update blocks, zones, size, snap or divergence, so
those paths remain spec-checked only.
