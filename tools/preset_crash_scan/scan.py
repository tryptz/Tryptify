#!/usr/bin/env python3
"""Runs every bundled preset through the harness and lists the ones that die.

Splits the presets into one block per worker. Each worker is a harness
process walking its block in order; when one dies, the preset it was on is
recorded with the signal and the sanitizer's summary, and a fresh process
carries on from the next preset. So one bad preset costs one restart, not the
run.

usage: scan.py <build dir> [--workers N] [--frames N] [--out DIR]

Writes <out>/crashes.tsv (index, signal, detail, stack, path), one
results<k>.tsv per worker for every preset that survived (status, load ms,
mean and worst frame ms, GL error, projectM's load error if any), a
stderr_<index>.txt per crash, and prints the Kotlin set for
KnownCrashPresets.kt.
"""
import argparse
import os
import re
import signal
import subprocess
import tempfile
import threading

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PRESETS = os.path.join(REPO, "app", "src", "main", "projectm-assets", "presets")
TIMEOUT_S = 30

ap = argparse.ArgumentParser()
ap.add_argument("build")
ap.add_argument("--workers", type=int, default=os.cpu_count() or 4)
ap.add_argument("--frames", type=int, default=16)
ap.add_argument("--out", default="scan-out")
args = ap.parse_args()

os.makedirs(args.out, exist_ok=True)
paths = sorted(
    os.path.join(d, f) for d, _, fs in os.walk(PRESETS) for f in fs if f.endswith(".milk")
)
listing = os.path.join(args.out, "list.txt")
with open(listing, "w") as f:
    f.write("\n".join(paths) + "\n")
# The app ships no textures, so neither does the scan: a preset that asks for
# one has to cope with not getting it, exactly as on a phone.
empty_textures = tempfile.mkdtemp()

env = dict(
    os.environ,
    ASAN_OPTIONS="detect_leaks=0:symbolize=1",
    TEXTURE_DIR=empty_textures,
    PRESET_TIMEOUT=str(TIMEOUT_S),
    # One llvmpipe thread per worker; the workers are the parallelism.
    LP_NUM_THREADS="1",
)
harness = os.path.join(os.path.abspath(args.build), "harness")
lock = threading.Lock()
crashes = []


def describe(code, stderr):
    name = signal.Signals(-code).name if code < 0 else f"exit {code}"
    summary = re.search(r"SUMMARY: AddressSanitizer: ([^\n]*)", stderr)
    thrown = re.search(r"terminate called after throwing[^\n]*\n[^\n]*", stderr)
    detail = summary.group(1) if summary else (thrown.group(0) if thrown else "")
    if code == -signal.SIGALRM:
        detail = f"no frame for {TIMEOUT_S} s (hang)"
    stack = " < ".join(re.findall(r"#\d+ 0x[0-9a-f]+ in (\S+)", stderr)[:6])
    return name, " ".join(detail.split())[:300], stack


def worker(k, lo, hi):
    prog = os.path.join(args.out, f"prog{k}.txt")
    res = os.path.join(args.out, f"results{k}.tsv")
    start = lo
    while start <= hi:
        p = subprocess.run(
            [harness, listing, str(start), str(hi), prog, res, str(args.frames)],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, errors="replace",
        )
        if p.returncode == 0:
            break
        try:
            died = int(open(prog).read())
        except (OSError, ValueError):
            died = start
        sig, detail, stack = describe(p.returncode, p.stderr)
        with open(os.path.join(args.out, f"stderr_{died}.txt"), "w") as f:
            f.write(p.stderr[-20000:])
        with lock:
            crashes.append((died, sig, detail, stack, paths[died]))
            print(f"#{died} {sig} {detail} :: {os.path.relpath(paths[died], PRESETS)}", flush=True)
        start = died + 1


n = len(paths)
blocks = [(n * k // args.workers, n * (k + 1) // args.workers - 1) for k in range(args.workers)]
threads = [threading.Thread(target=worker, args=(k, lo, hi)) for k, (lo, hi) in enumerate(blocks)]
for t in threads:
    t.start()
for t in threads:
    t.join()

crashes.sort()
with open(os.path.join(args.out, "crashes.tsv"), "w") as f:
    for row in crashes:
        f.write("\t".join(str(c) for c in row) + "\n")
print(f"\n{len(crashes)} of {n} presets crashed.\n")
for row in crashes:
    rel = os.path.relpath(row[4], PRESETS).replace("\\", "/")
    print('        "' + rel.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '",')
