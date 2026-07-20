# Atmos HRTF render — attribution & licensing

The binaural renderer in this directory uses a **procedural (synthetic)
spherical-head HRTF model**, not a measured HRIR dataset. It contains **no
third-party measurement data**, so it carries no dataset attribution or
redistribution obligation — it is clean-room, unlike the `cavern/` subtree.

## Model
`structural_hrtf.h` models the two dominant interaural localization cues from
first principles:

- **ITD (interaural time difference)** — the Woodworth spherical-head formula
  `ITD(θ) = (a/c)·(sin θ + θ)`, applied as a fractional delay on the far ear
  (`a` = head radius ≈ 8.75 cm, `c` = 343 m/s).
- **ILD / head shadow** — a per-ear level term plus a one-pole low-pass whose
  strength grows as the ear turns away from the source (a Brown-Duda-style
  structural head shadow).

These are standard textbook acoustics models, implemented directly (no code or
data copied from any HRTF toolkit or dataset).

## Not modeled (future fidelity upgrades)
Pinna spectral notches (elevation cues) and torso reflections are not modeled;
those would require measured HRIRs (e.g. a SOFA dataset) + convolution, which is
a deliberate later upgrade path. The current model gives stable azimuth
placement and front/overhead level cues with real-time cost.
