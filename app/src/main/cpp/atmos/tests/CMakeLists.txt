# Host (desktop) build of the cpp/atmos unit tests. The NDK build in
# app/src/main/cpp/CMakeLists.txt only type-checks these headers for Android;
# the tests themselves run on the build machine:
#
#   cmake -S app/src/main/cpp/atmos/tests -B build/atmos-tests
#   cmake --build build/atmos-tests && ctest --test-dir build/atmos-tests --output-on-failure
cmake_minimum_required(VERSION 3.16)
project(monochrome_atmos_host_tests CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

# Every *.cpp here is one self-checking test binary (exit code 0 = pass).
file(GLOB ATMOS_TESTS ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp)
foreach(src ${ATMOS_TESTS})
  get_filename_component(name ${src} NAME_WE)
  add_executable(${name} ${src})
  target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
  target_compile_options(${name} PRIVATE -Wall)
  add_test(NAME ${name} COMMAND ${name})
endforeach()
