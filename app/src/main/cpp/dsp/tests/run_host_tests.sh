#!/usr/bin/env sh
# Builds and runs the DSP host tests with the desktop compiler. No device and
# no NDK: the engine only needs the NDK for logging, which host_include/
# stands in for.
#
#   app/src/main/cpp/dsp/tests/run_host_tests.sh
#
# Exits non-zero if any test fails to build or fails.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
dsp="$here/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cxx=${CXX:-c++}
flags="-std=c++17 -O2 -I$dsp -I$dsp/util -I$dsp/snapins -I$dsp/.. -I$here/host_include"

echo "== wsola_pitch_test"
$cxx $flags "$here/wsola_pitch_test.cpp" -o "$out/wsola_pitch_test"
"$out/wsola_pitch_test"

echo "== multilane_engine_test"
$cxx $flags "$here/multilane_engine_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/multilane_engine_test"
"$out/multilane_engine_test"

echo "== oxford_multichannel_test"
$cxx $flags "$here/oxford_multichannel_test.cpp" -o "$out/oxford_multichannel_test"
"$out/oxford_multichannel_test"

echo "== snapin_fixes_test"
$cxx $flags "$here/snapin_fixes_test.cpp" -o "$out/snapin_fixes_test"
"$out/snapin_fixes_test"

# Every snapin's defaults against the table ParamDefs.kt is also checked
# against. After a deliberate default change, regenerate it with --write.
echo "== snapin_defaults_test"
$cxx $flags "$here/snapin_defaults_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/snapin_defaults_test"
"$out/snapin_defaults_test" "$here/../../../../test/resources/snapin_defaults.csv"
