// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

struct archive;

/**
 * Reads a RAR (RAR4 or RAR5) file by file, through libarchive's readers.
 * Only regular files are listed: folders, links and encrypted files are
 * passed over. What is kept, where it goes and how big it may be is decided
 * by the caller (StyleArchive in Kotlin), which reads each file it keeps
 * through read() and leaves the rest unread.
 */
class RarReader {
public:
    RarReader();
    ~RarReader();
    RarReader(const RarReader&) = delete;
    RarReader& operator=(const RarReader&) = delete;

    /** Opens [path]; false with error() set when it is not a RAR it can read. */
    bool open(const char* path);

    /**
     * Moves to the next regular file. Fills [name] (UTF-8, as stored) and
     * [size] (its unpacked size as the archive declares it, -1 if unknown).
     * False at the end, or on a damaged archive with error() set.
     */
    bool next(std::string& name, int64_t& size);

    /** Reads the current file: bytes read, 0 at its end, -1 on an error (error() says which). */
    long read(void* buffer, size_t length);

    /** The last error, empty when none. */
    const std::string& error() const { return error_; }

private:
    void fail();
    struct archive* archive_ = nullptr;
    std::string error_;
};
