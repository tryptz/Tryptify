// SPDX-License-Identifier: GPL-3.0-or-later
//
// RarReader over libarchive's RAR readers, on libarchive's own test archives
// (fixtures/, decoded from its libarchive/test/*.rar.uu, BSD-2-Clause): RAR4
// and RAR5, a solid RAR5 read with files passed over as StyleArchive passes
// them, and what is not a RAR or is cut short failing with a reason, not a
// crash.

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "rar_reader.h"

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond)                                                             \
    do {                                                                        \
        ++g_checks;                                                             \
        if (!(cond)) {                                                          \
            ++g_failures;                                                       \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
        }                                                                       \
    } while (0)

static std::string g_fixtures;

static std::string fixture(const char* name) { return g_fixtures + "/" + name; }

/** The current file, read to its end in small pieces; false on an error. */
static bool readAll(RarReader& reader, std::vector<uint8_t>& out) {
    out.clear();
    uint8_t buffer[333];
    while (true) {
        long got = reader.read(buffer, sizeof buffer);
        if (got < 0) return false;
        if (got == 0) return true;
        out.insert(out.end(), buffer, buffer + got);
    }
}

/** libarchive's RAR5 test data: little-endian words k*k - 3k + 1 + magic, from k = 1, never below 0. */
static bool isTestData(const std::vector<uint8_t>& data, int magic) {
    for (size_t i = 0; i + 4 <= data.size(); i += 4) {
        int64_t k = static_cast<int64_t>(i / 4) + 1;
        int64_t value = k * k - 3 * k + 1 + magic;
        if (value < 0) value = 0;
        uint32_t word = data[i] | (data[i + 1] << 8) | (data[i + 2] << 16) | (static_cast<uint32_t>(data[i + 3]) << 24);
        if (word != static_cast<uint32_t>(value)) return false;
    }
    return true;
}

static void rar4ListsOnlyItsRegularFiles() {
    RarReader reader;
    CHECK(reader.open(fixture("rar4_basic.rar").c_str()));
    std::string name;
    int64_t size = 0;
    std::vector<uint8_t> data;
    const char text[] = "test text document\r\n";
    // test.txt, then testdir/test.txt: the symlink and the two folders between are passed over.
    CHECK(reader.next(name, size));
    CHECK(name == "test.txt");
    CHECK(size == 20);
    CHECK(readAll(reader, data));
    CHECK(data.size() == 20 && std::memcmp(data.data(), text, 20) == 0);
    CHECK(reader.next(name, size));
    CHECK(name == "testdir/test.txt");
    CHECK(readAll(reader, data));
    CHECK(data.size() == 20 && std::memcmp(data.data(), text, 20) == 0);
    CHECK(!reader.next(name, size));
    CHECK(reader.error().empty());
}

static void rar5IsUnpacked() {
    RarReader reader;
    CHECK(reader.open(fixture("rar5_compressed.rar").c_str()));
    std::string name;
    int64_t size = 0;
    std::vector<uint8_t> data;
    CHECK(reader.next(name, size));
    CHECK(name == "test.bin");
    CHECK(size == 1200);
    CHECK(readAll(reader, data));
    CHECK(data.size() == 1200);
    CHECK(isTestData(data, 0));
    CHECK(!reader.next(name, size));
    CHECK(reader.error().empty());
}

static void aSolidRar5ReadsTheFilesKeptAfterOnesPassedOver() {
    RarReader reader;
    CHECK(reader.open(fixture("rar5_solid.rar").c_str()));
    std::string name;
    int64_t size = 0;
    std::vector<uint8_t> data;
    // test1 and test2 not read, as StyleArchive leaves a file it does not keep.
    CHECK(reader.next(name, size) && name == "test1.bin");
    CHECK(reader.next(name, size) && name == "test2.bin");
    CHECK(reader.next(name, size) && name == "test3.bin");
    CHECK(size == 4096);
    CHECK(readAll(reader, data));
    CHECK(data.size() == 4096 && isTestData(data, 3));
    // Read only partly, then on to the next.
    CHECK(reader.next(name, size) && name == "test4.bin");
    uint8_t piece[100];
    CHECK(reader.read(piece, sizeof piece) == 100);
    CHECK(!reader.next(name, size));
    CHECK(reader.error().empty());
}

static void whatIsNotARarFailsWithAReason() {
    std::string path = "not_a_rar.tmp";  // in the build folder, where ctest runs
    FILE* f = std::fopen(path.c_str(), "wb");
    CHECK(f != nullptr);
    if (f != nullptr) {
        std::fputs("PK\3\4 this is no RAR at all", f);
        std::fclose(f);
    }
    RarReader reader;
    CHECK(!reader.open(path.c_str()));
    CHECK(!reader.error().empty());
    std::string name;
    int64_t size = 0;
    CHECK(!reader.next(name, size));
    std::remove(path.c_str());

    RarReader missing;
    CHECK(!missing.open(fixture("no such file.rar").c_str()));
    CHECK(!missing.error().empty());
}

static void aRarCutShortFailsWithAReason() {
    std::vector<uint8_t> whole;
    FILE* in = std::fopen(fixture("rar5_solid.rar").c_str(), "rb");
    CHECK(in != nullptr);
    if (in == nullptr) return;
    int c;
    while ((c = std::fgetc(in)) != EOF) whole.push_back(static_cast<uint8_t>(c));
    std::fclose(in);
    std::string path = "cut_short.tmp";
    FILE* out = std::fopen(path.c_str(), "wb");
    CHECK(out != nullptr);
    if (out == nullptr) return;
    std::fwrite(whole.data(), 1, whole.size() / 2, out);
    std::fclose(out);

    RarReader reader;
    bool failed = !reader.open(path.c_str());
    std::string name;
    int64_t size = 0;
    std::vector<uint8_t> data;
    while (!failed && reader.next(name, size)) {
        if (!readAll(reader, data)) failed = true;
    }
    CHECK(failed || !reader.error().empty());
    CHECK(!reader.error().empty());
    std::remove(path.c_str());
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: rar_reader_test <fixtures dir>\n");
        return 2;
    }
    g_fixtures = argv[1];
    rar4ListsOnlyItsRegularFiles();
    rar5IsUnpacked();
    aSolidRar5ReadsTheFilesKeptAfterOnesPassedOver();
    whatIsNotARarFailsWithAReason();
    aRarCutShortFailsWithAReason();
    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
