# Atmos HRTF render — attribution & licensing

The binaural renderer in this directory convolves each positioned source with a
**measured head-related impulse response (HRIR)**, giving the pinna spectral
cues (front/back and elevation disambiguation) that the previous procedural
spherical-head model could not reproduce.

## HRIR dataset — MIT KEMAR
`hrir_table.h` is **generated**, not hand-written. It is baked from the
**MIT KEMAR** HRTF measurements (Bill Gardner & Keith Martin, MIT Media Lab),
a long-standing publicly available dataset distributed for research and
application use. The SOFA-format copy shipped with libmysofa
(`MIT_KEMAR_normal_pinna.sofa`) is the source.

Cite: W. G. Gardner and K. D. Martin, *"HRTF measurements of a KEMAR
dummy-head microphone,"* MIT Media Lab Perceptual Computing Technical Report
#280, 1994.

## How the table is produced
`tools/bake_hrir.c` links **libmysofa** (Christian Hoene et al., BSD-3-Clause,
<https://github.com/hoene/libmysofa>) to:

1. load the SOFA file and resample the HRIRs to 48 kHz (the native DD+/Atmos
   bed rate), with libmysofa's loudness normalization;
2. sample a uniform grid — azimuth every 10° (36 points, wrapping), elevation
   every 20° from −40°..+60° (6 points, clamping);
3. **onset-align** each ear's response (shift its onset to tap 0) and store the
   onset delay separately as the ITD — interpolating raw HRIRs whose onsets
   differ comb-filters the blend (measured −17.8 dB at 10 kHz between adjacent
   nodes; onset alignment cuts that to −2.5 dB), which the renderer restores as
   a fractional delay;
4. keep 128 aligned taps of the response;
5. emit `hrir_table.h` (`kHrirData` + `kHrirDelay`) in this repo's azimuth
   convention (0 = front, +right).

The baked table is the **default** HRTF and is embedded with no runtime
dependency. libmysofa (BSD-3-Clause) is **also vendored at
`third_party/libmysofa`** and linked into the Atmos JNI lib so the user can load
their own `.sofa` HRTF at runtime (`sofa_loader.cpp` → `nativeLoadSofa`), which
reproduces the same onset-aligned grid in memory. A device that never loads a
SOFA pulls in that code but never runs it, and the header-only renderer keeps no
libmysofa dependency of its own.

## Renderer
`hrir_renderer.h` interpolates the HRIR pair bilinearly across the grid for each
source's azimuth/elevation, then convolves the frame per ear with persistent
cross-frame input history. The interaural time difference is carried inside the
HRIR itself (as the inter-ear onset difference of the measured response), so no
separate delay model is used.
