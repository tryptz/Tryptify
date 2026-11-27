#!/bin/sh
# Copies Contour's core into third_party/contour, unchanged, from a checkout of
# https://github.com/Chronosauros/contour (Apache-2.0). Run it to move to a
# newer Contour:
#
#     tools/contour/vendor.sh /path/to/contour
#
# What is copied: the pure-Kotlin core and its tests, the device records the
# core's catalog is generated from, the protocol captures its parity test
# replays, and the licence files. Nothing else of Contour (its app, its UI,
# its films) comes over. third_party/contour/VENDORED.md says which commit.
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
- Built as the Gradle module \`:contour-core\` (\`contour-core/build.gradle.kts\`),
  where Contour's own tests run: \`./gradlew :contour-core:test\`.
NOTE
echo "vendored Contour $commit ($date) into third_party/contour"
