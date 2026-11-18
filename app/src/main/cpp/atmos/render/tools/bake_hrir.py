#!/usr/bin/env python3
"""Bakes a measured HRIR set into ../hrir_table.h, the Atmos binaural render's
built-in default: the Neumann KU100 of SADIE II (see ../NOTICE.md).

    pip install h5py numpy
    curl -O https://sofacoustics.org/data/database/sadie/D1_48K_24bit_256tap_FIR_SOFA.sofa
    python3 bake_hrir.py D1_48K_24bit_256tap_FIR_SOFA.sofa ../hrir_table.h

and then the equalizer that goes with it (bake_dfe.cpp). BUILD-TIME ONLY.

What the table must be (hrir_renderer.h, virtual_room.h): for each direction
of a uniform grid, each ear's response with its onset at tap 0, and beside it
that ear's delay in samples. The render blends neighbouring directions'
responses and delays apart and puts the delay back as a fractional one:
blending responses whose onsets differ combs them.

SADIE II's filters are close to linear phase: they ring before their peak as
much as after it, so there is no onset to find (a threshold on the old tool
put the two ears 14 samples apart for a sound dead ahead). So each measured
response is taken apart the way a head's can be:

  * its magnitude, kept exactly, made a minimum-phase filter (through the
    cepstrum): all of it after tap 0, nothing before, the least delay a
    filter of that magnitude can have;
  * the time between the ears, from the peak of the cross-correlation of the
    two ears below 1.5 kHz, found to a sixteenth of a sample. The ear the
    sound reaches second gets that delay, the other none. It is stored to
    the whole sample (10 microseconds off at most, under what is heard):
    the object render puts a delay back by linear interpolation, which dulls
    a delay that is not whole, and a grid point should not be dulled.

A grid direction that was not measured is the three nearest measured ones,
weighted by nearness: their magnitudes in dB and their delays, then made
minimum phase. The measurement has rings 10 to 15 degrees apart and the grid
one every 10, so most of the grid is within a few degrees of a measurement.

The head is made the same on both sides. A measured head never quite is: in
this set a sound dead ahead is 1.1 dB louder in the right ear, and a centre
that leans is the first thing heard in a mix. Each ear's magnitude is the
mean (in dB) of its own and of the other ear's for the mirrored direction,
and the delay likewise, so what is ahead is exactly between the ears. (The
MIT KEMAR set this replaces was one ear measured twice, mirrored.)

The grid was 10 degrees round by 20 up and down, stopping at +60, so the
room's top speakers and anything overhead were the +60 ring's. It is every 5
round and every 10 from -40 to straight overhead. Every set loaded at run
time is laid out on the same grid (sofa_loader.cpp).
"""
import sys
import h5py
import numpy as np

TAPS = 128
AZ_STEP, AZ_COUNT = 5, 72          # 0, 5, ... 355: wraps
EL_MIN, EL_STEP, EL_COUNT = -40, 10, 14   # -40 ... 90: clamps
RATE = 48000
N = 2048                           # transform size for the cepstrum
UP = 16                            # the delay is found to 1/UP of a sample
ITD_BAND_HZ = 1500.0
NEAREST = 3


def unit(az_deg, el_deg):
    """SOFA's own angles (azimuth counter-clockwise from ahead) as a unit vector."""
    az, el = np.radians(az_deg), np.radians(el_deg)
    return np.stack([np.cos(el) * np.cos(az), np.cos(el) * np.sin(az), np.sin(el)], axis=-1)


def interaural_delay(left, right):
    """How much later the right ear is than the left, in samples (negative: earlier)."""
    n = 2 * len(left)
    L, R = np.fft.rfft(left, n), np.fft.rfft(right, n)
    hz = np.fft.rfftfreq(n, 1.0 / RATE)
    keep = np.where(hz <= ITD_BAND_HZ, 1.0, 0.0) * np.where(hz >= 100.0, 1.0, 0.0)
    cross = R * np.conj(L) * keep
    # Zero-padded in frequency: the correlation at 1/UP of a sample.
    big = np.zeros(n * UP // 2 + 1, dtype=complex)
    big[: len(cross)] = cross
    corr = np.fft.irfft(big, n * UP)
    reach = int(1.2e-3 * RATE * UP)                 # no head is wider than 1.2 ms
    lags = np.concatenate([np.arange(0, reach + 1), np.arange(-reach, 0)])
    return lags[np.argmax(corr[lags])] / UP


def minimum_phase(mag_db):
    """The minimum-phase filter of a magnitude given in dB on N/2+1 bins."""
    log_mag = np.maximum(mag_db, mag_db.max() - 80.0) * (np.log(10.0) / 20.0)
    full = np.concatenate([log_mag, log_mag[-2:0:-1]])
    cep = np.fft.ifft(full).real
    fold = np.zeros(N)
    fold[0] = cep[0]
    fold[1 : N // 2] = 2.0 * cep[1 : N // 2]
    fold[N // 2] = cep[N // 2]
    return np.fft.ifft(np.exp(np.fft.fft(fold))).real


def main():
    src, out = sys.argv[1], sys.argv[2]
    with h5py.File(src, "r") as f:
        pos = f["SourcePosition"][:]
        ir = f["Data.IR"][:].astype(np.float64)
        rate = float(f["Data.SamplingRate"][0])
    if rate != RATE:
        sys.exit(f"{src} is at {rate} Hz; this bakes a {RATE} Hz set as it is")
    where = unit(pos[:, 0], pos[:, 1])
    mag_db = 20.0 * np.log10(np.maximum(np.abs(np.fft.rfft(ir, N, axis=-1)), 1e-9))   # [m][ear][bin]
    itd = np.array([interaural_delay(ir[m, 0], ir[m, 1]) for m in range(len(ir))])

    furthest = 0.0

    def measured(sofa_az, el):
        """The set at a direction: both ears' magnitude in dB, and the right ear's lateness."""
        nonlocal furthest
        angle = np.degrees(np.arccos(np.clip(where @ unit(sofa_az, el), -1.0, 1.0)))
        near = np.argsort(angle)[:NEAREST]
        furthest = max(furthest, angle[near[0]])
        if angle[near[0]] < 0.25:
            near, w = near[:1], np.ones(1)
        else:
            w = 1.0 / angle[near]
            w /= w.sum()
        return np.tensordot(w, mag_db[near], axes=1), float(w @ itd[near])

    data = np.zeros((EL_COUNT, AZ_COUNT, 2, TAPS))
    delay = np.zeros((EL_COUNT, AZ_COUNT, 2))
    worst_kept = 1.0
    for e in range(EL_COUNT):
        for a in range(AZ_COUNT):
            # The table's azimuth is clockwise from ahead (+ to the right); SOFA's the other way.
            az, el = (360.0 - a * AZ_STEP) % 360.0, EL_MIN + e * EL_STEP
            here, late = measured(az, el)
            there, late_there = measured((360.0 - az) % 360.0, el)   # the same direction, mirrored
            mags = 0.5 * (here + there[::-1])                        # its ears the other way round
            d = float(np.round(0.5 * (late - late_there)))
            delay[e, a, 0], delay[e, a, 1] = max(-d, 0.0), max(d, 0.0)
            for ear in range(2):
                h = minimum_phase(mags[ear])
                data[e, a, ear] = h[:TAPS]
                worst_kept = min(worst_kept, float(np.sum(h[:TAPS] ** 2) / np.sum(h ** 2)))

    with open(out, "w") as f:
        f.write(
            "// GENERATED by tools/bake_hrir.py — do not edit. The Neumann KU100 of\n"
            "// SADIE II (University of York, 2018, Apache-2.0;\n"
            "// D1_48K_24bit_256tap_FIR_SOFA.sofa) at 48 kHz: each response's own\n"
            "// magnitude as a minimum-phase filter, onset at tap 0, and the time\n"
            "// between the ears beside it. See NOTICE.md.\n"
            "#ifndef TF_ATMOS_RENDER_HRIR_TABLE_H\n#define TF_ATMOS_RENDER_HRIR_TABLE_H\n\n"
            "namespace tf { namespace atmos { namespace render {\n\n"
            f"constexpr int   kHrirTaps    = {TAPS};\n"
            f"constexpr int   kHrirAzCount = {AZ_COUNT};   // uniform, wraps\n"
            f"constexpr float kHrirAzStep  = {AZ_STEP}.0f;\n"
            f"constexpr int   kHrirElCount = {EL_COUNT};   // uniform, clamps\n"
            f"constexpr float kHrirElMin   = {EL_MIN}.0f;\n"
            f"constexpr float kHrirElStep  = {EL_STEP}.0f;\n\n"
            "// Onset-aligned response, [el][az][ear(0=L,1=R)][tap].\n"
            f"constexpr float kHrirData[{EL_COUNT}][{AZ_COUNT}][2][{TAPS}] = {{\n"
        )
        for e in range(EL_COUNT):
            f.write(f"{{ // el={EL_MIN + e * EL_STEP}\n")
            for a in range(AZ_COUNT):
                f.write("{" + ",".join("{" + ",".join(f"{v:.7e}f" for v in data[e, a, ear]) + "}" for ear in range(2)) + "},\n")
            f.write("},\n")
        f.write("};\n\n// Each ear's delay in samples (the nearer ear's is 0), [el][az][ear].\n"
                f"constexpr float kHrirDelay[{EL_COUNT}][{AZ_COUNT}][2] = {{\n")
        for e in range(EL_COUNT):
            f.write("{" + ",".join(f"{{{delay[e, a, 0]:.1f}f,{delay[e, a, 1]:.1f}f}}" for a in range(AZ_COUNT)) + "},\n")
        f.write("};\n\n}}}  // namespace tf::atmos::render\n\n#endif  // TF_ATMOS_RENDER_HRIR_TABLE_H\n")
    print(f"wrote {out}: {EL_COUNT} x {AZ_COUNT} directions, {TAPS} taps; "
          f"least energy kept {100 * worst_kept:.2f}%, largest delay {delay.max():.2f} samples, "
          f"furthest grid point from a measurement {furthest:.1f} degrees")
    front, right, left = delay[4, 0], delay[4, 18], delay[4, 54]
    print(f"   ahead {front}, 90 right {right}, 90 left {left}")


if __name__ == "__main__":
    main()
