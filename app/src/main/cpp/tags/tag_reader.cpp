#include "tag_reader.h"

#include <cctype>
#include <cstring>
#include <mutex>

#include <aifffile.h>
#include <apefile.h>
#include <apeproperties.h>
#include <asffile.h>
#include <asfproperties.h>
#include <dsdifffile.h>
#include <dsdiffproperties.h>
#include <dsffile.h>
#include <dsfproperties.h>
#include <fileref.h>
#include <flacfile.h>
#include <flacproperties.h>
#include <id3v1tag.h>
#include <id3v2tag.h>
#include <infotag.h>
#include <matroskafile.h>
#include <matroskaproperties.h>
#include <mp4file.h>
#include <mp4properties.h>
#include <mpcfile.h>
#include <mpegfile.h>
#include <mpegproperties.h>
#include <oggflacfile.h>
#include <opusfile.h>
#include <shortenfile.h>
#include <shortenproperties.h>
#include <speexfile.h>
#include <tfilestream.h>
#include <tpropertymap.h>
#include <trueaudiofile.h>
#include <trueaudioproperties.h>
#include <tvariant.h>
#include <vorbisfile.h>
#include <wavfile.h>
#include <wavpackfile.h>
#include <wavpackproperties.h>
#include <wavproperties.h>
#include <aiffproperties.h>

namespace tryptags {

bool isUtf8(const char *data, size_t size) {
    const auto *p = reinterpret_cast<const unsigned char *>(data);
    const unsigned char *end = p + size;
    while (p < end) {
        const unsigned char c = *p;
        if (c < 0x80) {
            ++p;
            continue;
        }
        int extra;
        uint32_t cp;
        uint32_t min;
        if ((c & 0xE0) == 0xC0) {
            extra = 1; cp = c & 0x1Fu; min = 0x80;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2; cp = c & 0x0Fu; min = 0x800;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3; cp = c & 0x07u; min = 0x10000;
        } else {
            return false;
        }
        if (end - p <= extra) return false;
        for (int i = 1; i <= extra; ++i) {
            if ((p[i] & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (p[i] & 0x3Fu);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        p += extra + 1;
    }
    return true;
}

namespace {

// What a tag calls Latin-1 is, in files that have seen many taggers, one of
// three things: Latin-1, UTF-8 written by a tagger that did not say so, or an
// older code page. The first two are settled here; the third is marked for
// the caller, which knows the phone's language and has a detector.
TagLib::String decodeDeclaredLatin1(const TagLib::ByteVector &data) {
    bool high = false;
    for (const char c : data) {
        if (static_cast<unsigned char>(c) >= 0x80) {
            high = true;
            break;
        }
    }
    if (!high) return TagLib::String(data, TagLib::String::Latin1);
    if (isUtf8(data.data(), data.size())) return TagLib::String(data, TagLib::String::UTF8);
    TagLib::String marked(L"\uE000");
    marked.append(TagLib::String(data, TagLib::String::Latin1));
    return marked;
}

class Id3v2Latin1 : public TagLib::ID3v2::Latin1StringHandler {
public:
    TagLib::String parse(const TagLib::ByteVector &data) const override {
        return decodeDeclaredLatin1(data);
    }
};

class Id3v1Strings : public TagLib::ID3v1::StringHandler {
public:
    TagLib::String parse(const TagLib::ByteVector &data) const override {
        return decodeDeclaredLatin1(data).stripWhiteSpace();
    }
};

// A RIFF INFO chunk has no declared encoding at all; TagLib's own handler
// takes it for UTF-8, and a file from an older Windows tagger then loses
// every accented letter.
class InfoStrings : public TagLib::RIFF::Info::StringHandler {
public:
    TagLib::String parse(const TagLib::ByteVector &data) const override {
        return decodeDeclaredLatin1(data);
    }
};

void installHandlers() {
    static std::once_flag once;
    std::call_once(once, [] {
        // Leaked on purpose: TagLib keeps the pointers for the process's life.
        TagLib::ID3v2::Tag::setLatin1StringHandler(new Id3v2Latin1());
        TagLib::ID3v1::Tag::setStringHandler(new Id3v1Strings());
        TagLib::RIFF::Info::Tag::setStringHandler(new InfoStrings());
    });
}

class Blob {
public:
    explicit Blob(std::vector<uint8_t> &out) : out_(out) {}

    void u32(uint32_t v) {
        const uint8_t b[4] = {
            static_cast<uint8_t>(v), static_cast<uint8_t>(v >> 8),
            static_cast<uint8_t>(v >> 16), static_cast<uint8_t>(v >> 24),
        };
        out_.insert(out_.end(), b, b + 4);
    }

    void i32(int v) { u32(static_cast<uint32_t>(v)); }

    void bytes(const char *data, size_t size) {
        u32(static_cast<uint32_t>(size));
        out_.insert(out_.end(), data, data + size);
    }

    void str(const std::string &s) { bytes(s.data(), s.size()); }

    void str(const TagLib::String &s) { str(s.to8Bit(true)); }

    size_t reserveU32() {
        const size_t at = out_.size();
        u32(0);
        return at;
    }

    void setU32(size_t at, uint32_t v) {
        out_[at] = static_cast<uint8_t>(v);
        out_[at + 1] = static_cast<uint8_t>(v >> 8);
        out_[at + 2] = static_cast<uint8_t>(v >> 16);
        out_[at + 3] = static_cast<uint8_t>(v >> 24);
    }

private:
    std::vector<uint8_t> &out_;
};

struct Stream {
    const char *container = "";
    std::string codec;
    int bits = 0;
    bool adts = false;
};

Stream describe(TagLib::File *file) {
    Stream s;
    const TagLib::AudioProperties *props = file->audioProperties();
    if (auto *f = dynamic_cast<TagLib::MPEG::File *>(file)) {
        s.container = "MPEG";
        if (const auto *p = dynamic_cast<const TagLib::MPEG::Properties *>(props)) {
            s.adts = p->isADTS();
            if (s.adts) {
                s.codec = "AAC";
            } else {
                switch (p->layer()) {
                    case 1: s.codec = "MP1"; break;
                    case 2: s.codec = "MP2"; break;
                    case 3: s.codec = "MP3"; break;
                    default: break;
                }
            }
        }
        (void) f;
    } else if (dynamic_cast<TagLib::FLAC::File *>(file)) {
        s.container = "FLAC";
        if (const auto *p = dynamic_cast<const TagLib::FLAC::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::MP4::File *>(file)) {
        s.container = "MP4";
        if (const auto *p = dynamic_cast<const TagLib::MP4::Properties *>(props)) {
            switch (p->codec()) {
                case TagLib::MP4::Properties::AAC: s.codec = "AAC"; break;
                case TagLib::MP4::Properties::ALAC: s.codec = "ALAC"; break;
                case TagLib::MP4::Properties::AC3: s.codec = "AC3"; break;
                case TagLib::MP4::Properties::EAC3: s.codec = "EAC3"; break;
                case TagLib::MP4::Properties::FLAC: s.codec = "FLAC"; break;
                case TagLib::MP4::Properties::DTS: s.codec = "DTS"; break;
                case TagLib::MP4::Properties::Opus: s.codec = "OPUS"; break;
                default: break;
            }
            // Lossy audio has no bit depth; the sample entry's 16 is a
            // placeholder there, not a fact.
            if (p->codec() == TagLib::MP4::Properties::ALAC || p->codec() == TagLib::MP4::Properties::FLAC) {
                s.bits = p->bitsPerSample();
            }
        }
    } else if (dynamic_cast<TagLib::Ogg::Vorbis::File *>(file)) {
        s.container = "OGG_VORBIS";
    } else if (dynamic_cast<TagLib::Ogg::Opus::File *>(file)) {
        s.container = "OGG_OPUS";
    } else if (dynamic_cast<TagLib::Ogg::FLAC::File *>(file)) {
        s.container = "OGG_FLAC";
        if (const auto *p = dynamic_cast<const TagLib::FLAC::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::Ogg::Speex::File *>(file)) {
        s.container = "OGG_SPEEX";
    } else if (dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
        s.container = "WAV";
        if (const auto *p = dynamic_cast<const TagLib::RIFF::WAV::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::RIFF::AIFF::File *>(file)) {
        s.container = "AIFF";
        if (const auto *p = dynamic_cast<const TagLib::RIFF::AIFF::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::APE::File *>(file)) {
        s.container = "APE";
        if (const auto *p = dynamic_cast<const TagLib::APE::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::WavPack::File *>(file)) {
        s.container = "WAVPACK";
        if (const auto *p = dynamic_cast<const TagLib::WavPack::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::MPC::File *>(file)) {
        s.container = "MPC";
    } else if (dynamic_cast<TagLib::TrueAudio::File *>(file)) {
        s.container = "TTA";
        if (const auto *p = dynamic_cast<const TagLib::TrueAudio::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::ASF::File *>(file)) {
        s.container = "ASF";
        if (const auto *p = dynamic_cast<const TagLib::ASF::Properties *>(props)) {
            switch (p->codec()) {
                case TagLib::ASF::Properties::WMA1: s.codec = "WMA1"; break;
                case TagLib::ASF::Properties::WMA2: s.codec = "WMA2"; break;
                case TagLib::ASF::Properties::WMA9Pro: s.codec = "WMA9PRO"; break;
                case TagLib::ASF::Properties::WMA9Lossless:
                    s.codec = "WMA9LOSSLESS";
                    s.bits = p->bitsPerSample();
                    break;
                default: break;
            }
        }
    } else if (dynamic_cast<TagLib::DSF::File *>(file)) {
        s.container = "DSF";
        if (const auto *p = dynamic_cast<const TagLib::DSF::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::DSDIFF::File *>(file)) {
        s.container = "DSDIFF";
        if (const auto *p = dynamic_cast<const TagLib::DSDIFF::Properties *>(props)) s.bits = p->bitsPerSample();
    } else if (dynamic_cast<TagLib::Matroska::File *>(file)) {
        s.container = "MATROSKA";
        if (const auto *p = dynamic_cast<const TagLib::Matroska::Properties *>(props)) {
            s.codec = p->codecName().to8Bit(true);
            s.bits = p->bitsPerSample();
        }
    } else if (dynamic_cast<TagLib::Shorten::File *>(file)) {
        s.container = "SHORTEN";
        if (const auto *p = dynamic_cast<const TagLib::Shorten::Properties *>(props)) s.bits = p->bitsPerSample();
    }
    if (s.bits < 0) s.bits = 0;
    return s;
}

uint32_t writeFields(Blob &blob, const TagLib::PropertyMap &map, TagLib::PropertyMap *seen) {
    uint32_t count = 0;
    for (const auto &[name, values] : map) {
        if (name.isEmpty()) continue;
        // A second tag in the file only fills in what the first left out.
        if (seen != nullptr && seen->contains(name)) continue;
        const TagLib::String key = name.upper();
        for (const auto &value : values) {
            if (value.isEmpty()) continue;
            blob.str(key);
            blob.str(value);
            ++count;
        }
    }
    return count;
}

// The front cover when the file names one, else its first picture.
const TagLib::VariantMap *pickPicture(const TagLib::List<TagLib::VariantMap> &pictures) {
    const TagLib::VariantMap *first = nullptr;
    for (const auto &picture : pictures) {
        const auto data = picture.find("data");
        if (data == picture.end() || data->second.type() != TagLib::Variant::ByteVector) continue;
        if (data->second.value<TagLib::ByteVector>().isEmpty()) continue;
        if (first == nullptr) first = &picture;
        const auto type = picture.find("pictureType");
        if (type != picture.end() && type->second.type() == TagLib::Variant::String &&
            type->second.value<TagLib::String>() == "Front Cover") {
            return &picture;
        }
    }
    return first;
}

// What a file's first bytes say it is, or null when they do not say: an MP3
// or raw AAC file has no signature, and one that starts with an ID3 tag could
// be several things.
const char *familyOfContent(const unsigned char *b, size_t n) {
    const auto at = [&](size_t i, const char *s) {
        const size_t len = std::strlen(s);
        return n >= i + len && std::memcmp(b + i, s, len) == 0;
    };
    if (at(0, "fLaC")) return "flac";
    if (at(0, "OggS")) return "ogg";
    if (at(4, "ftyp")) return "mp4";
    if (at(0, "RIFF") && at(8, "WAVE")) return "wav";
    if (at(0, "RF64") && at(8, "WAVE")) return "wav";
    if (at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC"))) return "aiff";
    if (at(0, "MAC ")) return "ape";
    if (at(0, "wvpk")) return "wv";
    if (at(0, "MPCK") || at(0, "MP+")) return "mpc";
    if (at(0, "TTA")) return "tta";
    if (at(0, "DSD ")) return "dsf";
    if (at(0, "FRM8")) return "dff";
    if (at(0, "ajkg")) return "shn";
    if (at(0, "\x30\x26\xB2\x75")) return "asf";
    if (at(0, "\x1A\x45\xDF\xA3")) return "mka";
    return nullptr;
}

// What a file's name says it is, in the same words; null for a name TagLib
// does not go by.
const char *familyOfName(const std::string &path) {
    const size_t dot = path.rfind('.');
    const size_t slash = path.rfind('/');
    if (dot == std::string::npos || (slash != std::string::npos && dot < slash)) return nullptr;
    std::string ext = path.substr(dot + 1);
    for (char &c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    static const struct { const char *ext; const char *family; } kNames[] = {
        {"mp3", "mpeg"}, {"mp2", "mpeg"}, {"aac", "mpeg"},
        {"flac", "flac"},
        {"ogg", "ogg"}, {"oga", "ogg"}, {"spx", "ogg"}, {"opus", "ogg"},
        {"m4a", "mp4"}, {"m4r", "mp4"}, {"m4b", "mp4"}, {"m4p", "mp4"}, {"mp4", "mp4"},
        {"3g2", "mp4"}, {"m4v", "mp4"},
        {"wav", "wav"},
        {"aif", "aiff"}, {"aiff", "aiff"}, {"afc", "aiff"}, {"aifc", "aiff"},
        {"ape", "ape"}, {"wv", "wv"}, {"mpc", "mpc"}, {"tta", "tta"},
        {"dsf", "dsf"}, {"dff", "dff"}, {"dsdiff", "dff"}, {"shn", "shn"},
        {"wma", "asf"}, {"asf", "asf"},
        {"mka", "mka"}, {"mkv", "mka"}, {"webm", "mka"},
    };
    for (const auto &name : kNames) {
        if (ext == name.ext) return name.family;
    }
    return nullptr;
}

// A file opened read-only that can answer to another name. TagLib picks a
// parser by the name's extension and only looks inside the file when the
// extension tells it nothing, so a FLAC saved as ".mp3" was parsed as an MP3
// and came back with no tags and no length. When the first bytes plainly say
// the name is wrong, the stream gives a name without an extension, and TagLib
// goes by what the file holds.
class NamedStream : public TagLib::FileStream {
public:
    explicit NamedStream(const char *path)
        // Read-only, always. TagLib's own file-name constructor asks for
        // write access first, and on Android a file closed after being open
        // for writing is handed to the media indexer again: every scan would
        // tell the system each song had changed.
        : TagLib::FileStream(path, /* openReadOnly = */ true), path_(path) {}

    void renameIfMisnamed() {
        const TagLib::ByteVector head = readBlock(16);
        seek(0);
        const char *content = familyOfContent(
            reinterpret_cast<const unsigned char *>(head.data()), head.size());
        const char *name = familyOfName(path_);
        if (content != nullptr && name != nullptr && std::strcmp(content, name) != 0) misnamed_ = true;
    }

    TagLib::FileName name() const override {
        return misnamed_ ? TagLib::FileName("content") : TagLib::FileStream::name();
    }

private:
    std::string path_;
    bool misnamed_ = false;
};

bool readInto(const char *path, const Options &options, std::vector<uint8_t> &out) {
    installHandlers();

    NamedStream stream(path);
    if (!stream.isOpen()) return false;
    stream.renameIfMisnamed();
    TagLib::FileRef ref(&stream, /* readAudioProperties = */ true, TagLib::AudioProperties::Average);
    TagLib::File *file = ref.file();
    if (ref.isNull() || file == nullptr || !file->isValid()) return false;

    const TagLib::AudioProperties *props = file->audioProperties();
    const Stream stream_ = describe(file);

    const bool hasPicture = file->complexPropertyKeys().contains("PICTURE");
    uint32_t flags = 0;
    if (hasPicture) flags |= kFlagHasPicture;
    if (stream_.adts) flags |= kFlagAdts;

    Blob blob(out);
    blob.u32(kMagic);
    blob.i32(props != nullptr ? props->lengthInMilliseconds() : 0);
    blob.i32(props != nullptr ? props->bitrate() : 0);
    blob.i32(props != nullptr ? props->sampleRate() : 0);
    blob.i32(props != nullptr ? props->channels() : 0);
    blob.i32(stream_.bits);
    blob.u32(flags);
    blob.str(std::string(stream_.container));
    blob.str(stream_.codec);

    const size_t countAt = blob.reserveU32();
    uint32_t count = 0;
    if (auto *wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) {
        // A WAV may carry an ID3 chunk, a LIST/INFO chunk, or both saying
        // different amounts. ID3 first, then whatever only INFO has.
        TagLib::PropertyMap id3;
        if (wav->hasID3v2Tag() && wav->ID3v2Tag() != nullptr) id3 = wav->ID3v2Tag()->properties();
        count += writeFields(blob, id3, nullptr);
        if (wav->hasInfoTag() && wav->InfoTag() != nullptr) {
            count += writeFields(blob, wav->InfoTag()->properties(), &id3);
        }
    } else {
        count += writeFields(blob, file->properties(), nullptr);
    }
    blob.setU32(countAt, count);

    bool wrotePicture = false;
    if (hasPicture && options.picture) {
        const auto pictures = file->complexProperties("PICTURE");
        if (const TagLib::VariantMap *picture = pickPicture(pictures)) {
            const auto data = picture->find("data")->second.value<TagLib::ByteVector>();
            if (data.size() <= options.maxPictureBytes) {
                const auto mime = picture->find("mimeType");
                blob.str(mime != picture->end() && mime->second.type() == TagLib::Variant::String
                             ? mime->second.value<TagLib::String>()
                             : TagLib::String());
                blob.bytes(data.data(), data.size());
                wrotePicture = true;
            }
        }
    }
    if (!wrotePicture) {
        blob.str(std::string());
        blob.u32(0);
    }
    return true;
}

}  // namespace

bool read(const char *path, const Options &options, std::vector<uint8_t> &out) {
    out.clear();
    if (path == nullptr || *path == '\0') return false;
    try {
        if (readInto(path, options, out)) return true;
    } catch (...) {
        // A size in a broken file that turned into an allocation, mostly.
    }
    out.clear();
    return false;
}

}  // namespace tryptags
