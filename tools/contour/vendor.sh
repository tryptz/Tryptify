#!/bin/sh
# Copies Contour's core into third_party/contour, unchanged, from a checkout of
# https://github.com/Chronosauros/contour (Apache-2.0). Run it to move to a
# newer Contour:
#
#     tools/contour/vendor.sh /path/to/contour
#
# What is copied: the pure-Kotlin core and its tests, the device records the
# core's catalog is generated from, the protocol captures its parity test
# replays, the licence files, and from Contour's app the files that talk to a
# DAC over USB (USB_FILES below) with the simulated DACs its review builds
# use. Nothing else of Contour (its screens, its controller, its films) comes
# over. third_party/contour/VENDORED.md says which commit.
set -eu
src=${1:?a Contour checkout}
here=$(cd "$(dirname "$0")/../.." && pwd)
dst="$here/third_party/contour"
test -f "$src/android/core/devices.gradle.kts" || { echo "$src is not a Contour checkout" >&2; exit 1; }
commit=$(git -C "$src" rev-parse HEAD)
date=$(git -C "$src" log -1 --format=%cd --date=short)
rm -rf "$dst"
mkdir -p "$dst/android/core" "$dst/research"
cp "$src/LICENSE" "$src/NOTICE" "$src/THIRD_PARTY_NOTICES.md" "$dst/"
cp -R "$src/devices" "$dst/devices"
cp -R "$src/research/protocol" "$dst/research/protocol"
cp -R "$src/android/core/src" "$dst/android/core/src"
cp "$src/android/core/devices.gradle.kts" "$dst/android/core/devices.gradle.kts"
# The app's USB layer: the HID transport, the port the core's sessions talk
# through and the client that reads, writes and verifies. The app module
# compiles these as they are (app/build.gradle.kts); what they need of
# Contour's own app (its R and its UiText) is stood in for by
# app/src/main/java/io/github/chronosauros/contour/ContourAppShims.kt. A file
# Contour adds to this set shows up as a compile error: add it here.
# DeviceIdentity.kt, the dozen lines that turn a USB device into one of
# Contour's DACs, is left behind on purpose: the app has its own
# (app/src/main/java/io/github/chronosauros/contour/usb/DeviceIdentity.kt),
# which asks Contour first and then the app's list of untested DACs.
USB_FILES="HidIo.kt HidTransport.kt WalkPlayPort.kt DacHandle.kt UsbAudioKeepAlive.kt DacClient.kt UsbLog.kt UacProbe.kt"
pkg=io/github/chronosauros/contour/usb
mkdir -p "$dst/android/app/src/main/kotlin/$pkg" "$dst/android/app/src/perf/kotlin/$pkg"
for f in $USB_FILES; do cp "$src/android/app/src/main/kotlin/$pkg/$f" "$dst/android/app/src/main/kotlin/$pkg/$f"; done
# The simulated DACs (review builds only in Contour): the app's unit tests
# send an EQ to them through the same client a real DAC gets.
cp -R "$src/android/app/src/perf/kotlin/$pkg/sim" "$dst/android/app/src/perf/kotlin/$pkg/sim"
find "$dst" -name '__pycache__' -prune -exec rm -rf {} +
cat > "$dst/VENDORED.md" <<NOTE
# Contour's core, vendored

A copy of the core of [Contour](https://github.com/Chronosauros/contour), the
app that writes a parametric EQ into a USB DAC's own memory. Apache-2.0: see
\`LICENSE\`, \`NOTICE\` and \`THIRD_PARTY_NOTICES.md\` here, which are Contour's own.

- Commit: \`$commit\` ($date)
- Copied by \`tools/contour/vendor.sh\`, which says what is copied. Nothing in
  this folder is edited by hand: a change belongs upstream, or in Tryptify's
  own code beside it. Run the script again to move to a newer Contour.
- \`android/core\` is built as the Gradle module \`:contour-core\`
  (\`contour-core/build.gradle.kts\`), where Contour's own tests run:
  \`./gradlew :contour-core:test\`.
- \`android/app\` holds only the files that talk to a DAC over USB and the
  simulated DACs. The app module compiles them as they are
  (\`app/build.gradle.kts\`).
NOTE
echo "vendored Contour $commit ($date) into third_party/contour"
