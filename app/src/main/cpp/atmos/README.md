# `cpp/atmos` — clean-room Dolby Atmos renderer foundation

This directory holds the first, **fully self-contained and verifiable** layer of
the E-AC-3 JOC → object → binaural/bed renderer described in the renderer plan.
Everything here is clean-room from the public ETSI specifications
(TS 102 366 / A/52 for E-AC-3 + EMDF, TS 103 420 for OAMD + JOC) and depends on
nothing but the C++17 standard library and the vendored signalsmith-linear FFT
(`fft.h`, from `third_party/signalsmith-stretch`), so it builds under the NDK
for every shipped ABI and is unit-tested on a host toolchain.

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
  correlation 1.00000 (577-sample delay). The modulation runs through a 64-point
  FFT instead of Cavern's dense rotation matrices; `cavern_qmf_test` holds it
  to the dense transform (to float rounding against exact phases, within
  5e-5 of peak against Cavern's float tables).
- **JOC decoder + upmix applier** (plan A6) — `cavern/joint_object_coding.h`
  (frame decode, Huffman, dequant, per-timeslot matrix interpolation) and
  `cavern/joint_object_coding_applier.h` (QMF-domain channel→object mix +
  inverse QMF). `cavern/joc_tables.h` holds the Huffman/band tables. Cavern's
  ThreadPool fan-out is sequential here; exceptions become a `valid()` flag.
  The per-timeslot matrices are worked out row by row as the applier needs
  them rather than tabled ahead of the frame (`cavern_joc_test` checks every
  row against the table: bit for bit on the host and arm64).
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
  (re-anchoring on discontinuities), not by arrival order.
- **Every frame carries JOC and OAMD.** The EMDF container sits in an audio
  block's skip field, so it starts at any bit of the frame. It is looked for
  at every bit (`cavern/extensible_metadata_decoder.h`), and a candidate is
  taken only when its payloads and protection fill its stated length to the
  byte, because the syncword turns up by chance in audio data about once in
  three frames. Looked for at whole bytes, as it was until 1.9.52, it was
  found in a quarter of the frames (2% of Dolby's channel-check tones), which
  this file used to describe as payloads being sparse.
- **The last update holds while the stream is Atmos.** For a frame whose
  container cannot be read, whether the stream is still Atmos comes from the
  syncframe's addbsi `flag_ec3_extension_type_a` (`cavern/enhanced_ac3.h`): a
  frame without it (a gapless change into plain E-AC-3) ends the hold at
  once, a seek drops it until the next payload, and only frames with no
  readable header fall back to a 32-frame expiry.
- **Both output paths are latency-aligned.** The object render's bed passes
  through QMF analysis+synthesis — a measured 577-sample delay (pinned by
  `cavern_qmf_test`). The decoded bed, which plays while there are no
  objects, runs every frame behind the same delay on the same speakers, and a
  switch between the two is an equal-power crossfade, so a seam is neither a
  timbre cut nor a time jump.
- **Motion is ramped.** An object's speaker gains move to each update's
  target over the update's own ramp (`SpeakerRenderer::render`), object
  positions use the OAMD frame's last info block, and per-object OAMD gains
  are applied with per-frame ramps.
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
`virtual_room_test`, `atmos_room_pipeline_test`, `object_engine_test`. The
tests share `tests/check.h` (CHECK and CHECK_NEAR, which refuse a NaN under
the phone's flags), `tests/bit_writer.h` and `tests/joc_frame.h`.

## Loudspeaker render (5.1 .. 9.1.6)

`speaker_renderer.h` renders the reconstructed objects to a physical layout —
5.1, 7.1, 5.1.2, 5.1.4, 7.1.2, 7.1.4, 9.1.4, 9.1.6 — for HDMI receivers and
multichannel USB interfaces (`AtmosPipeline::process_frame_speakers`, JNI
`nativeSetOutputLayout` / `nativeProcessFrameSpeakers`). Output is interleaved
in Android channel-mask order so the Kotlin side hands it to the AudioTrack
unchanged (`AtmosAudioTrackProvider` sets the exact mask; 9.1.x travels as a
24-channel frame whose extra positions are silent, because Media3 1.5 only
accepts 1-8, 10, 12 and 24 channels).

- **Panning**: a sin/cos balance along each axis of the room between the
  speakers either side of the object (`BalancePanner`): rows of speakers by
  y, speakers in a row by x, layers by z. This is the law the stream's own 5.1
  follows. It replaced a cube-to-angle warp and VBAP (1.9.52), which put an
  overhead object evenly round the room and kept pans against L/R. A layout
  with nothing overhead plays an object at its place on the floor.
- **Timing**: an update takes hold `sample_offset + 32 x block_offset_factor`
  samples into its frame, later by the core decoder's block (256) and the QMF
  round trip (577), and is reached over its `ramp_duration`. The 256 was
  swept against the core: the best of 0..512.
- **Object properties (TS 103 420 clause 5.2)**: size (box sampling, power
  sum), divergence (power-preserving centre + two sides, BS.2127-style), zone
  constraints (the panner over the permitted speakers only, speaker ->
  zone map of Table A.7), channel lock (nearest speaker). Bed objects play from
  their own channel when the layout has it; the LFE object goes to the LFE.
- **Fallback**: frames without JOC play the decoded bed on the layout, delayed
  by the same 577-sample QMF latency and crossfaded, exactly like the stereo path.

Host tests: `speaker_renderer_test`, `atmos_speaker_pipeline_test`.

## Headphone render: the 15.1.12 virtual room

With the HRTF on, `AtmosPipeline::process_frame` renders headphones as a
room of speakers (`set_room_amount` says how much of the room is heard):

1. Objects and beds are panned onto `OutputLayout::k15_1_12` by the same
   `SpeakerRenderer` as real speakers: fifteen at ear level (0, 15, 30, 60, 90,
   110, 135 and 160 degrees each side), eight at 30 degrees up (30, 70, 110,
   150) and four at 60 (45, 135), and an LFE. Size, divergence, zones and
   channel lock therefore apply on headphones, which the one-HRIR-an-object
   render ignores. The bed fallback and the crossfade into objects happen on
   the speaker feeds, so both go through one binaural stage.
2. `render/virtual_room.h` turns the 28 feeds into two ears: a fixed filter
   pair a speaker with its interaural delay inside (windowed-sinc), summed as
   spectra (27 forward FFTs, 2 inverse, a block of 256; a silent speaker is
   skipped), the diffuse-field equalizer (`hrir_dfe.h`; a loaded SOFA set gets
   one designed from itself), the LFE low-passed at 120 Hz at +10 dB to both
   ears, early reflections sent out of other speakers and a shared FDN tail
   (the Room amount; 0 is none), a 3 dB trim and a look-ahead limiter at
   -0.5 dBFS. 39 samples of delay.

`virtual_room_test` holds the convolution to a tap-by-tap one (-124 dB) and
checks the layout, LFE, room, limiter, reset and a set swapped in while it
renders; `atmos_room_pipeline_test` the pipeline's path through it. Nothing
here has been judged by ear: the room's reflections and tail are starting
values.

The render before it (each object through the HRIR of its own direction, no
equalizer, no room) is gone from the pipeline: nothing called it once the
room was the headphone render. `BinauralRenderer` (`render/hrir_renderer.h`)
stays for the mixer's spatial map (`render/channel_placer.h`).

## The virtual room's low end (fixed 1.9.52)

The baked KEMAR set was measured with a small loudspeaker and rolls off
below 300 Hz: through its equalizer, every direction was 3 dB down at 120 Hz
and 5 dB at 60 Hz, more than the 512-tap equalizer can put back. With bass
management on, the crossover hid it below 80 Hz and left a hole from there
to 250 Hz; with it off the whole low end was thin, and the render sounded as
if part of the mix was not playing. `VirtualRoom::design_bass_shelf` measures
that shortfall for the set in use (40-70 Hz against 300-630 Hz, all
directions, through the equalizer) and a low shelf at 120 Hz after the
equalizer puts it back: 5.4 dB for the baked set, nothing for a set whose
low end is level, never more than 9 dB. Level to within 1.5 dB from 50 Hz up
for the speakers ahead (`test_low_end`). On a real TIDAL stream the render's
80-630 Hz is now within 1 dB of the stream's own stereo fold.

## Measured rooms, and rooms worked out from their size (1.9.52)

The virtual room's reflections and tail can be replaced by a room
(`VirtualRoom::set_room_response`): a mono send of all the speaker feeds,
high-passed at 60 Hz, through the room's two-ear response in a uniformly
partitioned convolution (`render/measured_room.h`, `RoomConvolver`, 256-sample
blocks, up to 2 s). The convolver is one block late and the prepared response
starts one block after the measurement's direct sound, so reflections arrive
when they did in the room; the first 5 ms of them go with the direct sound,
which the render plays itself through its own ear filters.

`prepare_room_response` makes any two-channel measurement fit: 48 kHz, the
direct sound cut, the tail trimmed at -60 dB and at one and a half times the
room's own decay (a measurement's noise floor never falls), one energy for
every room (`kRoomResponseEnergy`, set so that Room at 100% adds what the
built-in room adds: -7.1 dB against the direct sound for both in
`measured_room_test`). `render/room_sofa.cpp` reads the room databases'
SOFA conventions through libmysofa's raw loader (its HRTF path refuses
them) and a shoebox's corners, listener and loudspeakers. Tried on files
from dEchorate, Oldenburg, TH Köln, TU Berlin, TU Ilmenau, BBC Maida Vale and
PAN-AR; a file whose HDF5 header libmysofa cannot follow (two PAN-AR files)
is refused, as is an anechoic one.

`render/box_room.h` works a box's response out by the image-source model
(Allen and Berkley), followed for as long as 1.5 million images allow and
then by noise falling at the room's Eyring rate. The lattice of images is
what gives a room its modes; `measured_room_test` finds the depth mode of a
5 x 6 x 2.7 m room 7 dB over a frequency half way to its second. Nothing in
this has been checked by ear here.

`PanEnvelope` (`speaker_renderer.h`, `AtmosPipeline::set_pan_envelope`) is
the panning envelope: with it on, an object's place across and along the
room is drawn in about the middle by `extent(z) = 1 - (1 - ceiling) * z^curve`,
so the room it may use is all of the floor at ear level and `ceiling_width`
by `ceiling_depth` of it at the ceiling. It is applied in
`SpeakerRenderer::object_gains` after warp and before snap, size and
divergence, for every layout; bed channels, ISF objects and the LFE are not
moved, and the object meter reports the place inside the envelope. Off by
default: `speaker_renderer_test` holds that on, an object plays exactly as
one mixed at its enveloped place would with it off, and that at ear level
nothing changes. On 7.1.4 with the ceiling at 20% of the width, 21% of a
far-left ceiling object's power is in the right top speakers, where before
there was none.

The idea of a trapezoidal room and panning domain (walls that lean in, so
what is high is not wide), and the name 15.1.12, are Poorna Srikar
Maremanda's (iammps.com, "Beyond the Sphere", CC BY 4.0). The geometry and
code here are this project's own; that site publishes no coordinates. Its
author asks to be contacted before commercial software implementing the
approach is released, and states patent and design applications.

## The stage ahead sounded hollow (fixed 1.9.52)

Sound panned between the front pair reaches each ear from both speakers, a
fraction of a millisecond apart, and partly cancels: measured through the
room with noise, 6 dB down at 1.25-2.5 kHz and 7 dB at 5-10 kHz against its
own low mids, with 2.5-5 kHz untouched between them; KEMAR's ear adds a
notch near 8 kHz for the centre speaker. A real room's walls fill those dips
in. The owner heard it as hollow highs. `VirtualRoom::design_tone_correction` (`front_stage_gain`) works
out a correction for that stage from the set in use (the centre speaker,
between the pair, hard to one side; each case's tone through the equalizer
against its own 250-700 Hz, half-octave smoothed; 0.7 of the inverse, from
500 Hz up, between -3 and +6 dB) and folds it into the equalizer. For the
baked set: +3 dB at 1.25-1.6 kHz, -1.5 dB at 4 kHz, +6 dB at 8 kHz. Between
the pair, the bands from 630 Hz to 10 kHz are now within 4 dB of each other
where they spanned 7. It is a tone for the front stage: a sound from the
side or behind gets the same lift and is that much brighter there. Not
checked by ear here.

## Nothing above 15 kHz (fixed 1.9.52)

The baked KEMAR set (a small loudspeaker, measured at 44.1 kHz) falls away
at the top: over every direction and through its equalizer it is 5 dB down
at 15 kHz, 9 at 16, 14 at 18 and 18 at 20, and the equalizer's own limit
stops it correcting there. White noise from any speaker of the room came out
12 to 19 dB down from 17 to 20 kHz; the owner saw it on a spectrum as
everything cut after 15 kHz. The stream itself is not cut there.
`top_end_gain` measures how far the set in use is under its own 8-13 kHz at
each kHz from 14 to 20 and the tone correction adds that back, up to 12 dB,
held to 20.5 kHz and gone by 22 (a 44.1 kHz measurement has nothing above).
After it the same noise is within 5 dB of the 4-8 kHz level up to 19 kHz. A
set whose top is level gets nothing.

The three corrections (`design_dfe`, `design_tone_correction`,
`design_bass_shelf`) are one section of `virtual_room.h`, sharing
`diffuse_level_db`, `smooth_power` and `min_phase_fir`; the tone correction
is folded into the equalizer's 512 taps when a set is published, so the
render runs no extra stage for it.

## Positions were NaN on phones (fixed 1.9.52)

The JNI library is built `-O3 -ffast-math`. `ObjectState::distance` was NaN
for "inside the room" and tested with `std::isnan`, which that flag folds
away: on a phone every dynamic object took the distance branch, and its room
position came out NaN. The panner then put every object on one speaker, the
map drew none, and nothing was ever seen to move, while all of this
directory's host tests passed. The distance is now a plain number
(`ObjectInfoBlock::kNoDistance`, `kDistanceAtInfinity`), no NaN or infinity
is used anywhere here, every test is also built with the phone's flags
(`<name>_fastmath`), and the tests' comparisons refuse a NaN through
`dsp/util/finite.h`. On a real TIDAL stream built with those flags: 0 moving
objects and NaN positions before, 14 moving after, as on the host.

## JOC decode fixes

Measured against the stream's own 5.1 with the reference check below, or on
synthetic payloads (`cavern_joc_test`):

- Downmix configurations 3 and 4 carry the surround pair turned 90 degrees.
  The applier turns Ls/Rs back (every subband times -j) before mixing:
  waveform correlation of the rendered L/R/C with the core 0.60/0.45/0.65 ->
  0.92/0.92/0.95.
- A steep-slope object's decoded matrix is expanded from parameter bands to
  subbands (it was handed out band-indexed: silence above the band count),
  and so is the first half of a two-point ramp; two-point steep uses both
  offsets.
- Sparse objects are decoded (they were silence): the channel index and the
  coefficient each run as one chain along the bands.
- Coefficients are walked on their ring in whole numbers, with steps of
  820/4096 and 820/8192; in floats the lowest level could come out as the
  highest.
- `joc_seq_count` 0 (a stream's first frame, a splice) starts from a zero
  matrix; after a seek the first payload starts on its own, and the filter
  banks are emptied.
- A 7.1 core's pairs are fed to JOC in its order (the decoder's is rears
  first), and a 7.1 bed's sides and rears are no longer exchanged.
- Cavern's 3 dB off every object's gain is gone.

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

- **Trims beyond 5.1's surround trim.** The trim element is read
  (`OAElementMD::trim_element`): `warp_mode` 1 doubles Y on 5.x layouts, and
  trim configuration 1 (5.1; the bundled clip disables exactly that one and
  matches its core untrimmed) takes its surround trim off what objects send
  to the surrounds: the transmitted value, or -3 dB on default_trim. The
  -3 dB is measured, not specified: the specification gives the choice and
  the custom values, not the default's, nor how a renderer applies any of
  them. On a second real stream (warp 1, default trims) C went from +1.9 dB
  / 0.93 to +0.4 dB / 0.994 against the core and Ls/Rs from +3.3 dB to
  +0.3. Centre and height trims, the two balances, and the eight other
  configurations are read past: nothing to measure them against.
- Above 5.1 there is no Dolby render to compare with: the panner on height
  layouts is the 5.1 law carried up, not a measured one. Overhead positions
  on 5.1 are within 0.04 of the core, not 0.01.
- The one-HRIR-an-object headphone render (virtual room off) still ramps
  each update across the frame it arrived in.
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

Results on the test clip (1.9.52): the moving objects' paths track the core at
+0.985..+0.995; every 5.1 speaker tracks the core at +0.999 or better and
within 0.1 dB; the per-window speaker-energy distribution matches with median
and 10th-percentile similarity 1.000. The check fails under 0.98 or past
0.5 dB. (With the hull panner the speakers were at +0.91..+0.97 and up to
0.8 dB off.) Before the fixes the same check
fails outright (everything out of L; similarity 0.02). It also picked the
layout-aware cube warp in speaker_renderer.h over a fixed one (5.1 surrounds
+0.86 -> +0.95).

The clip exercises no mixed-update blocks, zones, size, snap or divergence, so
those paths remain spec-checked only.

## Channel check (7.1.4 / 9.1.6)

The 5.1 core can't check the height or the 7.1 surrounds. Dolby's 7.1.4 test
tones can: they play each 7.1.4 speaker in turn as one object placed exactly at
that speaker, so every frame has a known right answer.
`tools/reference_check/channel_check.sh dolby-test-tones_7_1_4.mp4` renders the
clip to 7.1.4 and 9.1.6 and fails unless, for every speaker, at least 98% of
the frames put at least 90% of their energy on it. The clip (~190 MB, not
committed) is mirrored with other Atmos demo files at
https://www.peterpee.com/demo.

It found the hold expiring mid-tone. The byte-aligned search then in use found
JOC in 2% of the tones' frames, with gaps up to ~20 s, and the old fixed
32-frame hold dropped to the 5.1 bed for most of each gap: top-front tones played from L, rear tones from between
the side and rear speakers. Frames with 90% on target: 69% before the
header-driven hold, 100% after, with every speaker, heights included, at 91%
or more of its energy on target. Neither the TIDAL reference check nor the
Santeria demo (DD+JOC 640 kb/s, from the same mirror) moved.
