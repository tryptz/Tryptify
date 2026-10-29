# SPDX-License-Identifier: GPL-3.0-or-later
#
# Host build of the RAR reader's tests: rar_reader.cpp over the vendored
# libarchive subset, built as the app builds it, on a desktop compiler.
#
#   cmake -S app/src/main/cpp/archive/tests -B build/archive-tests
#   cmake --build build/archive-tests && ctest --test-dir build/archive-tests --output-on-failure

cmake_minimum_required(VERSION 3.22.1)
project(archive_host_tests C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

set(ARCHIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)
include(${ARCHIVE_DIR}/libarchive_rar.cmake)

add_executable(rar_reader_test
    rar_reader_test.cpp
    ${ARCHIVE_DIR}/rar_reader.cpp)
target_include_directories(rar_reader_test PRIVATE ${ARCHIVE_DIR})
# The flags the app builds it with, so a test passes on the same code.
target_compile_options(rar_reader_test PRIVATE -O2 -fno-exceptions -fno-rtti -Wall -Wextra)
target_link_libraries(rar_reader_test archive_rar)
add_test(NAME rar_reader_test COMMAND rar_reader_test ${CMAKE_CURRENT_SOURCE_DIR}/fixtures)
