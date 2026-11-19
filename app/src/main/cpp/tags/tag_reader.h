// The app's own tag reader: one file in, everything the library keeps about
// it out, in one flat block of bytes.
//
// It replaces Android's MediaMetadataRetriever (a call into another process
// for every file, with tags that differ by phone and Android version) and
// jaudiotagger (which finds its classes by name, so R8 can strip them in a
// release build). TagLib does the parsing; nothing here is reached by
// reflection, so there is nothing for the shrinker to break.
//
// No JNI in this file: the host tests (tags/tests) call it directly.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace tryptags {

// The block's first four bytes, little-endian: "TTG1".
constexpr uint32_t kMagic = 0x31475454u;

// A text value starts with this character when its file declared it as
// Latin-1, it holds bytes above 0x7F, and those bytes are not UTF-8: a tag
// written in some older code page (CP1251, GBK, Shift-JIS). Each byte is kept
// as the character of the same number, so the caller can get the bytes back
// and decide what they were. U+E000 is private use: no real tag begins with it.
constexpr char32_t kLegacyMark = 0xE000;

// Flags in the block's header.
constexpr uint32_t kFlagHasPicture = 1u << 0;  // the file holds a cover, read or not
constexpr uint32_t kFlagAdts = 1u << 1;        // an MPEG file that is raw AAC (ADTS)

struct Options {
    // Copy the cover's bytes into the block. Off, the header still says
    // whether there is one.
    bool picture = true;
    // No cover larger than this is copied: a malformed size must not become
    // an allocation.
    uint32_t maxPictureBytes = 32u * 1024u * 1024u;
};

// Reads the file at `path` (UTF-8, opened read-only) into `out`:
//
//   u32  magic
//   i32  length in milliseconds
//   i32  bitrate in kb/s
//   i32  sample rate in Hz
//   i32  channels
//   i32  bits per sample, 0 where the format has none
//   u32  flags
//   str  container: "MPEG" "FLAC" "MP4" "OGG_VORBIS" "OGG_OPUS" "OGG_FLAC"
//        "OGG_SPEEX" "WAV" "AIFF" "APE" "WAVPACK" "MPC" "TTA" "ASF" "DSF"
//        "DSDIFF" "MATROSKA" "SHORTEN", or "" for one TagLib opened that is
//        none of these
//   str  codec, where the container does not settle it: "AAC" "ALAC" "AC3"
//        "EAC3" "FLAC" "DTS" "OPUS" for MP4; "MP1" "MP2" "MP3" for MPEG;
//        "WMA1" "WMA2" "WMA9PRO" "WMA9LOSSLESS" for ASF; Matroska's own
//        codec id; else ""
//   u32  number of fields, then for each: str name (upper case), str value.
//        A field with several values is several entries, in the file's order.
//   str  the cover's MIME type, "" when unknown or none
//   u32  the cover's length, then its bytes
//
// where str is a u32 length and that many bytes of UTF-8, and every number is
// little-endian. False when the file cannot be opened or is not audio TagLib
// knows; `out` is then left empty. Never throws.
bool read(const char *path, const Options &options, std::vector<uint8_t> &out);

// True when `data` is well-formed UTF-8 (no overlong forms, no surrogates,
// nothing past U+10FFFF). Exposed for the tests.
bool isUtf8(const char *data, size_t size);

}  // namespace tryptags
