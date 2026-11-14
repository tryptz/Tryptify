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
# hrtf_runtime_set_test loads a set from a second thread while it renders.
find_package(Threads REQUIRED)

# Every *.cpp here is one self-checking test binary (exit code 0 = pass).
file(GLOB ATMOS_TESTS ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp)
foreach(src ${ATMOS_TESTS})
  get_filename_component(name ${src} NAME_WE)
  add_executable(${name} ${src})
  target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
  target_compile_options(${name} PRIVATE -Wall)
  target_link_libraries(${name} PRIVATE Threads::Threads)
  add_test(NAME ${name} COMMAND ${name})
  # And again as the phone builds it. monochrome_atmos_jni is -O3 -ffast-math,
  # under which the compiler takes every float for finite and folds isnan /
  # isinf / isfinite away. The object positions once hung on a NaN sentinel:
  # every host test passed, and on a phone every Atmos object's position was
  # NaN (they all played from one speaker and none moved). Nothing here may
  # mean anything by a NaN or an infinity; this build is what holds it to that.
  add_executable(${name}_fastmath ${src})
  target_include_directories(${name}_fastmath PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
  target_compile_options(${name}_fastmath PRIVATE -O3 -ffast-math -Wno-nan-infinity-disabled)
  target_link_libraries(${name}_fastmath PRIVATE Threads::Threads)
  add_test(NAME ${name}_fastmath COMMAND ${name}_fastmath)
endforeach()
