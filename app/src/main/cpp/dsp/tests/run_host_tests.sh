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

# The effects' delay lines and rings: read as they did before their % went,
# never past their ends at any knob setting (DSP_CHECK_DELAY_RANGE counts
# that), silent after a rate change; the limiter past its old 2^31-sample
# overflow. Run again under the sanitizers below.
echo "== snapin_buffers_test"
$cxx $flags -DDSP_CHECK_DELAY_RANGE "$here/snapin_buffers_test.cpp" -o "$out/snapin_buffers_test"
"$out/snapin_buffers_test"

# The mixer's exactness: a flat mix and a fader moved and back come out bit
# for bit, the glide is the one it was, and the untouched flag never lies.
echo "== engine_exactness_test"
$cxx $flags "$here/engine_exactness_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/engine_exactness_test"
"$out/engine_exactness_test"

# ReplayGain's stage, alone and in front of the mixer, the Oxford effects and
# every snapin: exact at unity, no click on a change, a raised song's level,
# and a song with no tags measured and turned down to the target. With
# -ffast-math as on the phone: its checks must not lean on NaN.
echo "== replay_gain_test"
$cxx $flags -ffast-math "$here/replay_gain_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/replay_gain_test"
"$out/replay_gain_test"

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

# Knobs that follow the music: the follower against the Kotlin side's numbers,
# with -ffast-math as on the phone, and links through the engine's chain edits.
echo "== mod_route_test"
$cxx $flags -ffast-math "$here/mod_route_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/mod_route_test"
"$out/mod_route_test"

# The oversampler: exact latency, linear phase, passband null, image rejection.
echo "== oversampler_test"
$cxx $flags "$here/oversampler_test.cpp" -o "$out/oversampler_test"
"$out/oversampler_test"

# The loudness meter against EBU Tech 3341 / 3342's reference signals, with
# -ffast-math as on the phone: its no-reading and silence values must survive it.
echo "== loudness_meter_test"
$cxx $flags -ffast-math "$here/loudness_meter_test.cpp" -o "$out/loudness_meter_test"
"$out/loudness_meter_test"

# The dynamic range meter against signals whose DR is known by hand, with
# -ffast-math as on the phone: its no-reading value must survive it.
echo "== dynamic_range_meter_test"
$cxx $flags -ffast-math "$here/dynamic_range_meter_test.cpp" -o "$out/dynamic_range_meter_test"
"$out/dynamic_range_meter_test"

# The spectrum waterfall's geometry: inside the box at every angle, the fade
# where it was set, a fixed line count whatever the depth.
echo "== spectrum_waterfall_test"
$cxx $flags -ffast-math "$here/spectrum_waterfall_test.cpp" -o "$out/spectrum_waterfall_test"
"$out/spectrum_waterfall_test"

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

    echo "== snapin_buffers_test (asan, ubsan)"
    $cxx $flags -DDSP_CHECK_DELAY_RANGE -g -fsanitize=address,undefined -fno-omit-frame-pointer \
        "$here/snapin_buffers_test.cpp" -o "$out/snapin_buffers_test_asan"
    UBSAN_OPTIONS=halt_on_error=1 "$out/snapin_buffers_test_asan"

    echo "== engine_stress_test chaos (tsan)"
    $cxx $flags -g -fsanitize=thread \
        "$here/engine_stress_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/engine_stress_tsan" -lpthread
    TSAN_OPTIONS=halt_on_error=1 "$out/engine_stress_tsan" \
        "$here/../../../../test/resources/snapin_ranges.csv" 10 chaos
fi

# The finite guards with -ffast-math. Host compilers keep their bit tests,
# but clang 21 (NDK r29) folded them away (util/finite.h), so with the NDK
# and qemu on this machine the test is also built by the phone's compiler,
# with the library's flags, and run under emulation for both ARM ABIs.
echo "== finite_guard_test (fast-math)"
$cxx $flags -ffast-math "$here/finite_guard_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/finite_guard_test"
"$out/finite_guard_test"
# The SDK as the build finds it: $ANDROID_HOME, or local.properties' sdk.dir.
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
props="$here/../../../../../../local.properties"
if [ -z "$sdk" ] && [ -f "$props" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$props" | tail -n 1)
fi
ndk_bin=$(ls -d "${sdk:-/nonexistent}"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin 2>/dev/null | tail -n 1 || true)
for abi in aarch64 armv7a; do
    echo "== finite_guard_test (NDK $abi, qemu)"
    case $abi in
        aarch64) target=aarch64-linux-android26; qemu=qemu-aarch64; arch=-march=armv8-a+simd ;;
        *) target=armv7a-linux-androideabi26; qemu=qemu-arm; arch= ;;
    esac
    runner=$(command -v "$qemu-static" || command -v "$qemu" || true)
    if [ -z "$ndk_bin" ] || [ ! -x "$ndk_bin/$target-clang++" ] || [ -z "$runner" ]; then
        echo "skipped: needs the NDK (\$ANDROID_HOME or sdk.dir) and $qemu (qemu-user-static)"
        continue
    fi
    "$ndk_bin/$target-clang++" -std=c++17 -O3 -ffast-math -fno-exceptions -fno-rtti $arch -static \
        -I"$dsp" -I"$dsp/util" -I"$dsp/snapins" -I"$dsp/.." -I"$here/host_include" \
        "$here/finite_guard_test.cpp" "$dsp/dsp_engine.cpp" -o "$out/finite_guard_test_$abi"
    "$runner" "$out/finite_guard_test_$abi"
done

# The spatial map's placer: channels heard where they are placed (HRIR and
# pan), the LFE undirected, drags smooth, nonsense ignored. Lives with the
# Atmos renderer it drives; -ffast-math as on the phone.
echo "== channel_placer_test"
atmos="$dsp/../atmos"
$cxx -std=c++17 -O2 -ffast-math -I"$atmos" -I"$atmos/render" \
    "$atmos/tests/channel_placer_test.cpp" -o "$out/channel_placer_test"
"$out/channel_placer_test"

# The USB DAC bypass driver against stubbed libusb: alt-setting match for
# UAC2 and UAC3 BADD (Apple's USB-C adapter in each of its configurations)
# and iso packets that never pass wMaxPacketSize. Needs the libusb headers
# from the submodule.
usb="$dsp/../usb"
libusb_inc="$here/../../../../../../third_party/libusb/libusb"
echo "== uac_driver_test"
if [ -f "$libusb_inc/libusb.h" ]; then
    $cxx -std=c++17 -O2 -I"$usb" -I"$here/host_include" -I"$libusb_inc" \
        "$usb/tests/uac_driver_test.cpp" "$usb/libusb_uac_driver.cpp" \
        -o "$out/uac_driver_test" -lpthread
    "$out/uac_driver_test"
else
    echo "skipped: third_party/libusb is not checked out"
fi
