// SPDX-License-Identifier: GPL-3.0-or-later
#include "rar_reader.h"

#include <archive.h>
#include <archive_entry.h>

RarReader::RarReader() = default;

RarReader::~RarReader() {
    if (archive_ != nullptr) archive_read_free(archive_);
}

void RarReader::fail() {
    const char* message = archive_ != nullptr ? archive_error_string(archive_) : nullptr;
    error_ = message != nullptr ? message : "unreadable archive";
}

bool RarReader::open(const char* path) {
    archive_ = archive_read_new();
    if (archive_ == nullptr) {
        error_ = "out of memory";
        return false;
    }
    // RAR only: anything else is read in Kotlin, or not at all.
    archive_read_support_format_rar(archive_);
    archive_read_support_format_rar5(archive_);
    if (archive_read_open_filename(archive_, path, 64 * 1024) != ARCHIVE_OK) {
        fail();
        return false;
    }
    return true;
}

bool RarReader::next(std::string& name, int64_t& size) {
    if (archive_ == nullptr) return false;
    while (true) {
        struct archive_entry* entry = nullptr;
        int status = archive_read_next_header(archive_, &entry);
        if (status == ARCHIVE_EOF) return false;
        // A warning still gives a usable entry; anything worse ends the read.
        if (status < ARCHIVE_WARN || entry == nullptr) {
            fail();
            return false;
        }
        if (archive_entry_filetype(entry) != AE_IFREG) continue;
        if (archive_entry_hardlink(entry) != nullptr) continue;
        if (archive_entry_is_data_encrypted(entry) == 1) continue;
        const char* utf8 = archive_entry_pathname_utf8(entry);
        const char* path = utf8 != nullptr ? utf8 : archive_entry_pathname(entry);
        if (path == nullptr || *path == '\0') continue;
        name.assign(path);
        size = archive_entry_size_is_set(entry) ? archive_entry_size(entry) : -1;
        return true;
    }
}

long RarReader::read(void* buffer, size_t length) {
    if (archive_ == nullptr) return -1;
    la_ssize_t got = archive_read_data(archive_, buffer, length);
    if (got < 0) {
        fail();
        return -1;
    }
    return static_cast<long>(got);
}
