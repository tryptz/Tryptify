# Host build of the tag reader's tests: tag_reader.cpp over TagLib, built as
# the app builds it, on a desktop compiler. The files it reads are TagLib's
# own test files (third_party/taglib/tests/data), one of every format.
#
#   cmake -S app/src/main/cpp/tags/tests -B build/tags-tests
#   cmake --build build/tags-tests && ctest --test-dir build/tags-tests --output-on-failure

cmake_minimum_required(VERSION 3.22.1)
project(tags_host_tests C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

set(TAGS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)
include(${TAGS_DIR}/taglib.cmake)

add_executable(tag_reader_test
    tag_reader_test.cpp
    ${TAGS_DIR}/tag_reader.cpp)
target_include_directories(tag_reader_test PRIVATE ${TAGS_DIR} ${TAGLIB_INCLUDE_DIRS})
target_compile_options(tag_reader_test PRIVATE -O2 -Wall -Wextra)
target_link_libraries(tag_reader_test tag)
add_test(NAME tag_reader_test
    COMMAND tag_reader_test ${TAGLIB_DIR}/tests/data ${CMAKE_CURRENT_BINARY_DIR})
