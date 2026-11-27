# Stale proofs

Cells whose proof log no longer replays: the app's frames changed after the hardware run. Each is awaiting re-proof on hardware (plan v2, step 2);
a new PASS log then replaces the old one and the line goes. `ProvenLogReplayTest` fails on a differing cell that is not listed here, and on a
listed cell that matches again. Line format: `- <dac>/<function>: <commit that changed the frames> - <what>`.

- allegro-mini/eq-full: a937516 - frequency register = intended f x 24000/48000 (the old log is the pre-rescale encoding); proven on a phone (Pixel 9 Pro, run 2026-10-25-contour16-r7-phone-nothing), Mac log at the next desk
- dawn-pro-2/eq-ls: scale applied after the measurement; acoustic law from run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k (the shelf register is now intended Q x sqrt2, the log holds the unscaled register)
- dawn-pro-2/eq-hs: scale applied after the measurement; acoustic law from run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k (the shelf register is now intended Q x sqrt2, the log holds the unscaled register)
- max/eq-shelf-q2: 1.8.0 shelf form (peq.shelfForm) - AUTO preamp of two cut shelves (LS 300 Hz -8 dB Q 2, HS 5000 Hz -8 dB Q 2) is now computed in the Q form the Max plays: -4 dB (preamp byte fc), the log holds -1 dB (ff) from the slope form; band frames unchanged, only the AUTO preamp byte moves; the acoustic PASS of run 2026-11-10-crinear-protocol-max-16-mac-eqtest-shelf stays valid for its registers, re-prove with the new preamp on the Mac
