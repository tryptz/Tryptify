#!/usr/bin/env bash
# Real-content reference check for the native Atmos renderer.
#
# The 5.1 core of an E-AC-3 JOC stream is Dolby's encoder rendering the Atmos
# objects to 5.1, so it is a Dolby reference that ships inside every stream.
# This decodes the bundled test clip with FFmpeg (the same core decode NextLib
# does on device), runs the repo's native code over it, renders the objects to
# 5.1 and compares with that core. See compare.py for what is checked.
#
#   app/src/main/cpp/atmos/tools/reference_check/run.sh [clip.mp4]
#
# Needs ffmpeg/ffprobe and a C++17 compiler on PATH. Exits non-zero on failure.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ATMOS="$HERE/../.."
CLIP="${1:-$ATMOS/../../assets/atmos_test.mp4}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ffmpeg -v error -y -i "$CLIP" -map 0:a:0 -c:a copy -f eac3 "$WORK/raw.ec3"
ffprobe -v error -select_streams a:0 -show_entries packet=size,pts -of csv=p=0 "$CLIP" > "$WORK/packets.csv"
ffmpeg -v error -y -i "$CLIP" -map 0:a:0 -ac 6 -f f32le -acodec pcm_f32le "$WORK/bed.f32"

c++ -std=c++17 -O2 -I"$ATMOS" "$HERE/harness.cpp" -o "$WORK/harness"
"$WORK/harness" "$WORK/raw.ec3" "$WORK/packets.csv" "$WORK/bed.f32" 1 > "$WORK/frames51.csv" 2> "$WORK/objects.csv"
python3 "$HERE/compare.py" "$WORK/bed.f32" "$WORK/frames51.csv" "$WORK/objects.csv"
