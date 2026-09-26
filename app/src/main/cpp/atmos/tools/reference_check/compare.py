#!/usr/bin/env python3
"""Compares the renderer's output for a real E-AC-3 JOC stream with the stream's
own 5.1 core, which is Dolby's encoder render of the same objects to 5.1.

  compare.py bed.f32 frames51.csv objects.csv

Checks, and exits non-zero when one fails:
  1. every audible, moving object's decoded path tracks the core's left/right
     and front/back balance (catches audio/metadata misalignment and decode bugs);
  2. each 5.1 speaker of our render tracks the same speaker of the core;
  3. the distribution of energy across the speakers matches window by window.
Levels are reported, not checked: the port applies Cavern's 3 dB anti-clip trim.
"""
import array, collections, csv, math, sys

N, W = 1536, 8  # frame length; 8-frame windows wash out the 577-sample render latency
bed_path, frames_path, objects_path = sys.argv[1:4]

bed = array.array('f'); bed.frombytes(open(bed_path, 'rb').read())
F = len(bed) // (6 * N)
energy = []
for f in range(F):
    seg = bed[f * N * 6:(f + 1) * N * 6]
    e = [0.0] * 6
    for i in range(0, len(seg), 6):
        for c in range(6): e[c] += seg[i + c] * seg[i + c]
    energy.append(e)

def corr(a, b):
    n = len(a); ma = sum(a) / n; mb = sum(b) / n
    va = math.sqrt(sum((x - ma) ** 2 for x in a)); vb = math.sqrt(sum((y - mb) ** 2 for y in b))
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (va * vb + 1e-12)

failures = []

# 1. object paths vs the core's balance
per = collections.defaultdict(dict)
for r in csv.DictReader(open(objects_path)): per[int(r['obj'])][int(r['frame'])] = r
moving = 0
for o in sorted(per):
    rs = per[o]
    frames = [f for f in rs if float(rs[f]['rms']) > 1e-3 and rs[f]['bed'] == '0']
    if len(frames) < 50: continue
    xs = [float(rs[f]['x']) for f in frames]; ys = [float(rs[f]['y']) for f in frames]
    if max(xs) - min(xs) < 0.2 or max(ys) - min(ys) < 0.2: continue
    moving += 1
    tot = lambda f: sum(energy[f][c] for c in (0, 1, 2, 4, 5)) + 1e-12
    bx = [(energy[f][1] + energy[f][5] - energy[f][0] - energy[f][4]) / tot(f) for f in frames]
    by = [(energy[f][4] + energy[f][5] - energy[f][0] - energy[f][1] - energy[f][2]) / tot(f) for f in frames]
    cx, cy = corr(xs, bx), corr(ys, by)
    print(f"object {o}: path vs core balance  x {cx:+.3f}  y {cy:+.3f}  ({len(frames)} audible frames)")
    if cx < 0.85 or cy < 0.85: failures.append(f"object {o} path does not track the core")
if moving == 0: failures.append("no audible moving object (audio/metadata misaligned?)")

# 2 + 3. our 5.1 render vs the core, per speaker and per window
rows = list(csv.DictReader(open(frames_path)))
ours = [[float(r[f'ch{c}']) for c in range(6)] for r in rows]
joc = [int(r['joc_objects']) for r in rows]
ref = [[math.sqrt(x / N) for x in e] for e in energy]
def window(series, f0): return [math.sqrt(sum(series[f][c] ** 2 for f in range(f0, f0 + W)) / W) for c in range(6)]
starts = [f for f in range(0, min(F, len(ours)) - W, W) if all(joc[f + k] > 0 for k in range(W))]
R = [window(ref, s) for s in starts]; O = [window(ours, s) for s in starts]
for c, name in enumerate(['L', 'R', 'C', 'LFE', 'Ls', 'Rs']):
    r = [w[c] for w in R]; o = [w[c] for w in O]
    lvl = 20 * math.log10((sum(x * x for x in o) / len(o)) ** .5 / ((sum(x * x for x in r) / len(r)) ** .5 + 1e-12) + 1e-12)
    k = corr(r, o)
    print(f"speaker {name:>3}: corr {k:+.3f}   level {lvl:+.1f} dB vs core")
    if k < 0.85: failures.append(f"speaker {name} does not track the core")
sims = []
for rw, ow in zip(R, O):
    a = [rw[c] ** 2 for c in (0, 1, 2, 4, 5)]; b = [ow[c] ** 2 for c in (0, 1, 2, 4, 5)]
    na = math.sqrt(sum(x * x for x in a)); nb = math.sqrt(sum(x * x for x in b))
    if na > 1e-9 and nb > 1e-9: sims.append(sum(x * y for x, y in zip(a, b)) / (na * nb))
sims.sort()
med = sims[len(sims) // 2]
print(f"speaker-energy distribution similarity: median {med:.3f}, 10th percentile {sims[len(sims) // 10]:.3f} ({len(sims)} windows)")
if med < 0.95: failures.append("speaker energy distribution differs from the core")

print("PASS" if not failures else "FAIL: " + "; ".join(failures))
sys.exit(1 if failures else 0)
