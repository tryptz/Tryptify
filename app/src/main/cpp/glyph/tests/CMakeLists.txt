# SPDX-License-Identifier: GPL-3.0-or-later
#
# Host build of the Trypt Mania engine tests. The engine sources carry no
# Android headers, so they build on a desktop compiler as they are.
#
#   cmake -S app/src/main/cpp/glyph/tests -B build/glyph-tests
#   cmake --build build/glyph-tests && ctest --test-dir build/glyph-tests --output-on-failure

cmake_minimum_required(VERSION 3.22.1)
project(glyph_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

set(GLYPH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)

add_executable(glyph_engine_test
    glyph_engine_test.cpp
    ${GLYPH_DIR}/glyph_engine.cpp
    ${GLYPH_DIR}/glyph_timing.cpp)
target_include_directories(glyph_engine_test PRIVATE ${GLYPH_DIR})
# The flags the app builds the engine with, so a test passes on the same code.
target_compile_options(glyph_engine_test PRIVATE -O2 -fno-exceptions -fno-rtti -Wall -Wextra)
add_test(NAME glyph_engine_test COMMAND glyph_engine_test)
