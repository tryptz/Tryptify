# TagLib (third_party/taglib, a submodule, LGPL-2.1 / MPL-1.1), built as a
# static library for the app's own tag reader. Included by the app's
# CMakeLists.txt and by tags/tests, so both build the same thing.
#
# Fetch it with its own submodule (utfcpp):
#   git submodule update --init --recursive --depth 1

set(TAGLIB_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/taglib)
if(NOT EXISTS ${TAGLIB_DIR}/CMakeLists.txt OR NOT EXISTS ${TAGLIB_DIR}/3rdparty/utfcpp/source/utf8.h)
    message(FATAL_ERROR
        "third_party/taglib is not checked out (or its utfcpp is not). Run: "
        "git submodule update --init --recursive --depth 1")
endif()

# Normal variables, not cache entries: they hold for TagLib's directory and
# leave every other project in this build (projectM is built shared) alone.
set(BUILD_SHARED_LIBS OFF)
set(BUILD_TESTING OFF)
set(BUILD_EXAMPLES OFF)
set(BUILD_BINDINGS OFF)
set(VISIBILITY_HIDDEN ON)
set(ENABLE_CCACHE OFF)
# Tracker modules are not music the app plays, and their parsers are code a
# stray .mod or .xm in a folder would otherwise run.
set(WITH_MOD OFF)
set(WITH_ZLIB ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${TAGLIB_DIR} ${CMAKE_CURRENT_BINARY_DIR}/taglib EXCLUDE_FROM_ALL)
unset(BUILD_SHARED_LIBS)
unset(BUILD_TESTING)
unset(BUILD_EXAMPLES)
unset(BUILD_BINDINGS)

# TagLib's headers sit one folder per format, and its target only exports
# them once installed.
set(TAGLIB_INCLUDE_DIRS
    ${TAGLIB_DIR}/taglib
    ${TAGLIB_DIR}/taglib/toolkit
    ${TAGLIB_DIR}/taglib/ape
    ${TAGLIB_DIR}/taglib/asf
    ${TAGLIB_DIR}/taglib/dsdiff
    ${TAGLIB_DIR}/taglib/dsf
    ${TAGLIB_DIR}/taglib/flac
    ${TAGLIB_DIR}/taglib/matroska
    ${TAGLIB_DIR}/taglib/mp4
    ${TAGLIB_DIR}/taglib/mpc
    ${TAGLIB_DIR}/taglib/mpeg
    ${TAGLIB_DIR}/taglib/mpeg/id3v1
    ${TAGLIB_DIR}/taglib/mpeg/id3v2
    ${TAGLIB_DIR}/taglib/mpeg/id3v2/frames
    ${TAGLIB_DIR}/taglib/ogg
    ${TAGLIB_DIR}/taglib/ogg/flac
    ${TAGLIB_DIR}/taglib/ogg/opus
    ${TAGLIB_DIR}/taglib/ogg/speex
    ${TAGLIB_DIR}/taglib/ogg/vorbis
    ${TAGLIB_DIR}/taglib/riff
    ${TAGLIB_DIR}/taglib/riff/aiff
    ${TAGLIB_DIR}/taglib/riff/wav
    ${TAGLIB_DIR}/taglib/shorten
    ${TAGLIB_DIR}/taglib/trueaudio
    ${TAGLIB_DIR}/taglib/wavpack
    # taglib_config.h, written by TagLib's configure step.
    ${CMAKE_CURRENT_BINARY_DIR}/taglib
)
