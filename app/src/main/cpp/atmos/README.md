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
