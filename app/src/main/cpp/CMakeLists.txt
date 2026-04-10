cmake_minimum_required(VERSION 3.22.1)
project("monochrome_native")

# ── ProjectM Visualizer ──────────────────────────────────────────────────
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm
    ${CMAKE_CURRENT_BINARY_DIR}/projectm)

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
    dsp/spatial_renderer.cpp
    dsp/spatial_renderer_jni.cpp
    # dsp/atmos/AtmosDecoderJni.cpp
    # dsp/atmos/Eac3Decoder.cpp
    dsp/atmos/JocReconstructor.cpp
    dsp/atmos/BinauralRenderer.cpp
    dsp/atmos/RoomSimulator.cpp
)

target_include_directories(monochrome_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/util
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/snapins
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/atmos
    ${CMAKE_CURRENT_BINARY_DIR}/ffmpeg/include
    ${CMAKE_CURRENT_BINARY_DIR}/libmysofa/include
)

# ── FFmpeg (Disabled temporarily due to Windows build constraints) ──────
# include(ExternalProject)
# set(FFMPEG_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/ffmpeg)
# ExternalProject_Add(ffmpeg_source ...)
# ExternalProject_Add(libmysofa_source ...)
# add_dependencies(monochrome_dsp ffmpeg_source libmysofa_source)

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
