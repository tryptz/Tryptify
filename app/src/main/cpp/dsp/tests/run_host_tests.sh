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

# Bus-to-bus routing: order, levels, loops, solo, delay compensation, saves.
echo "== dsp_routing_test"
$cxx $flags "$here/dsp_routing_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/dsp_routing_test"
"$out/dsp_routing_test"

# The mix as Kotlin writes it before the engine exists, read by the engine.
echo "== state_fixture_test"
$cxx $flags "$here/state_fixture_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/state_fixture_test"
"$out/state_fixture_test" "$here/../../../../test/resources/mixer_state_fixture.json"

# The oversampler: exact latency, linear phase, passband null, image rejection.
echo "== oversampler_test"
$cxx $flags "$here/oversampler_test.cpp" -o "$out/oversampler_test"
"$out/oversampler_test"

# Everything that could break the engine, under AddressSanitizer and
# UndefinedBehaviorSanitizer, with -ffast-math as on the phone (a NaN guard
# the flag would delete must fail here). Then the control-vs-audio thread
# chaos again under ThreadSanitizer, which cannot share a binary with ASan.
# Sanitizers need a toolchain that ships them; SANITIZE=0 skips them.
if [ "${SANITIZE:-1}" != "0" ]; then
    echo "== engine_stress_test (asan, ubsan, fast-math)"
    $cxx $flags -ffast-math -g -fsanitize=address,undefined -fno-omit-frame-pointer \
        "$here/engine_stress_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/engine_stress_test" -lpthread
    UBSAN_OPTIONS=halt_on_error=1 "$out/engine_stress_test" \
        "$here/../../../../test/resources/snapin_ranges.csv" 8

    echo "== engine_stress_test chaos (tsan)"
    $cxx $flags -g -fsanitize=thread \
        "$here/engine_stress_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/engine_stress_tsan" -lpthread
    TSAN_OPTIONS=halt_on_error=1 "$out/engine_stress_tsan" \
        "$here/../../../../test/resources/snapin_ranges.csv" 10 chaos
fi

# The spatial map's placer: channels heard where they are placed (HRIR and
# pan), the LFE undirected, drags smooth, nonsense ignored. Lives with the
# Atmos renderer it drives; -ffast-math as on the phone.
echo "== channel_placer_test"
atmos="$dsp/../atmos"
$cxx -std=c++17 -O2 -ffast-math -I"$atmos" -I"$atmos/render" \
    "$atmos/tests/channel_placer_test.cpp" -o "$out/channel_placer_test"
"$out/channel_placer_test"
