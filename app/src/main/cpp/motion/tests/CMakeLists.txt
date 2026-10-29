# SPDX-License-Identifier: GPL-3.0-or-later
#
# Host build of the motion blur tests. motion_blur.cpp carries no Android
# headers, so it builds on a desktop compiler as it is.
#
#   cmake -S app/src/main/cpp/motion/tests -B build/motion-tests
#   cmake --build build/motion-tests && ctest --test-dir build/motion-tests --output-on-failure

cmake_minimum_required(VERSION 3.22.1)
project(motion_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

set(MOTION_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)

add_executable(motion_blur_test
    motion_blur_test.cpp
    ${MOTION_DIR}/motion_blur.cpp)
target_include_directories(motion_blur_test PRIVATE ${MOTION_DIR})
# The flags the app builds it with, so a test passes on the same code.
target_compile_options(motion_blur_test PRIVATE -O2 -fno-exceptions -fno-rtti -Wall -Wextra)
add_test(NAME motion_blur_test COMMAND motion_blur_test)
