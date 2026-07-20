cmake_minimum_required(VERSION 3.22.1)
project("monochrome_native")

# NDK 28/29 + CMake doesn't auto-link libc++ when using -static-libstdc++.
# Add explicit shared libc++ to every target (including projectm subproject).
link_libraries(c++_shared)

# ── ProjectM Visualizer ──────────────────────────────────────────────────
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm
    ${CMAKE_CURRENT_BINARY_DIR}/projectm)

# The ProjectM subproject defaults to appending 'd' to the library name in
# debug builds (libprojectM-4d.so / libprojectM-4-playlistd.so), which means
# System.loadLibrary("projectM-4") fails and triggers a scary `dlopen failed`
# in logcat on every debug launch. Force the non-suffixed name on all config
# types so one Kotlin loader path works for both debug and release.
set_target_properties(projectM PROPERTIES DEBUG_POSTFIX "")
set_target_properties(projectM_playlist PROPERTIES DEBUG_POSTFIX "")

add_library(monochrome_visualizer SHARED
    projectm_bridge.cpp
    projectm_jni.cpp
    audio_ring_buffer.cpp
)

target_include_directories(monochrome_visualizer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm/src/api/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm/src/playlist/api
    ${CMAKE_CURRENT_BINARY_DIR}/projectm/src/api/include
    ${CMAKE_CURRENT_BINARY_DIR}/projectm/src/playlist/include
)

target_link_libraries(monochrome_visualizer
    android
    log
    EGL
    GLESv3
    projectM
    projectM_playlist
)

# ── DSP Engine ───────────────────────────────────────────────────────────
add_library(monochrome_dsp SHARED
    dsp/dsp_engine.cpp
    dsp/dsp_jni.cpp
    dsp/oxford/oxford_jni.cpp
)

target_include_directories(monochrome_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/util
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/snapins
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/oxford
)

target_compile_features(monochrome_dsp PRIVATE cxx_std_17)

target_compile_options(monochrome_dsp PRIVATE
    -O3 -ffast-math -fno-exceptions -fno-rtti
    # ARM NEON SIMD for float processing on arm64
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+simd>
)

# Prevent debug 'd' suffix so System.loadLibrary() names match
set_target_properties(monochrome_dsp PROPERTIES DEBUG_POSTFIX "")
set_target_properties(monochrome_visualizer PROPERTIES DEBUG_POSTFIX "")

target_link_libraries(monochrome_dsp
    android
    log
)

# ── libusb (USB Audio Class direct output) ────────────────────────────────
# Vendored at third_party/libusb. Upstream uses autotools + Android.mk;
# we list the platform source files directly here so the existing CMake
# toolchain handles arm64-v8a / armeabi-v7a / x86_64 in one shot. Builds
# as a static library so the resulting libmonochrome_usb.so embeds it
# (one less .so to load at app startup).
set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb)
add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)
target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_DIR}/libusb
    ${LIBUSB_DIR}/libusb/os
    ${LIBUSB_DIR}/android   # config.h with autotools-generated #defines
)
target_compile_options(usb1.0 PRIVATE
    -fvisibility=hidden
    -Wno-unused-parameter
    -Wno-unused-but-set-variable
)
set_target_properties(usb1.0 PROPERTIES POSITION_INDEPENDENT_CODE ON)

# JNI bridge + UAC2 driver. The actual isochronous transfer pump lives
# inside libusb_uac_driver.cpp; this shared lib is the surface Kotlin's
# UsbNativeLoader.ensureLoaded() pulls in.
add_library(monochrome_usb SHARED
    usb/usb_jni.cpp
    usb/libusb_uac_driver.cpp
)
target_include_directories(monochrome_usb PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/usb
    ${LIBUSB_DIR}/libusb
)
target_compile_features(monochrome_usb PRIVATE cxx_std_17)
target_compile_options(monochrome_usb PRIVATE
    -O3 -fno-exceptions -fno-rtti
)
set_target_properties(monochrome_usb PROPERTIES DEBUG_POSTFIX "")
target_link_libraries(monochrome_usb
    android
    log
    usb1.0
)

# ── Atmos renderer foundation (clean-room) ───────────────────────────────
# Header-only building blocks (bit reader, EMDF walker, OAMD coordinate math,
# VBAP bed panner) for the Dolby Atmos / E-AC-3 JOC object renderer. Built as a
# static library so the NDK type-checks the headers for every shipped ABI on
# each CI run; it carries no runtime dependency yet and is host-unit-tested via
# atmos/tests/atmos_tests.cpp. See atmos/README.md for scope and next steps.
add_library(monochrome_atmos STATIC
    atmos/atmos.cpp
)
target_include_directories(monochrome_atmos PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/atmos
)
target_compile_features(monochrome_atmos PRIVATE cxx_std_17)
target_compile_options(monochrome_atmos PRIVATE
    -O3 -fno-exceptions -fno-rtti
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+simd>
)
set_target_properties(monochrome_atmos PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(monochrome_atmos log)

# ── libmysofa (runtime SOFA HRTF loading) ────────────────────────────────
# Vendored at third_party/libmysofa (BSD-3-Clause). Used ONLY by the Atmos JNI
# lib to load a user-supplied .sofa HRTF at runtime; the default HRTF is still
# the baked hrir_table.h, so a device that never loads a SOFA pulls in this code
# but never runs it. Parses the HDF5-based SOFA container (needs zlib) and the
# embedded Speex resampler (OUTSIDE_SPEEX + RANDOM_PREFIX to avoid symbol clash).
set(MYSOFA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libmysofa)
file(GLOB MYSOFA_SRC ${MYSOFA_DIR}/hrtf/*.c ${MYSOFA_DIR}/hdf/*.c
    ${MYSOFA_DIR}/resampler/speex_resampler.c)
add_library(mysofa STATIC ${MYSOFA_SRC})
target_include_directories(mysofa PUBLIC
    ${MYSOFA_DIR}/hrtf ${MYSOFA_DIR}/hdf ${MYSOFA_DIR}/resampler)
target_compile_definitions(mysofa PRIVATE OUTSIDE_SPEEX RANDOM_PREFIX=mysofa)
target_compile_options(mysofa PRIVATE
    -O2 -Wno-unused-parameter -Wno-unused-but-set-variable -Wno-unused-function)
set_target_properties(mysofa PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(mysofa z)

# ── Atmos JNI surface (plan Option B) ────────────────────────────────────
# Runtime .so exposing the clean-room metadata/object code to Kotlin. The E-AC-3
# core is decoded by Media3's FfmpegAudioRenderer (NextLib); this lib parses the
# Atmos side-data out of raw frames and (as the surface grows) runs the JOC QMF
# upmix on the decoded bed PCM. No FFmpeg dependency here.
add_library(monochrome_atmos_jni SHARED
    atmos/atmos_jni.cpp
    atmos/render/sofa_loader.cpp
)
target_include_directories(monochrome_atmos_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/atmos
    ${MYSOFA_DIR}/hrtf
)
target_compile_features(monochrome_atmos_jni PRIVATE cxx_std_17)
# The renderer DSP (QMF filterbank, JOC upmix, HRTF) is header-only, so it is
# compiled into THIS translation unit — not into monochrome_atmos. Without these
# flags a debug build runs the whole signal path at -O0, which misses the audio
# deadline and is audible as micro-stutter. Optimize regardless of build type.
target_compile_options(monochrome_atmos_jni PRIVATE
    -O3 -ffast-math
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+simd>
)
# Cancel projectM's global CMAKE_DEBUG_POSTFIX "d" so the lib ships as
# libmonochrome_atmos_jni.so (loadable by that name), like the sibling libs.
set_target_properties(monochrome_atmos_jni PROPERTIES DEBUG_POSTFIX "")
target_link_libraries(monochrome_atmos_jni monochrome_atmos mysofa log)
