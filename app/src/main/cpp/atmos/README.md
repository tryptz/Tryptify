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

- **OAMD framing + EMDF container** (plan A3) — `cavern/object_metadata.h`
  (`OAElementMD` + `ObjectAudioMetadata` program/bed assignment, element/ramp
  timing) and `cavern/extensible_metadata_decoder.h` (`ExtensibleMetadataDecoder`:
  scans the EMDF syncword and dispatches OAMD id 11 / JOC id 14). Decode is
  reproduced exactly; the `UpdateSources()` render integration is not ported —
  decoded state is exposed via accessors. Host-tested (49 checks): the EMDF
  VariableBits helpers, ReadBits, and program assignment.

With this, the full **EMDF → OAMD + JOC → QMF upmix → object PCM + positions**
chain is ported (host-verified, CI-green on NDK).

### JNI surface

`atmos_jni.cpp` exposes the decode path to Kotlin (`AtmosEngine.kt`) via
`libmonochrome_atmos.so` — `nativeSelfCheck()` and `nativeDecodeEmdfObjectCount()`
(walk an EMDF buffer, return the JOC object count). It is **off the player's
critical path**: nothing loads the library at runtime yet, so it cannot affect
playback. It is the boundary the render/output wiring grows into.

Still to build: the **E-AC-3 core audio decode** (the bed PCM that feeds the
applier — a large multi-file Cavern codec **or** an FFmpeg `eac3` binding, plan
Option 1), the container demux (`MediaExtractor` → raw EC-3 stream), the **HRTF
binauralizer** back-end, and the **Media3 `AtmosAudioProcessor`** integration
into `monochrome_dsp`.
- **HRTF binauralizer** back-end (plan A7) — will reuse libmysofa + a NEON
  convolver and the app's existing AutoEQ/HRTF infra.
- **JNI surface + Media3 `AtmosAudioProcessor`** wiring into `monochrome_dsp`.

See the phased acceptance criteria in the renderer plan (§7). This commit lands
Phase-0-adjacent foundations: the pieces that are provable in isolation.
