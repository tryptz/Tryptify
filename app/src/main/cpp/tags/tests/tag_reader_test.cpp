// Host tests of the app's tag reader (tag_reader.cpp) on TagLib's own test
// files. Run by ctest with two arguments: the folder of those files, and a
// folder to write scratch copies in.

#include "tag_reader.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <string>
#include <vector>

#include <sys/stat.h>

#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <wavfile.h>
#include <id3v2tag.h>
#include <infotag.h>

namespace fs = std::filesystem;

static int g_failed = 0;
static int g_checks = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) {                                                           \
            ++g_failed;                                                          \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
        }                                                                        \
    } while (0)

#define CHECK_EQ(a, b)                                                                  \
    do {                                                                                \
        ++g_checks;                                                                     \
        const auto va_ = (a);                                                           \
        const auto vb_ = (b);                                                           \
        if (!(va_ == vb_)) {                                                            \
            ++g_failed;                                                                 \
            std::fprintf(stderr, "FAIL %s:%d: %s == %s\n   got  [%s]\n   want [%s]\n",  \
                         __FILE__, __LINE__, #a, #b, show(va_).c_str(), show(vb_).c_str()); \
        }                                                                               \
    } while (0)

static std::string show(const std::string &s) { return s; }
static std::string show(int v) { return std::to_string(v); }
static std::string show(size_t v) { return std::to_string(v); }

// The block, parsed back: what the Kotlin side does.
struct Parsed {
    bool ok = false;
    int lengthMs = 0, bitrate = 0, sampleRate = 0, channels = 0, bits = 0;
    uint32_t flags = 0;
    std::string container, codec;
    std::vector<std::pair<std::string, std::string>> fields;
    std::string pictureMime;
    std::string picture;

    std::string first(const std::string &name) const {
        for (const auto &f : fields) {
            if (f.first == name) return f.second;
        }
        return {};
    }

    std::vector<std::string> all(const std::string &name) const {
        std::vector<std::string> out;
        for (const auto &f : fields) {
            if (f.first == name) out.push_back(f.second);
        }
        return out;
    }
};

class Cursor {
public:
    explicit Cursor(const std::vector<uint8_t> &b) : b_(b) {}
    bool good() const { return good_; }
    uint32_t u32() {
        if (at_ + 4 > b_.size()) {
            good_ = false;
            return 0;
        }
        const uint32_t v = b_[at_] | (b_[at_ + 1] << 8) | (b_[at_ + 2] << 16) |
                           (static_cast<uint32_t>(b_[at_ + 3]) << 24);
        at_ += 4;
        return v;
    }
    std::string str() {
        const uint32_t n = u32();
        if (!good_ || at_ + n > b_.size()) {
            good_ = false;
            return {};
        }
        std::string s(reinterpret_cast<const char *>(b_.data()) + at_, n);
        at_ += n;
        return s;
    }
    bool atEnd() const { return at_ == b_.size(); }

private:
    const std::vector<uint8_t> &b_;
    size_t at_ = 0;
    bool good_ = true;
};

static Parsed readFile(const std::string &path, bool picture = true) {
    Parsed p;
    std::vector<uint8_t> blob;
    tryptags::Options options;
    options.picture = picture;
    if (!tryptags::read(path.c_str(), options, blob)) {
        CHECK(blob.empty());
        return p;
    }
    Cursor c(blob);
    if (c.u32() != tryptags::kMagic) return p;
    p.lengthMs = static_cast<int>(c.u32());
    p.bitrate = static_cast<int>(c.u32());
    p.sampleRate = static_cast<int>(c.u32());
    p.channels = static_cast<int>(c.u32());
    p.bits = static_cast<int>(c.u32());
    p.flags = c.u32();
    p.container = c.str();
    p.codec = c.str();
    const uint32_t n = c.u32();
    for (uint32_t i = 0; i < n && c.good(); ++i) {
        std::string name = c.str();
        std::string value = c.str();
        p.fields.emplace_back(std::move(name), std::move(value));
    }
    p.pictureMime = c.str();
    p.picture = c.str();
    // The whole block is accounted for: nothing missing, nothing after.
    p.ok = c.good() && c.atEnd();
    return p;
}

static std::string g_data;
static std::string g_scratch;

static std::string data(const std::string &name) { return g_data + "/" + name; }

static std::string scratchCopy(const std::string &name, const std::string &as = {}) {
    const std::string to = g_scratch + "/" + (as.empty() ? name : as);
    fs::copy_file(data(name), to, fs::copy_options::overwrite_existing);
    fs::permissions(to, fs::perms::owner_read | fs::perms::owner_write);
    return to;
}

static const std::string kMark = "\xEE\x80\x80";  // U+E000 in UTF-8

// ── Every format opens, and is named ────────────────────────────────────────

static void testFormats() {
    struct Case {
        const char *file;
        const char *container;
        const char *codec;
    };
    const Case cases[] = {
        {"xing.mp3", "MPEG", "MP3"},
        {"lame_cbr.mp3", "MPEG", "MP3"},
        {"empty1s.aac", "MPEG", "AAC"},
        {"silence-44-s.flac", "FLAC", ""},
        {"empty.ogg", "OGG_VORBIS", ""},
        {"correctness_gain_silent_output.opus", "OGG_OPUS", ""},
        {"empty_flac.oga", "OGG_FLAC", ""},
        {"empty.spx", "OGG_SPEEX", ""},
        {"has-tags.m4a", "MP4", "AAC"},
        {"empty_alac.m4a", "MP4", "ALAC"},
        {"eac3.m4a", "MP4", "EAC3"},
        {"ac3.m4a", "MP4", "AC3"},
        {"flac.m4a", "MP4", "FLAC"},
        {"opus.m4a", "MP4", "OPUS"},
        {"empty.wav", "WAV", ""},
        {"empty.aiff", "AIFF", ""},
        {"mac-399.ape", "APE", ""},
        {"click.wv", "WAVPACK", ""},
        {"click.mpc", "MPC", ""},
        {"empty.tta", "TTA", ""},
        {"silence-1.wma", "ASF", "WMA2"},
        {"lossless.wma", "ASF", "WMA9LOSSLESS"},
        {"empty10ms.dsf", "DSF", ""},
        {"empty10ms.dff", "DSDIFF", ""},
        {"2sec-silence.shn", "SHORTEN", ""},
    };
    for (const auto &c : cases) {
        const Parsed p = readFile(data(c.file));
        if (!p.ok) {
            ++g_failed;
            std::fprintf(stderr, "FAIL: %s did not open\n", c.file);
            continue;
        }
        CHECK_EQ(p.container, std::string(c.container));
        CHECK_EQ(p.codec, std::string(c.codec));
        CHECK(p.sampleRate > 0);
        CHECK(p.channels > 0);
    }
    const Parsed mka = readFile(data("no-tags.mka"));
    CHECK(mka.ok);
    CHECK_EQ(mka.container, std::string("MATROSKA"));

    // Lossless formats say their depth; lossy ones say none.
    CHECK_EQ(readFile(data("silence-44-s.flac")).bits, 16);
    CHECK_EQ(readFile(data("empty_alac.m4a")).bits, 16);
    CHECK_EQ(readFile(data("has-tags.m4a")).bits, 0);
    CHECK_EQ(readFile(data("xing.mp3")).bits, 0);
    CHECK(readFile(data("empty.wav")).bits > 0);

    // A tracker module is not opened at all.
    CHECK(!readFile(data("test.mod")).ok);
    CHECK(!readFile(data("test.xm")).ok);
}

// ── Tags written by TagLib come back, in every format ───────────────────────

static void testRoundTrip() {
    struct Case {
        const char *file;
        bool rich;  // holds any named field, not just the format's fixed few
    };
    const Case cases[] = {
        {"xing.mp3", true},
        {"silence-44-s.flac", true},
        {"empty.ogg", true},
        {"correctness_gain_silent_output.opus", true},
        {"has-tags.m4a", true},
        {"mac-399.ape", true},
        {"click.wv", true},
        {"click.mpc", true},
        {"silence-1.wma", true},
        {"empty.aiff", true},
        {"empty10ms.dsf", true},
        {"empty.tta", true},
        {"empty.wav", true},
    };
    const std::string title = "T\xC3\xAFtle \xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E \xF0\x9F\x98\x80";  // ï, 日本語, 😀
    for (const auto &c : cases) {
        const std::string path = scratchCopy(c.file, std::string("rt-") + c.file);
        {
            TagLib::FileRef ref(path.c_str());
            if (ref.isNull()) {
                ++g_failed;
                std::fprintf(stderr, "FAIL: TagLib could not open %s to tag it\n", c.file);
                continue;
            }
            TagLib::PropertyMap map;
            map["TITLE"] = TagLib::StringList(TagLib::String(title, TagLib::String::UTF8));
            TagLib::StringList artists;
            artists.append("First Artist");
            artists.append(TagLib::String("Bj\xC3\xB6rk", TagLib::String::UTF8));
            map["ARTIST"] = artists;
            map["ALBUM"] = TagLib::StringList("The Album");
            map["ALBUMARTIST"] = TagLib::StringList("Album Artist");
            map["DATE"] = TagLib::StringList("2021");
            map["TRACKNUMBER"] = TagLib::StringList("3/12");
            map["DISCNUMBER"] = TagLib::StringList("1/2");
            map["GENRE"] = TagLib::StringList("Drum & Bass");
            if (c.rich) {
                map["COMPOSER"] = TagLib::StringList("A Composer");
                map["BPM"] = TagLib::StringList("174");
                map["INITIALKEY"] = TagLib::StringList("8A");
                map["REPLAYGAIN_TRACK_GAIN"] = TagLib::StringList("-6.54 dB");
                map["REPLAYGAIN_TRACK_PEAK"] = TagLib::StringList("0.988525");
                map["ISRC"] = TagLib::StringList("GBAYE0601498");
                map["LYRICS"] = TagLib::StringList("[00:01.00]one\n[00:02.00]two");
            }
            ref.setProperties(map);
            CHECK(ref.save());
        }
        const Parsed p = readFile(path);
        if (!p.ok) {
            ++g_failed;
            std::fprintf(stderr, "FAIL: %s did not open after tagging\n", c.file);
            continue;
        }
        std::fprintf(stderr, "  round trip %-40s %zu fields\n", c.file, p.fields.size());
        CHECK_EQ(p.first("TITLE"), title);
        CHECK_EQ(p.first("ALBUM"), std::string("The Album"));
        CHECK_EQ(p.first("ALBUMARTIST"), std::string("Album Artist"));
        CHECK_EQ(p.first("GENRE"), std::string("Drum & Bass"));
        // Both artists, in order: as two values, or as one the format joined.
        const auto artists = p.all("ARTIST");
        CHECK(!artists.empty());
        if (artists.size() == 2) {
            CHECK_EQ(artists[0], std::string("First Artist"));
            CHECK_EQ(artists[1], std::string("Bj\xC3\xB6rk"));
        } else if (artists.size() == 1) {
            CHECK(artists[0].find("First Artist") == 0);
            CHECK(artists[0].find("Bj\xC3\xB6rk") != std::string::npos);
        }
        CHECK(p.first("DATE").rfind("2021", 0) == 0);
        CHECK(p.first("TRACKNUMBER").rfind("3", 0) == 0);
        CHECK(p.first("DISCNUMBER").rfind("1", 0) == 0);
        if (c.rich) {
            CHECK_EQ(p.first("COMPOSER"), std::string("A Composer"));
            CHECK_EQ(p.first("BPM"), std::string("174"));
            CHECK_EQ(p.first("INITIALKEY"), std::string("8A"));
            CHECK_EQ(p.first("REPLAYGAIN_TRACK_GAIN"), std::string("-6.54 dB"));
            CHECK_EQ(p.first("REPLAYGAIN_TRACK_PEAK"), std::string("0.988525"));
            CHECK_EQ(p.first("ISRC"), std::string("GBAYE0601498"));
            CHECK_EQ(p.first("LYRICS"), std::string("[00:01.00]one\n[00:02.00]two"));
        }
    }
}

// ── The cover ───────────────────────────────────────────────────────────────

static void testPicture() {
    std::string cover = "\xFF\xD8\xFF\xE0";
    for (int i = 0; i < 5000; ++i) cover.push_back(static_cast<char>(i * 31));
    for (const char *name : {"xing.mp3", "silence-44-s.flac", "has-tags.m4a", "empty.ogg",
                             "correctness_gain_silent_output.opus", "mac-399.ape", "silence-1.wma"}) {
        const std::string path = scratchCopy(name, std::string("pic-") + name);
        {
            TagLib::FileRef ref(path.c_str());
            if (ref.isNull()) {
                ++g_failed;
                continue;
            }
            // A back cover first: the front one is taken wherever it sits.
            TagLib::VariantMap back;
            back["data"] = TagLib::ByteVector("not the front", 13);
            back["mimeType"] = TagLib::String("image/png");
            back["pictureType"] = TagLib::String("Back Cover");
            TagLib::VariantMap front;
            front["data"] = TagLib::ByteVector(cover.data(), static_cast<unsigned int>(cover.size()));
            front["mimeType"] = TagLib::String("image/jpeg");
            front["pictureType"] = TagLib::String("Front Cover");
            TagLib::List<TagLib::VariantMap> pictures;
            pictures.append(back);
            pictures.append(front);
            ref.setComplexProperties("PICTURE", pictures);
            CHECK(ref.save());
        }
        const Parsed p = readFile(path);
        CHECK(p.ok);
        CHECK(p.flags & tryptags::kFlagHasPicture);
        // MP4 keeps no picture type, so its first cover is the one taken.
        if (std::string(name) == "has-tags.m4a") {
            CHECK(!p.picture.empty());
        } else {
            CHECK_EQ(p.picture.size(), cover.size());
            CHECK(p.picture == cover);
            // Where the format keeps a type at all (APE keeps none).
            CHECK(p.pictureMime.empty() || p.pictureMime == "image/jpeg");
        }
        // Not asked for: said to be there, not copied.
        const Parsed without = readFile(path, /* picture = */ false);
        CHECK(without.ok);
        CHECK(without.flags & tryptags::kFlagHasPicture);
        CHECK(without.picture.empty());
        CHECK_EQ(without.first("TITLE"), p.first("TITLE"));
    }
    const Parsed none = readFile(data("silence-44-s.flac"));
    CHECK(none.ok);
}

// ── Text a tag calls Latin-1 ────────────────────────────────────────────────

static std::string frame(const char *id, const std::string &bytes) {
    std::string f(id, 4);
    const uint32_t size = static_cast<uint32_t>(bytes.size() + 1);
    f.push_back(static_cast<char>(size >> 24));
    f.push_back(static_cast<char>(size >> 16));
    f.push_back(static_cast<char>(size >> 8));
    f.push_back(static_cast<char>(size));
    f.push_back(0);
    f.push_back(0);
    f.push_back(0);  // encoding: ISO-8859-1
    f += bytes;
    return f;
}

static void testDeclaredLatin1() {
    // An ID3v2.3 tag written by hand, every frame claiming Latin-1.
    const std::string cyrillicUtf8 = "\xD0\xA1\xD0\xBF\xD0\xBB\xD0\xB8\xD0\xBD";  // Сплин
    const std::string cyrillic1251 = "\xD1\xEF\xEB\xE8\xED";                      // the same, in CP1251
    std::string frames;
    frames += frame("TIT2", "Plain title");
    frames += frame("TPE1", cyrillicUtf8);
    frames += frame("TALB", cyrillic1251);
    frames += frame("TCOM", "Beyonc\xE9");
    std::string tag = "ID3";
    tag.push_back(3);
    tag.push_back(0);
    tag.push_back(0);
    const uint32_t size = static_cast<uint32_t>(frames.size());
    tag.push_back(static_cast<char>((size >> 21) & 0x7F));
    tag.push_back(static_cast<char>((size >> 14) & 0x7F));
    tag.push_back(static_cast<char>((size >> 7) & 0x7F));
    tag.push_back(static_cast<char>(size & 0x7F));
    tag += frames;

    std::ifstream in(data("lame_cbr.mp3"), std::ios::binary);
    std::string audio((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (audio.rfind("ID3", 0) == 0 && audio.size() > 10) {
        // The fixture's own tag comes off, so the one above is the file's only one.
        const auto b = [&](int i) { return static_cast<uint32_t>(static_cast<unsigned char>(audio[i])) & 0x7Fu; };
        audio.erase(0, 10 + ((b(6) << 21) | (b(7) << 14) | (b(8) << 7) | b(9)));
    }
    CHECK(audio.rfind("ID3", 0) != 0);
    const std::string path = g_scratch + "/latin1.mp3";
    {
        std::ofstream out(path, std::ios::binary);
        out << tag << audio;
    }
    const Parsed p = readFile(path);
    CHECK(p.ok);
    // ASCII: as it is.
    CHECK_EQ(p.first("TITLE"), std::string("Plain title"));
    // UTF-8 the tagger did not declare: read as UTF-8.
    CHECK_EQ(p.first("ARTIST"), cyrillicUtf8);
    // An older code page: marked, each byte kept as the character of its number.
    CHECK_EQ(p.first("ALBUM"), kMark + "\xC3\x91\xC3\xAF\xC3\xAB\xC3\xA8\xC3\xAD");
    // Real Latin-1 is marked too: only the caller can tell it from a code page.
    CHECK_EQ(p.first("COMPOSER"), kMark + "Beyonc\xC3\xA9");

    CHECK(tryptags::isUtf8("", 0));
    CHECK(tryptags::isUtf8("abc", 3));
    CHECK(tryptags::isUtf8("\xC3\xA9", 2));
    CHECK(tryptags::isUtf8("\xF0\x9F\x98\x80", 4));
    CHECK(!tryptags::isUtf8("\xE9", 1));                  // a lone Latin-1 é
    CHECK(!tryptags::isUtf8("Beyonc\xE9", 7));
    CHECK(!tryptags::isUtf8("\xC3", 1));                  // cut short
    CHECK(!tryptags::isUtf8("\xC0\xAF", 2));              // overlong
    CHECK(!tryptags::isUtf8("\xED\xA0\x80", 3));          // a surrogate
    CHECK(!tryptags::isUtf8("\xF4\x90\x80\x80", 4));      // past U+10FFFF
    CHECK(!tryptags::isUtf8("\xD1\xEF\xEB\xE8\xED", 5));  // CP1251
}

// ── A WAV's two tags ────────────────────────────────────────────────────────

static void testWavBothTags() {
    const std::string path = scratchCopy("empty.wav", "both.wav");
    {
        TagLib::RIFF::WAV::File wav(path.c_str());
        CHECK(wav.isValid());
        wav.ID3v2Tag()->setTitle("From ID3");
        wav.InfoTag()->setTitle("From INFO");
        wav.InfoTag()->setArtist("Only in INFO");
        CHECK(wav.save());
    }
    const Parsed p = readFile(path);
    CHECK(p.ok);
    CHECK_EQ(p.all("TITLE").size(), static_cast<size_t>(1));
    CHECK_EQ(p.first("TITLE"), std::string("From ID3"));
    CHECK_EQ(p.first("ARTIST"), std::string("Only in INFO"));
}

// ── Nothing is written, and nothing crashes ─────────────────────────────────

static void testReadOnly() {
    const std::string path = scratchCopy("xing.mp3", "readonly.mp3");
    fs::permissions(path, fs::perms::owner_read);
    struct stat before {};
    CHECK(stat(path.c_str(), &before) == 0);
    CHECK(readFile(path).ok);
    struct stat after {};
    CHECK(stat(path.c_str(), &after) == 0);
    CHECK(before.st_mtime == after.st_mtime);
    CHECK(before.st_size == after.st_size);
    fs::permissions(path, fs::perms::owner_read | fs::perms::owner_write);
}

static void testNeverCrashes() {
    // Every file TagLib's own tests keep, the broken ones above all
    // (segfault.*, infloop.*, excessive_alloc.*, garbage.mp3): each is read,
    // or refused, and the block of one that is read parses to its end.
    int opened = 0;
    int refused = 0;
    for (const auto &entry : fs::directory_iterator(g_data)) {
        if (!entry.is_regular_file()) continue;
        std::vector<uint8_t> blob;
        if (tryptags::read(entry.path().c_str(), tryptags::Options(), blob)) {
            ++opened;
            const Parsed p = readFile(entry.path().string());
            if (!p.ok) {
                ++g_failed;
                std::fprintf(stderr, "FAIL: %s read to a block that does not parse\n",
                             entry.path().filename().c_str());
            }
        } else {
            ++refused;
        }
    }
    std::fprintf(stderr, "  %d files read, %d refused\n", opened, refused);
    CHECK(opened > 80);

    CHECK(!readFile(g_scratch + "/no-such-file.mp3").ok);
    CHECK(!readFile(g_scratch).ok);  // a folder
    const std::string empty = g_scratch + "/empty.flac";
    { std::ofstream out(empty, std::ios::binary); }
    CHECK(!readFile(empty).ok);
    std::vector<uint8_t> blob;
    CHECK(!tryptags::read(nullptr, tryptags::Options(), blob));
    CHECK(!tryptags::read("", tryptags::Options(), blob));

    // A name that is not what the file is: read by what it holds.
    const std::string misnamed = scratchCopy("silence-44-s.flac", "really-a-flac.mp3");
    const Parsed p = readFile(misnamed);
    CHECK(p.ok);
    CHECK_EQ(p.container, std::string("FLAC"));
    CHECK_EQ(p.sampleRate, 44100);
    const Parsed m4aAsFlac = readFile(scratchCopy("has-tags.m4a", "really-an-m4a.flac"));
    CHECK(m4aAsFlac.ok);
    CHECK_EQ(m4aAsFlac.container, std::string("MP4"));
    // No extension at all (the play cache's ".bin"): by what it holds, too.
    const Parsed bin = readFile(scratchCopy("empty.ogg", "stream.bin"));
    CHECK(bin.ok);
    CHECK_EQ(bin.container, std::string("OGG_VORBIS"));

    // A path with characters outside the basic plane.
    const std::string emoji = scratchCopy("xing.mp3", "\xF0\x9F\x8E\xB5 \xE6\x9B\xB2.mp3");
    CHECK(readFile(emoji).ok);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <taglib tests/data> <scratch folder>\n", argv[0]);
        return 2;
    }
    g_data = argv[1];
    g_scratch = std::string(argv[2]) + "/tag-reader-scratch";
    fs::remove_all(g_scratch);
    fs::create_directories(g_scratch);

    testFormats();
    testRoundTrip();
    testPicture();
    testDeclaredLatin1();
    testWavBothTags();
    testReadOnly();
    testNeverCrashes();

    if (g_failed != 0) {
        std::fprintf(stderr, "%d of %d checks FAILED\n", g_failed, g_checks);
        return 1;
    }
    std::printf("tag_reader_test: all %d checks passed\n", g_checks);
    return 0;
}
