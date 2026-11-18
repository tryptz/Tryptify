# Atmos HRTF render — attribution & licensing

The binaural renderer in this directory convolves each positioned source with a
**measured head-related impulse response (HRIR)**, giving the pinna spectral
cues (front/back and elevation disambiguation) that the previous procedural
spherical-head model could not reproduce.

## HRIR dataset — Neumann KU100, SADIE II
`hrir_table.h` is **generated**, not hand-written. It is baked from subject
**D1** of the **SADIE II** database: the Neumann KU100 dummy head, measured
at the AudioLab, University of York, at 48 kHz on 8,802 directions
(`D1_48K_24bit_256tap_FIR_SOFA.sofa`, from
<https://sofacoustics.org/data/database/sadie/>).

Copyright 2018, University of York. Licensed under the Apache License,
Version 2.0 (<http://www.apache.org/licenses/LICENSE-2.0>). The table is a
modified form of that data: see below for what was changed.

Cite: C. Armstrong, L. Thresh, D. Murphy and G. Kearney, *"A Perceptual
Evaluation of Individual and Non-Individual HRTFs: A Case Study of the
SADIE II Database,"* Applied Sciences 8(11), 2029, 2018
(<https://doi.org/10.3390/app8112029>).

It replaced the MIT KEMAR set (Gardner and Martin, 1994) on 2026-11-18, when
the owner asked for the best default there is: the KU100 is the head most
binaural renderers are judged against, and this measurement is at the
render's own rate, reaches the lowest and the highest octave, and is licensed
for shipping.

## How the table is produced
`tools/bake_hrir.py` (h5py, numpy) reads the SOFA file and, for each
direction of a uniform grid (azimuth every 5°, elevation every 10° from −40°
to +90°):

1. takes the three nearest measured directions, weighted by nearness (the
   furthest grid point is 5° from a measurement);
2. keeps each ear's **magnitude** and makes it a **minimum-phase** filter of
   128 taps, onset at tap 0. The set's own filters are close to linear phase
   and ring before their peak, so they have no onset to align, and the
   render blends neighbouring responses, which combs unless they start
   together;
3. stores the **time between the ears** beside it, from the cross-correlation
   of the two ears below 1.5 kHz, to the whole sample;
4. makes the head **the same on both sides** (each ear averaged with the
   other ear's mirrored direction): as measured, a sound dead ahead is
   1.1 dB louder in the right ear;
5. emits `hrir_table.h` (`kHrirData` + `kHrirDelay`) in this repo's azimuth
   convention (0 = front, + right).

`tools/bake_dfe.cpp` then makes `hrir_dfe.h`, the table's diffuse-field
equalizer (next to nothing for this set, which its makers equalized).

The baked table is the **default** HRTF and is embedded with no runtime
dependency. libmysofa (BSD-3-Clause) is **also vendored at
`third_party/libmysofa`** and linked into the Atmos JNI lib so the user can load
their own `.sofa` HRTF at runtime (`sofa_loader.cpp` → `nativePrepareSofa`), which
reproduces the same onset-aligned grid in memory. A device that never loads a
SOFA pulls in that code but never runs it, and the header-only renderer keeps no
libmysofa dependency of its own.

## Renderer
`hrir_renderer.h` interpolates the HRIR pair bilinearly across the grid for each
source's azimuth/elevation, then convolves the frame per ear with persistent
cross-frame input history. The interaural time difference is carried inside the
HRIR itself (as the inter-ear onset difference of the measured response), so no
separate delay model is used.
