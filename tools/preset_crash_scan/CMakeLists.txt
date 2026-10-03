# Host build of the preset crash scanner: projectM exactly as the app builds it
# (GLES, the vendored projectM-eval, the fixes in projectm_patches/), under
# AddressSanitizer, linked into a harness that loads and renders presets on a
# headless EGL context. See README.md.
cmake_minimum_required(VERSION 3.22)
project(preset_crash_scan C CXX)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)

set(ENABLE_GLES ON CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)

# ASan on everything, projectM included: a preset that corrupts memory without
# happening to crash a desktop can still crash a phone, and ASan turns every
# such write into a report.
add_compile_options(-fsanitize=address -fno-omit-frame-pointer)
add_link_options(-fsanitize=address)

add_subdirectory(${REPO_ROOT}/third_party/projectm ${CMAKE_BINARY_DIR}/projectm)
include(${REPO_ROOT}/app/src/main/cpp/projectm_patches/projectm_patches.cmake)

add_executable(harness harness.cpp)
target_include_directories(harness PRIVATE
    ${REPO_ROOT}/third_party/projectm/src/api/include
    ${CMAKE_BINARY_DIR}/projectm/src/api/include)
target_link_libraries(harness projectM EGL GLESv2 pthread)
