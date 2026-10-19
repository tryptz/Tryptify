#!/usr/bin/env python3
"""Scores a speaker render of Dolby's 7.1.4 channel-check tones.

  channel_check.py layout_id frames.csv objects.csv

Each tone is one OAMD object placed at a corner or edge of the room cube that
names a 7.1.4 speaker (the LFE tone is the LFE object). The target of every
frame is the loudest tone's speaker, taken from the decoded OAMD and held
between metadata updates, the way the stream defines it. Checks, and exits
non-zero when one fails:
  1. all twelve 7.1.4 speakers are exercised;
  2. per speaker, at least 98% of the frames put at least 90% of their energy
     on the target speaker.
A render that falls back to the 5.1 bed mid-tone fails (2): the bed carries a
top-front tone in L and a rear tone between the side and rear speakers.
"""
import csv, sys

layout, frames_path, objects_path = int(sys.argv[1]), sys.argv[2], sys.argv[3]
NAMES = {6: "L R C LFE Lrs Rrs Lss Rss Ltf Rtf Ltr Rtr",
         8: "L R C LFE Lrs Rrs Lss Rss Ltf Rtf Ltr Rtr Lts Rts Lw Rw"}[layout].split()
# OAMD room cube (x left->right, y front->back, z floor->ceiling) -> speaker.
TARGETS = {(0, 0, 0): "L", (1, 0, 0): "R", (0.5, 0, 0): "C",
           (0, 0.5, 0): "Lss", (1, 0.5, 0): "Rss", (0, 1, 0): "Lrs", (1, 1, 0): "Rrs",
           (0, 0, 1): "Ltf", (1, 0, 1): "Rtf", (0, 1, 1): "Ltr", (1, 1, 1): "Rtr"}
MIN_ON_TARGET, MIN_FRAMES = 0.90, 0.98

loudest = {}  # frame -> (speaker, rms) of its loudest tone
for r in csv.DictReader(open(objects_path)):
    rms = float(r["rms"])
    if rms < 1e-3:
        continue
    if r["lfe"] == "1":
        target = "LFE"
    else:
        target = TARGETS.get(tuple(round(float(r[k]), 2) for k in "xyz"))
    f = int(r["frame"])
    if target and (f not in loudest or rms > loudest[f][1]):
        loudest[f] = (target, rms)

rows = list(csv.DictReader(open(frames_path)))
hits, totals, target = {}, {}, None
for f, r in enumerate(rows):
    if f in loudest:
        target = loudest[f][0]
    energy = [float(r[f"ch{c}"]) ** 2 for c in range(len(NAMES))]
    total = sum(energy)
    if target is None or total < 1e-6:  # before the first tone, or a pause
        continue
    totals[target] = totals.get(target, 0) + 1
    if energy[NAMES.index(target)] >= MIN_ON_TARGET * total:
        hits[target] = hits.get(target, 0) + 1

print(f"== channel check, {({6: '7.1.4', 8: '9.1.6'})[layout]} render")
failures = []
for name in "L R C LFE Lrs Rrs Lss Rss Ltf Rtf Ltr Rtr".split():
    n = totals.get(name, 0)
    if n == 0:
        failures.append(f"{name}: no tone found")
        continue
    share = hits.get(name, 0) / n
    print(f"  {name:4s} {100 * share:6.2f}% of {n} frames with >= 90% on target")
    if share < MIN_FRAMES:
        failures.append(f"{name}: {100 * share:.1f}% of frames on target")
for f in failures:
    print("  FAIL", f)
print("FAIL" if failures else "PASS")
sys.exit(1 if failures else 0)
