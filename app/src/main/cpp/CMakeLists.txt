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
