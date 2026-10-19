#!/usr/bin/env bash
# Channel-check test for the native Atmos speaker renderer.
#
# Dolby's 7.1.4 test tones (dolby-test-tones_7_1_4.mp4, E-AC-3 JOC, 12 objects)
# play each speaker of a 7.1.4 layout in turn, as an object placed exactly at
# that speaker. That gives every frame a known right answer: this renders the
# clip to 7.1.4 and 9.1.6 and checks that each tone's energy lands on its own
# speaker. See channel_check.py for what is checked.
#
# The clip is ~190 MB and not committed. It is Dolby's channel-check download,
# mirrored with the other Atmos demo files at https://www.peterpee.com/demo.
#
#   app/src/main/cpp/atmos/tools/reference_check/channel_check.sh dolby-test-tones_7_1_4.mp4
#
# Needs ffmpeg/ffprobe, a C++17 compiler and ~2 GB of memory (the harness reads
# the whole decoded bed). Takes a few minutes. Exits non-zero on failure.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ATMOS="$HERE/../.."
CLIP="${1:?usage: channel_check.sh dolby-test-tones_7_1_4.mp4}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ffmpeg -v error -y -i "$CLIP" -map 0:a:0 -c:a copy -f eac3 "$WORK/raw.ec3"
ffprobe -v error -select_streams a:0 -show_entries packet=size,pts -of csv=p=0 "$CLIP" > "$WORK/packets.csv"
ffmpeg -v error -y -i "$CLIP" -map 0:a:0 -ac 6 -f f32le -acodec pcm_f32le "$WORK/bed.f32"

c++ -std=c++17 -O2 -I"$ATMOS" "$HERE/harness.cpp" -o "$WORK/harness"
status=0
for layout in 6 8; do  # OutputLayout k7_1_4, k9_1_6
  "$WORK/harness" "$WORK/raw.ec3" "$WORK/packets.csv" "$WORK/bed.f32" "$layout" \
    > "$WORK/frames.csv" 2> "$WORK/objects.csv"
  python3 "$HERE/channel_check.py" "$layout" "$WORK/frames.csv" "$WORK/objects.csv" || status=1
done
exit $status
