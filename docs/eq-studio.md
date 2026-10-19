# EQ Studio

`ui/eq/EqStudioScreen.kt` and its siblings (`EqStudioGraph.kt`,
`EqStudioControls.kt`, `EqStudioMath.kt`). A graph-first equalizer screen
modelled on [peqdb.com/studio](https://peqdb.com/studio/), built natively on the
app's own EQ: the same `EqViewModel`, filter model, AutoEQ engine, headphone
database and targets as the classic Equalizer screen. It is opened from the top
of that screen, or from Settings search.

## Rules that are not obvious from the code

**It shares the classic screen's view model.** The nav host hands it
`hiltViewModel(<Equalizer back stack entry>)` when that entry exists. Two view
models would each hold a copy of the bands; going back would show the old copy,
and the next edit there would write it over everything done in the Studio.
Opened from Settings search there is no Equalizer entry and it gets its own,
which is safe because nothing else on the stack holds bands.

**A filter's colour is keyed on its id, never its position.** Sorting or
deleting another filter must not repaint the one being tuned. `addBand` takes
`max(id) + 1`, so ids are never reused while a list exists.

**The result curve is drawn without the preamp.** The preamp is a level change,
not a tonal one. With it, the result sat a few dB under the target everywhere and
a perfect fit read as a bad one.

**The graph is fixed and only the panel scrolls** (phone layout). The point of the
screen is watching the curve move while tuning. peqdb's own phone layout lets its
panel cover the graph, which is the thing this was built to avoid.

**Every pill is a sibling of the page's haze source**, following
`docs/ui-invariants.md`: the screen draws its own backdrop box with `hazeSource`
and provides it as `LocalGlassBackdrop`, exactly as Discover does. Nothing glass
goes in a dialog; the two dialogs (exact value, name) are plain Material 3.

**Scrub pills report the total drag distance, not deltas.** The caller recomputes
from the value at drag start, so clamping at an end of a range cannot make the
value drift.

## Touch

| Gesture | Where | Does |
|---|---|---|
| Drag | a handle on the graph | moves that filter (frequency across, gain up/down) |
| Pinch (two fingers) | anywhere on the graph | changes the selected filter's Q; apart = wider = lower Q |
| Touch and hold | empty graph | crosshair readout of measurement, target, result and the difference |
| Swipe sideways | a scrub pill | scrubs the value, with a haptic tick per coarse step |
| Tap | a scrub pill | types an exact value |

Scales: frequency 60 dp per octave (tick every sixth of an octave), gain 16 dp per
dB (tick per dB), Q 80 dp per octave of Q (tick per quarter octave).

## Compared with peqdb's studio

What Tryptify does that peqdb does not:

- **The EQ plays.** peqdb designs filters to export; here the same filters run in
  the app's DSP, with automatic preamp against the real headroom budget.
- Separate **left and right** corrections, with per-ear measurements from
  squig.link sources.
- **Per-output presets** that switch with the headphones, **system-wide AutoEQ**,
  and the **Nothing Ear** QR export.
- A **live spectrum** of what is playing, tone controls, saved presets.
- Measurements from AutoEq and many squig.link rigs, plus the listener's own
  uploads, and it works offline once loaded.

What peqdb does that Tryptify does not yet:

- **Overlay several headphones** and average them.
- **Find the closest-matching headphone** to a curve.
- An **equation editor** for building targets (tilts, blends).
- **Bypass all** as an instant A/B, and shareable links and screenshots.
- A user-adjustable dB scale and normalisation frequency.

What the Studio took from peqdb: the graph-first layout, a colour per filter
shared by its handle, curve and chip, every target one tap away under the graph,
and add/sort/bypass/export next to the filters.
