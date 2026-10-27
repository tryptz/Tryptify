// Host test for the libusb UAC driver's alt-setting match and iso pacing.
//
// libusb itself is stubbed: each case hands the driver a parsed config
// descriptor, as libusb would, and completes the driver's own transfers
// by calling their callbacks. Everything else is the real driver.
//
// The Apple USB-C to 3.5mm adapter (05ac:110a) is the reference device.
// It has three configurations: UAC2, UAC3 BADD Headphone and full UAC3.
// The kernel picks the first UAC3 one, so on Android it runs as BADD:
// no class-specific descriptors at all, format implied by
// wMaxPacketSize, 48 kHz only. Its endpoints are synchronous full-speed
// and sized exactly to the rate (48 frames × 6 bytes = 288), which the
// pump's old unconditional headroom frame could not fit, so even the
// UAC2 configuration was refused. Descriptors below follow its lsusb -v.

#include <libusb.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include "libusb_uac_driver.h"

using monotrypt::usb::LibusbUacDriver;
using monotrypt::usb::StartError;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

// --- the fake bus ---------------------------------------------------------

libusb_config_descriptor* gConfig = nullptr;
int gSpeed = LIBUSB_SPEED_FULL;
std::deque<libusb_transfer*> gPending;   // submitted, not yet completed
// A wedged device: cancel is accepted but nothing completes until the
// test says so, long after the driver stopped waiting.
bool gStallCancel = false;
std::vector<libusb_transfer*> gStalled;
int gClockSetCount = 0;
int gLastAlt = -1;

// Feature Units the fake DAC answers for, by unit ID. Each starts the
// way a DAC left by Android might: attenuated and muted.
struct FakeUnit {
    int16_t minDb256 = -127 * 256, maxDb256 = 0;
    std::map<int, int16_t> volume;   // by channel
    std::map<int, uint8_t> muted;
    bool touched = false;
    bool stalls = false;   // a DAC that STALLs every request to it
};
std::map<int, FakeUnit> gUnits;

void resetUnits(std::initializer_list<std::pair<int, int16_t>> units) {
    gUnits.clear();
    for (const auto& u : units) {
        FakeUnit fu;
        fu.maxDb256 = u.second;
        for (int c = 0; c <= 2; ++c) {
            fu.volume[c] = -20 * 256;
            fu.muted[c] = 1;
        }
        gUnits[u.first] = fu;
    }
}

} // namespace

extern "C" {
int LIBUSB_CALL libusb_init(libusb_context** ctx) { *ctx = reinterpret_cast<libusb_context*>(1); return 0; }
void LIBUSB_CALL libusb_exit(libusb_context*) {}
int libusb_set_option(libusb_context*, enum libusb_option, ...) { return 0; }
int LIBUSB_CALL libusb_wrap_sys_device(libusb_context*, intptr_t, libusb_device_handle** h) {
    *h = reinterpret_cast<libusb_device_handle*>(1);
    return 0;
}
void LIBUSB_CALL libusb_close(libusb_device_handle*) {}
int LIBUSB_CALL libusb_set_auto_detach_kernel_driver(libusb_device_handle*, int) { return 0; }
libusb_device* LIBUSB_CALL libusb_get_device(libusb_device_handle*) { return reinterpret_cast<libusb_device*>(1); }
int LIBUSB_CALL libusb_get_device_speed(libusb_device*) { return gSpeed; }
int LIBUSB_CALL libusb_get_active_config_descriptor(libusb_device*, libusb_config_descriptor** c) { *c = gConfig; return 0; }
int LIBUSB_CALL libusb_get_config_descriptor(libusb_device*, uint8_t, libusb_config_descriptor** c) { *c = gConfig; return 0; }
void LIBUSB_CALL libusb_free_config_descriptor(libusb_config_descriptor*) {}
int LIBUSB_CALL libusb_claim_interface(libusb_device_handle*, int) { return 0; }
int LIBUSB_CALL libusb_release_interface(libusb_device_handle*, int) { return 0; }
int LIBUSB_CALL libusb_set_interface_alt_setting(libusb_device_handle*, int, int alt) { gLastAlt = alt; return 0; }
// Feature Unit requests go to gUnits. Any other SET (a clock's rate, a
// UAC1 endpoint's rate) is accepted and any other GET stalls.
int LIBUSB_CALL libusb_control_transfer(libusb_device_handle*, uint8_t type, uint8_t req,
                                        uint16_t value, uint16_t index, unsigned char* data,
                                        uint16_t len, unsigned int) {
    const bool get = (type & 0x80) != 0;
    const bool toInterface = (type & 0x1F) == 0x01;
    auto unit = gUnits.find(index >> 8);
    if (!toInterface || unit == gUnits.end()) {
        if (get) return LIBUSB_ERROR_PIPE;
        ++gClockSetCount;
        return len;
    }
    FakeUnit& fu = unit->second;
    if (fu.stalls) return LIBUSB_ERROR_PIPE;
    const int selector = value >> 8, channel = value & 0xFF;
    auto put16 = [](unsigned char* q, int16_t v) { q[0] = v & 0xFF; q[1] = (v >> 8) & 0xFF; };
    if (selector == 0x01) {                       // mute
        if (get) { data[0] = fu.muted[channel]; return 1; }
        fu.muted[channel] = data[0];
        fu.touched = true;
        return 1;
    }
    if (selector != 0x02) return LIBUSB_ERROR_PIPE;
    if (!get && req == 0x01) {                    // SET_CUR volume
        int16_t v = static_cast<int16_t>(data[0] | (data[1] << 8));
        if (v < fu.minDb256 || v > fu.maxDb256) return LIBUSB_ERROR_PIPE;
        fu.volume[channel] = v;
        fu.touched = true;
        return 2;
    }
    switch (req) {
        case 0x01: case 0x81:                     // CUR (UAC2/3), GET_CUR (UAC1)
            put16(data, fu.volume[channel]);
            return 2;
        case 0x02:                                // RANGE: one subrange
            if (len < 8) return LIBUSB_ERROR_OVERFLOW;
            put16(data, 1);
            put16(data + 2, fu.minDb256);
            put16(data + 4, fu.maxDb256);
            put16(data + 6, 256);
            return 8;
        case 0x83:                                // UAC1 GET_MAX
            put16(data, fu.maxDb256);
            return 2;
        default:
            return LIBUSB_ERROR_PIPE;
    }
}
const char* LIBUSB_CALL libusb_strerror(int) { return "stub"; }
libusb_transfer* LIBUSB_CALL libusb_alloc_transfer(int packets) {
    return static_cast<libusb_transfer*>(std::calloc(
        1, sizeof(libusb_transfer) + packets * sizeof(libusb_iso_packet_descriptor)));
}
void LIBUSB_CALL libusb_free_transfer(libusb_transfer* t) { std::free(t); }
int LIBUSB_CALL libusb_submit_transfer(libusb_transfer* t) { gPending.push_back(t); return 0; }
int LIBUSB_CALL libusb_cancel_transfer(libusb_transfer* t) {
    if (gStallCancel) { gStalled.push_back(t); return 0; }
    t->status = LIBUSB_TRANSFER_CANCELLED;
    t->callback(t);
    return 0;
}
int LIBUSB_CALL libusb_handle_events_timeout(libusb_context*, struct timeval*) {
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
    return 0;
}
}

namespace {

// --- descriptor builders ----------------------------------------------------

using Bytes = std::vector<uint8_t>;
std::deque<Bytes> gExtras;   // keeps every extra blob alive

constexpr uint8_t kIsoSyncOut = 0x0D, kIsoAsyncOut = 0x05, kIsoFeedbackIn = 0x11, kInterrupt = 0x03;

libusb_endpoint_descriptor ep(uint8_t addr, uint8_t attr, uint16_t mps, uint8_t interval) {
    libusb_endpoint_descriptor e{};
    e.bLength = 7;
    e.bDescriptorType = LIBUSB_DT_ENDPOINT;
    e.bEndpointAddress = addr;
    e.bmAttributes = attr;
    e.wMaxPacketSize = mps;
    e.bInterval = interval;
    return e;
}

const unsigned char* keep(Bytes b, int* len) {
    gExtras.push_back(std::move(b));
    *len = static_cast<int>(gExtras.back().size());
    return gExtras.back().data();
}

libusb_interface_descriptor alt(uint8_t num, uint8_t setting, uint8_t cls, uint8_t sub,
                                uint8_t proto, Bytes extra,
                                std::vector<libusb_endpoint_descriptor> eps) {
    libusb_interface_descriptor d{};
    d.bLength = 9;
    d.bDescriptorType = LIBUSB_DT_INTERFACE;
    d.bInterfaceNumber = num;
    d.bAlternateSetting = setting;
    d.bInterfaceClass = cls;
    d.bInterfaceSubClass = sub;
    d.bInterfaceProtocol = proto;
    d.extra = keep(std::move(extra), &d.extra_length);
    d.bNumEndpoints = static_cast<uint8_t>(eps.size());
    auto* arr = new libusb_endpoint_descriptor[eps.size() + 1];
    for (size_t i = 0; i < eps.size(); ++i) arr[i] = eps[i];
    d.endpoint = arr;
    return d;
}

libusb_config_descriptor* config(Bytes extra,
                                 std::vector<std::vector<libusb_interface_descriptor>> ifaces) {
    auto* c = new libusb_config_descriptor{};
    c->bNumInterfaces = static_cast<uint8_t>(ifaces.size());
    auto* arr = new libusb_interface[ifaces.size()];
    for (size_t i = 0; i < ifaces.size(); ++i) {
        auto* alts = new libusb_interface_descriptor[ifaces[i].size()];
        for (size_t a = 0; a < ifaces[i].size(); ++a) alts[a] = ifaces[i][a];
        arr[i].altsetting = alts;
        arr[i].num_altsetting = static_cast<int>(ifaces[i].size());
    }
    c->interface = arr;
    c->extra = keep(std::move(extra), &c->extra_length);
    return c;
}

Bytes cat(Bytes a, const Bytes& b) {
    a.insert(a.end(), b.begin(), b.end());
    return a;
}

// Interface Association: interfaces 0-1, audio, the given function subclass.
Bytes iad(uint8_t functionSubclass, uint8_t protocol) {
    return {8, 0x0B, 0, 2, 1, functionSubclass, protocol, 0};
}

std::vector<libusb_interface_descriptor> hid(uint8_t num, uint8_t epAddr) {
    return {alt(num, 0, 3, 0, 0, {}, {ep(epAddr, kInterrupt, 64, 1)})};
}

// Configuration 2: UAC3 BADD Headphone (0x21). Nothing class-specific.
libusb_config_descriptor* appleBadd() {
    return config(iad(0x21, 0x30), {
        {alt(0, 0, 1, 1, 0x30, {}, {ep(0x81, kInterrupt, 6, 1)})},
        {alt(1, 0, 1, 2, 0x30, {}, {}),
         alt(1, 1, 1, 2, 0x30, {}, {ep(0x02, kIsoSyncOut, 0x0120, 1)}),
         alt(1, 2, 1, 2, 0x30, {}, {ep(0x02, kIsoSyncOut, 0x00C0, 1)})},
        hid(2, 0x85),
        hid(3, 0x86),
    });
}

// UAC2 AudioControl: clock source 9; playback IT 1 -> FU 2 (mute on
// the master channel, volume per channel) -> OT 3; and a microphone
// IT 4 -> FU 5 -> USB streaming OT 6, which playback must not touch.
Bytes uac2Control() {
    return {
        9, 0x24, 0x01, 0x00, 0x02, 0x04, 0x3D, 0x00, 0x00,             // HEADER, bcdADC 2.00
        17, 0x24, 0x02, 1, 0x01, 0x01, 0, 9, 2, 3, 0, 0, 0, 0, 0, 0, 0, // INPUT_TERMINAL 1
        18, 0x24, 0x06, 2, 1, 0x03, 0, 0, 0, 0x0C, 0, 0, 0, 0x0C, 0, 0, 0, 0, // FEATURE_UNIT 2
        12, 0x24, 0x03, 3, 0x02, 0x03, 0, 2, 9, 0, 0, 0,                // OUTPUT_TERMINAL 3
        17, 0x24, 0x02, 4, 0x01, 0x02, 0, 9, 1, 0, 0, 0, 0, 0, 0, 0, 0, // INPUT_TERMINAL 4 (mic)
        14, 0x24, 0x06, 5, 4, 0x0F, 0, 0, 0, 0x0F, 0, 0, 0, 0,          // FEATURE_UNIT 5 (mic)
        12, 0x24, 0x03, 6, 0x01, 0x01, 0, 5, 9, 0, 0, 0,                // OUTPUT_TERMINAL 6
        8, 0x24, 0x0A, 9, 0x01, 0x07, 0, 0,                             // CLOCK_SOURCE 9
    };
}

Bytes uac2Stream(uint8_t subslot, uint8_t bits) {
    Bytes general = {16, 0x24, 0x01, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0};
    return cat(general, {6, 0x24, 0x02, 1, subslot, bits});
}

// Configuration 1: UAC2, same endpoints.
libusb_config_descriptor* appleUac2() {
    return config(iad(0x00, 0x20), {
        {alt(0, 0, 1, 1, 0x20, uac2Control(), {ep(0x81, kInterrupt, 6, 1)})},
        {alt(1, 0, 1, 2, 0x20, {}, {}),
         alt(1, 1, 1, 2, 0x20, uac2Stream(3, 24), {ep(0x02, kIsoSyncOut, 0x0120, 1)}),
         alt(1, 2, 1, 2, 0x20, uac2Stream(2, 16), {ep(0x02, kIsoSyncOut, 0x00C0, 1)})},
        hid(2, 0x85),
        hid(3, 0x86),
    });
}

// Configuration 3: full UAC3, whose descriptors the driver can't read.
libusb_config_descriptor* appleFullUac3() {
    Bytes header = {10, 0x24, 0x01, 0x04, 0x40, 0x00, 0, 0, 0, 0};
    return config(iad(0x01, 0x30), {
        {alt(0, 0, 1, 1, 0x30, header, {ep(0x81, kInterrupt, 6, 1)})},
        {alt(1, 0, 1, 2, 0x30, {}, {}),
         alt(1, 1, 1, 2, 0x30, {23, 0x24, 0x01, 1}, {ep(0x02, kIsoSyncOut, 0x0120, 1)})},
    });
}

// A UAC1 DAC (adaptive, full speed, 48 kHz/16-bit) whose volume goes
// up to +6 dB: IT 1 -> FU 2 (mute master, volume per channel) -> OT 3.
libusb_config_descriptor* uac1Dac() {
    Bytes ac = {
        9, 0x24, 0x01, 0x00, 0x01, 0x28, 0x00, 1, 1,     // HEADER, bcdADC 1.00
        12, 0x24, 0x02, 1, 0x01, 0x01, 0, 2, 3, 0, 0, 0, // INPUT_TERMINAL 1
        10, 0x24, 0x06, 2, 1, 1, 0x01, 0x02, 0x02, 0,    // FEATURE_UNIT 2
        9, 0x24, 0x03, 3, 0x02, 0x03, 0, 2, 0,           // OUTPUT_TERMINAL 3
    };
    Bytes as = {7, 0x24, 0x01, 1, 1, 0x01, 0x00,                     // AS_GENERAL
                11, 0x24, 0x02, 1, 2, 2, 16, 1, 0x80, 0xBB, 0x00};    // FORMAT_TYPE_I, 48k
    return config({}, {
        {alt(0, 0, 1, 1, 0x00, ac, {})},
        {alt(1, 0, 1, 2, 0x00, {}, {}),
         alt(1, 1, 1, 2, 0x00, as, {ep(0x01, 0x09, 0x00C0, 1)})},
    });
}

// A high-speed async UAC2 DAC with a feedback endpoint, one packet per
// microframe: 48 kHz/24-bit/2ch is 6 frames (36 bytes) a packet.
libusb_config_descriptor* asyncHs(uint16_t dataMps) {
    return config(iad(0x00, 0x20), {
        {alt(0, 0, 1, 1, 0x20, uac2Control(), {})},
        {alt(1, 0, 1, 2, 0x20, {}, {}),
         alt(1, 1, 1, 2, 0x20, uac2Stream(3, 24),
             {ep(0x01, kIsoAsyncOut, dataMps, 1), ep(0x81, kIsoFeedbackIn, 4, 4)})},
    });
}

// --- driving the pump -------------------------------------------------------

struct Pumped {
    int maxBytes = 0;
    long frames = 0;
    int packets = 0;
};

// Completes [transfers] data transfers (8 packets each) in submission order.
// Feedback transfers come back carrying [feedbackQ16], when it is non-zero.
Pumped pump(int transfers, int stride, uint32_t feedbackQ16 = 0) {
    Pumped r;
    int done = 0;
    while (done < transfers && !gPending.empty()) {
        libusb_transfer* t = gPending.front();
        gPending.pop_front();
        t->status = LIBUSB_TRANSFER_COMPLETED;
        if (t->endpoint & 0x80) {
            if (feedbackQ16 == 0) { gPending.push_back(t); continue; }
            std::memcpy(t->buffer, &feedbackQ16, 4);
            t->iso_packet_desc[0].status = LIBUSB_TRANSFER_COMPLETED;
            t->iso_packet_desc[0].actual_length = 4;
            t->callback(t);
            continue;
        }
        t->callback(t);
        for (int p = 0; p < t->num_iso_packets; ++p) {
            int len = static_cast<int>(t->iso_packet_desc[p].length);
            if (len > r.maxBytes) r.maxBytes = len;
            r.frames += len / stride;
            ++r.packets;
        }
        ++done;
    }
    return r;
}

struct Session {
    LibusbUacDriver driver;
    bool started = false;

    Session(libusb_config_descriptor* cfg, int speed, int rate, int bits) {
        gConfig = cfg;
        gSpeed = speed;
        gPending.clear();
        gClockSetCount = 0;
        gLastAlt = -1;
        driver.open(3);
        started = driver.start(rate, bits, 2);
        if (started) {
            const auto& f = driver.currentFormat();
            std::vector<uint8_t> pcm(rate / 5 * f.channels * f.bytesPerSample, 0x5A);
            driver.writePcm(pcm.data(), rate / 5);
        }
    }
    ~Session() { driver.close(); }

    int stride() const {
        const auto& f = driver.currentFormat();
        return f.channels * f.bytesPerSample;
    }
};

bool contains(const std::string& s, const char* what) { return s.find(what) != std::string::npos; }

} // namespace

int main() {
    std::printf("-- Apple adapter, BADD configuration (what Android selects)\n");
    {
        resetUnits({{2, 0}});
        Session s(appleBadd(), LIBUSB_SPEED_FULL, 48000, 24);
        const auto& f = s.driver.currentFormat();
        check(s.started, "48 kHz/24-bit starts");
        check(f.altSetting == 1 && f.bytesPerSample == 3, "24-bit is alt 1, 3-byte subslots (from mps 288)");
        check(f.badd && f.uacVersion == 0x0300, "negotiated as UAC3 BADD");
        check(gClockSetCount == 0, "no clock request: BADD has no clock entity");
        const FakeUnit& fu = gUnits[2];
        check(fu.muted.at(0) == 0, "BADD playback unit 2 unmuted (master channel)");
        check(fu.volume.at(1) == 0 && fu.volume.at(2) == 0,
              "BADD volume raised from -20 dB to 0 dB on both channels");
        check(s.driver.currentFormat().hwVolumeDb256 == 0, "reports HW volume 0 dB");
        auto rates = s.driver.supportedRates();
        check(rates.size() == 1 && rates[0].minHz == 48000 && rates[0].maxHz == 48000,
              "supported rates list just 48 kHz");
        Pumped p = pump(100, s.stride());
        check(p.maxBytes == 288, "packets are 288 bytes, the endpoint's exact max");
        check(p.frames == 48L * p.packets, "48 frames a packet, 48000 a second");
    }
    {
        Session s(appleBadd(), LIBUSB_SPEED_FULL, 48000, 16);
        const auto& f = s.driver.currentFormat();
        check(s.started && f.altSetting == 2 && f.bytesPerSample == 2,
              "48 kHz/16-bit starts on alt 2 (from mps 192)");
        Pumped p = pump(100, s.stride());
        check(p.maxBytes == 192, "16-bit packets are 192 bytes");
    }
    {
        Session s(appleBadd(), LIBUSB_SPEED_FULL, 44100, 24);
        check(!s.started && s.driver.lastError() == StartError::NoMatchingAlt,
              "44.1 kHz is refused: BADD is 48 kHz only");
        check(contains(s.driver.lastErrorDetail(), "fixed at 48 kHz"),
              "the error says why");
        check(gLastAlt == -1, "no alt setting was selected");
    }

    std::printf("-- Apple adapter, UAC2 configuration\n");
    {
        resetUnits({{2, 0}, {5, 0}});
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        check(gUnits[2].muted.at(0) == 0, "playback unit 2 unmuted");
        check(gUnits[2].volume.at(1) == 0 && gUnits[2].volume.at(2) == 0,
              "playback volume at 0 dB on both channels");
        check(!gUnits[5].touched, "the microphone's unit is left alone");
        check(s.started && s.driver.currentFormat().altSetting == 1,
              "48 kHz/24-bit fits its exactly-sized 288-byte endpoint");
        check(!s.driver.currentFormat().badd, "not BADD");
        Pumped p = pump(100, s.stride());
        check(p.maxBytes == 288, "packets never pass 288 bytes");
    }
    {
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 16);
        check(s.started && s.driver.currentFormat().altSetting == 2,
              "48 kHz/16-bit fits its 192-byte endpoint");
    }

    std::printf("-- UAC1 DAC with gain past full scale\n");
    {
        resetUnits({{2, 6 * 256}});
        Session s(uac1Dac(), LIBUSB_SPEED_FULL, 48000, 16);
        check(s.started, "48 kHz/16-bit starts");
        check(gUnits[2].muted.at(0) == 0, "unmuted");
        check(gUnits[2].volume.at(1) == 0 && gUnits[2].volume.at(2) == 0,
              "volume capped at 0 dB, not its +6 dB maximum");
    }
    {
        resetUnits({{2, 0}});
        gUnits[2].stalls = true;
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        check(s.started && s.driver.currentFormat().hwVolumeDb256 == monotrypt::usb::kHwVolumeNone,
              "a DAC that refuses volume requests still plays, reporting none");
    }

    std::printf("-- Apple adapter, full UAC3 configuration\n");
    {
        Session s(appleFullUac3(), LIBUSB_SPEED_FULL, 48000, 24);
        check(!s.started && s.driver.lastError() == StartError::NoMatchingAlt,
              "full UAC3 is refused, not misread as UAC2");
        check(contains(s.driver.lastErrorDetail(), "Audio 3.0"), "the error says why");
    }

    std::printf("-- async high-speed DAC with feedback\n");
    // 6.5 frames a packet, 16.16 fixed point.
    const uint32_t overAsk = (6u << 16) | 0x8000u;
    {
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        check(s.started && s.driver.currentFormat().feedbackEndpointAddress == 0x81,
              "starts with its feedback endpoint");
        Pumped p = pump(200, s.stride(), overAsk);
        check(p.maxBytes == 7 * 6, "feedback may raise a packet to nominal + 1 frame");
    }
    {
        // Endpoint sized to the nominal rate only: no room for headroom.
        Session s(asyncHs(36), LIBUSB_SPEED_HIGH, 48000, 24);
        check(s.started, "an endpoint sized to the nominal rate still starts");
        Pumped p = pump(200, s.stride(), overAsk);
        check(p.maxBytes <= 36, "feedback can't push a packet past wMaxPacketSize");
    }

    std::printf("-- a DAC that stalls while stopping\n");
    {
        resetUnits({{2, 0}});
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        check(s.started, "starts");
        pump(10, s.stride());
        gStallCancel = true;
        gStalled.clear();
        s.driver.stop();
        gStallCancel = false;
        check(!gStalled.empty(), "stop gave up on transfers that never came back");
        // They come back now. Their memory must still be the driver's:
        // AddressSanitizer reports this write if stop freed them.
        for (libusb_transfer* t : gStalled) {
            std::memset(t->buffer, 0, static_cast<size_t>(t->length));
            t->status = LIBUSB_TRANSFER_CANCELLED;
            t->callback(t);
        }
        gStalled.clear();
        gPending.clear();
        check(s.driver.start(48000, 24, 2), "starts again after the late completions");
        pump(10, s.stride());
        s.driver.stop();
        check(gStalled.empty(), "a normal stop waits for nothing");
    }

    if (failures) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all checks passed\n");
    return 0;
}
