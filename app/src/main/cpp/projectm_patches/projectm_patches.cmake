# Crash fixes to the vendored projectM, applied without touching the submodule.
#
# Each entry swaps one of projectM's own source files for a fixed copy kept in
# this directory, by rewriting the owning target's SOURCES after
# add_subdirectory(third_party/projectm). The submodule stays pinned to clean
# upstream, a fresh `git submodule update` needs nothing re-applied, and every
# fix is an ordinary file in this repo with a header saying what changed.
#
# Included by app/src/main/cpp/CMakeLists.txt, and by the host harness that
# runs every bundled preset through projectM, so the APK and the test are
# built from the same sources.

set(PROJECTM_PATCHED_VERSION 4.1.6)

if(NOT DEFINED libprojectM_SOURCE_DIR)
    message(FATAL_ERROR "projectm_patches.cmake must be included after add_subdirectory(projectm)")
endif()
# The fixed copies are copies of 4.1.6 files. Against any other version they
# would silently revert whatever upstream changed in them, so a version bump
# has to come back here and decide, file by file, whether each fix is still
# needed.
file(READ "${libprojectM_SOURCE_DIR}/CMakeLists.txt" _pm_cmake)
string(REGEX MATCH "project\\(libprojectM[^)]*VERSION ([0-9.]+)" _ "${_pm_cmake}")
if(NOT CMAKE_MATCH_1 STREQUAL PROJECTM_PATCHED_VERSION)
    message(FATAL_ERROR
        "projectM is ${CMAKE_MATCH_1}, but projectm_patches/ holds fixes to ${PROJECTM_PATCHED_VERSION}. "
        "Re-check each patched file against the new upstream before building.")
endif()

# replace_projectm_source(<target> <file as listed in the target> <fixed copy>)
function(replace_projectm_source target original replacement)
    get_target_property(_sources ${target} SOURCES)
    list(FIND _sources "${original}" _at)
    if(_at EQUAL -1)
        message(FATAL_ERROR "projectM target ${target} no longer lists ${original}; the patch for it is stale.")
    endif()
    list(REMOVE_AT _sources ${_at})
    list(INSERT _sources ${_at} "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/${replacement}")
    set_property(TARGET ${target} PROPERTY SOURCES "${_sources}")
endfunction()

# A preset sampling a texture that cannot be found left a descriptor with no
# texture, and the next frame read through it: SIGSEGV on the render thread.
replace_projectm_source(Renderer TextureSamplerDescriptor.cpp TextureSamplerDescriptor.cpp)
