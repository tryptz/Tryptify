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

#include <algorithm>
#include <atomic>
#include <chrono>
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
int gAltSets = 0;
// A DAC that refuses every alt setting but 0 (gone, or wedged).
bool gRefuseAlt = false;
// Calls into the event loop: a running event thread makes ~1,000 a
// second here, a stopped one none.
std::atomic<long> gEventPolls{0};
// Handles closed, and whether any was closed while the driver still had
// transfers out on it or an interface claimed.
int gCloseCount = 0;
int gClaimed = 0;
int gOutstanding = 0;   // submitted and not yet completed or cancelled
bool gClosedUnderPump = false;

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
void LIBUSB_CALL libusb_close(libusb_device_handle*) {
    ++gCloseCount;
    if (gClaimed > 0 || gOutstanding > 0) gClosedUnderPump = true;
}
int LIBUSB_CALL libusb_set_auto_detach_kernel_driver(libusb_device_handle*, int) { return 0; }
libusb_device* LIBUSB_CALL libusb_get_device(libusb_device_handle*) { return reinterpret_cast<libusb_device*>(1); }
int LIBUSB_CALL libusb_get_device_speed(libusb_device*) { return gSpeed; }
int LIBUSB_CALL libusb_get_active_config_descriptor(libusb_device*, libusb_config_descriptor** c) { *c = gConfig; return 0; }
int LIBUSB_CALL libusb_get_config_descriptor(libusb_device*, uint8_t, libusb_config_descriptor** c) { *c = gConfig; return 0; }
void LIBUSB_CALL libusb_free_config_descriptor(libusb_config_descriptor*) {}
int LIBUSB_CALL libusb_claim_interface(libusb_device_handle*, int) { ++gClaimed; return 0; }
int LIBUSB_CALL libusb_release_interface(libusb_device_handle*, int) { --gClaimed; return 0; }
int LIBUSB_CALL libusb_set_interface_alt_setting(libusb_device_handle*, int, int alt) {
    ++gAltSets;
    if (gRefuseAlt && alt != 0) return LIBUSB_ERROR_PIPE;
    gLastAlt = alt;
    return 0;
}
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
int LIBUSB_CALL libusb_submit_transfer(libusb_transfer* t) { gPending.push_back(t); ++gOutstanding; return 0; }
int LIBUSB_CALL libusb_cancel_transfer(libusb_transfer* t) {
    if (gStallCancel) { gStalled.push_back(t); return 0; }
    --gOutstanding;
    // A cancelled transfer is no longer on the bus.
    gPending.erase(std::remove(gPending.begin(), gPending.end(), t), gPending.end());
    t->status = LIBUSB_TRANSFER_CANCELLED;
    t->callback(t);
    return 0;
}
int LIBUSB_CALL libusb_handle_events_timeout(libusb_context*, struct timeval*) {
    ++gEventPolls;
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

// A high-speed async UAC2 DAC with a feedback endpoint, by default one
// packet per microframe (48 kHz/24-bit/2ch is 6 frames, 36 bytes, a
// packet) and a feedback reading every millisecond.
libusb_config_descriptor* asyncHs(uint16_t dataMps, uint8_t dataInterval = 1,
                                  uint8_t feedbackInterval = 4) {
    return config(iad(0x00, 0x20), {
        {alt(0, 0, 1, 1, 0x20, uac2Control(), {})},
        {alt(1, 0, 1, 2, 0x20, {}, {}),
         alt(1, 1, 1, 2, 0x20, uac2Stream(3, 24),
             {ep(0x01, kIsoAsyncOut, dataMps, dataInterval),
              ep(0x81, kIsoFeedbackIn, 4, feedbackInterval)})},
    });
}

// --- driving the pump -------------------------------------------------------

struct Pumped {
    int maxBytes = 0;
    long frames = 0;
    int packets = 0;
};

// Completes [transfers] data transfers in submission order and measures
// what the driver refilled them with. Feedback transfers come back
// carrying [feedbackQ16] in every packet when it is non-zero, or in the
// last packet only with [lastPacketOnly] (a DAC that answers some polls
// with nothing); with zero they are left waiting.
Pumped pump(int transfers, int stride, uint32_t feedbackQ16 = 0, bool lastPacketOnly = false) {
    Pumped r;
    int done = 0;
    size_t idleFeedback = 0;
    while (done < transfers && !gPending.empty() && idleFeedback <= gPending.size()) {
        libusb_transfer* t = gPending.front();
        gPending.pop_front();
        t->status = LIBUSB_TRANSFER_COMPLETED;
        if (t->endpoint & 0x80) {
            if (feedbackQ16 == 0) { gPending.push_back(t); ++idleFeedback; continue; }
            for (int p = 0; p < t->num_iso_packets; ++p) {
                const bool carries = !lastPacketOnly || p == t->num_iso_packets - 1;
                std::memcpy(libusb_get_iso_packet_buffer_simple(t, p), &feedbackQ16, 4);
                t->iso_packet_desc[p].status = LIBUSB_TRANSFER_COMPLETED;
                t->iso_packet_desc[p].actual_length = carries ? 4 : 0;
            }
            --gOutstanding;
            t->callback(t);
            continue;
        }
        idleFeedback = 0;
        --gOutstanding;
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

    // Writes [prefill] frames once started; -1 is 200 ms.
    Session(libusb_config_descriptor* cfg, int speed, int rate, int bits, int prefill = -1) {
        gConfig = cfg;
        gSpeed = speed;
        gPending.clear();
        gClockSetCount = 0;
        gLastAlt = -1;
        driver.open(3);
        started = driver.start(rate, bits, 2);
        if (started) write(prefill < 0 ? rate / 5 : prefill);
    }
    ~Session() { driver.close(); }

    int write(int frames) {
        const auto& f = driver.currentFormat();
        std::vector<uint8_t> pcm(static_cast<size_t>(frames) * f.channels * f.bytesPerSample, 0x5A);
        return driver.writePcm(pcm.data(), frames);
    }

    int stride() const {
        const auto& f = driver.currentFormat();
        return f.channels * f.bytesPerSample;
    }
};

bool contains(const std::string& s, const char* what) { return s.find(what) != std::string::npos; }

// Packets per transfer of the pending transfers going [in] or out.
int pendingPackets(bool in, int* count = nullptr) {
    int packets = 0, n = 0;
    for (libusb_transfer* t : gPending) {
        if (((t->endpoint & 0x80) != 0) != in) continue;
        packets = t->num_iso_packets;
        ++n;
    }
    if (count) *count = n;
    return packets;
}

// Whether every pending data transfer carries only silence.
bool pendingDataSilent() {
    for (libusb_transfer* t : gPending) {
        if (t->endpoint & 0x80) continue;
        size_t bytes = 0;
        for (int p = 0; p < t->num_iso_packets; ++p) bytes += t->iso_packet_desc[p].length;
        for (size_t i = 0; i < bytes; ++i) {
            if (t->buffer[i] != 0) return false;
        }
    }
    return true;
}

// Event-loop calls over [ms] milliseconds.
long eventPollsOver(int ms) {
    long before = gEventPolls.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    return gEventPolls.load() - before;
}

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

    std::printf("-- opened again while streaming\n");
    {
        resetUnits({{2, 0}});
        gClaimed = 0;
        gOutstanding = 0;
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        check(s.started, "starts");
        pump(10, s.stride());
        gCloseCount = 0;
        gClosedUnderPump = false;
        check(s.driver.open(3), "the same connection again is still open");
        check(gCloseCount == 0 && s.driver.isStreaming(), "and its stream is left running");
        // A second connection to the device: the Kotlin side used to hand one
        // over on every reconcile, and the handle was closed under the pump.
        check(s.driver.open(4), "another connection opens");
        check(gCloseCount == 1, "the old handle is closed");
        check(!gClosedUnderPump, "only after the pump stopped and its interfaces were released");
        check(!s.driver.isStreaming(), "the old stream is gone with it");
        // The stub's queue still points at transfers the driver has freed.
        gPending.clear();
        check(s.driver.start(48000, 24, 2), "the new connection starts");
        pump(10, s.stride());
    }

    std::printf("-- transfer sizes: ~4 ms and at least 8 packets each, 4 in flight\n");
    {
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        int data = 0, fb = 0;
        check(pendingPackets(false, &data) == 32 && data == 4,
              "high speed, a packet per microframe: 4 transfers of 32 packets (16 ms), "
              "250 wakeups a second");
        check(pendingPackets(true, &fb) == 8 && fb == 2,
              "1 ms feedback: 2 transfers of 8 readings, 125 wakeups a second");
        Pumped p = pump(250, s.stride());
        check(p.frames == 48000, "a second of transfers carries 48,000 frames");
        check(p.maxBytes <= 0x0100, "no packet passes wMaxPacketSize");
    }
    {
        Session s(asyncHs(0x0100, 1, 1), LIBUSB_SPEED_HIGH, 48000, 24);
        check(pendingPackets(true) == 64, "feedback every microframe: 64 readings (8 ms) a transfer");
    }
    {
        Session s(asyncHs(0x0100, 2), LIBUSB_SPEED_HIGH, 48000, 24);
        check(pendingPackets(false) == 16, "a packet every 2 microframes: 16 packets (4 ms)");
        Pumped p = pump(250, s.stride());
        check(p.frames == 48000, "48,000 frames a second");
    }
    {
        Session s(asyncHs(0x0100, 3), LIBUSB_SPEED_HIGH, 48000, 24);
        check(pendingPackets(false) == 8, "a packet every 4 microframes: 8 packets (4 ms)");
    }
    {
        // One packet a millisecond at high speed (the Focal Bathys' alt):
        // 4 ms would be 4 packets and twice the wakeups it always had.
        Session s(asyncHs(0x0130, 4), LIBUSB_SPEED_HIGH, 48000, 24);
        int data = 0;
        check(pendingPackets(false, &data) == 8 && data == 4,
              "high speed, a packet per ms: 4 transfers of 8 packets (8 ms), "
              "125 wakeups a second as before");
        Pumped p = pump(125, s.stride());
        check(p.frames == 48000 && p.maxBytes == 48 * 6, "48 frames a packet, 48,000 a second");
    }
    {
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        int data = 0;
        check(pendingPackets(false, &data) == 8 && data == 4,
              "full speed: 4 transfers of 8 packets (8 ms), 125 wakeups a second as before");
        Pumped p = pump(125, s.stride());
        check(p.frames == 48000 && p.maxBytes == 288, "48 frames a packet, never past 288 bytes");
    }
    {
        // Played position at full speed, where ~32 ms is in flight: the
        // first transfers are silence, then each counts once it completes.
        // 48 frames a packet, 8 packets: 384 a transfer.
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        pump(4, s.stride());
        check(s.driver.playedFrames() == 0,
              "full speed: 32 ms of the song in flight is not counted as played");
        pump(1, s.stride());
        check(s.driver.playedFrames() == 384, "and a transfer counts once it completes");
    }
    {
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 44100, 24);
        Pumped p = pump(250, s.stride());
        check(p.frames >= 44099 && p.frames <= 44100,
              "44.1 kHz at high speed: 44,100 frames a second, to within one");
    }
    {
        const uint32_t overAsk = (6u << 16) | 0x8000u;
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        Pumped p = pump(200, s.stride(), overAsk, /*lastPacketOnly=*/true);
        check(p.maxBytes == 7 * 6, "a reading in any packet of a feedback transfer steers the pump");
    }

    std::printf("-- the played position counts what the DAC was sent\n");
    {
        // 9,600 frames written; 32 packets of 6 frames is 192 a transfer.
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        const int st = s.stride();
        check(s.driver.writtenFrames() == 9600 && s.driver.playedFrames() == 0,
              "nothing played before a transfer completes");
        pump(4, st);
        check(s.driver.playedFrames() == 0, "the primed silence is not counted");
        check(s.driver.writtenFrames() - s.driver.playedFrames() == 9600,
              "16 ms of the song queued in flight is still pending, not played");
        pump(1, st);
        check(s.driver.playedFrames() == 192, "a transfer counts once it completes");
        pump(3, st);
        check(s.driver.playedFrames() == 768, "and each one after it");
    }
    {
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24, /*prefill=*/100);
        pump(14, s.stride());
        check(s.driver.playedFrames() == 100,
              "an underrun's silence is not counted: played stops at what was written");
        check(s.driver.writtenFrames() == s.driver.playedFrames(), "so the stream can end");
    }
    {
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        const int st = s.stride();
        pump(6, st);
        check(s.driver.playedFrames() == 384, "two transfers of the song played");
        s.driver.flushRing();
        check(s.driver.playedFrames() == 0 && s.driver.writtenFrames() == 0,
              "a flush starts both counts again");
        pump(4, st);
        check(s.driver.playedFrames() == 0,
              "audio queued before the flush is not counted after it");
        check(pendingDataSilent(), "and the flushed audio is not sent: the refills are silence");
        check(s.write(500) == 500, "the next song's frames are taken");
        pump(8, st);
        check(s.driver.playedFrames() == 500 && s.driver.writtenFrames() == 500,
              "and counted from zero");
    }

    std::printf("-- parked while paused\n");
    {
        resetUnits({{2, 0}});
        gClaimed = 0;
        gOutstanding = 0;
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        const int st = s.stride();
        check(s.started && gClaimed == 2, "starts, both interfaces claimed");
        pump(8, st);
        s.driver.flushRing();   // what pause() does first
        check(eventPollsOver(30) > 5, "a running pump has its event thread polling");
        check(s.driver.parkPump(), "parks");
        check(gOutstanding == 0 && gPending.empty(), "every transfer cancelled and back");
        check(gLastAlt == 0, "the interface is on alt 0");
        check(gClaimed == 2, "both interfaces still claimed");
        check(eventPollsOver(30) == 0, "no thread polls libusb while parked");
        check(s.driver.isStreaming() && s.driver.isPumpParked(),
              "still the same stream, parked");
        check(s.driver.isStreamingFormat(48000, 24, 2),
              "a track in the same format reuses it");
        check(!s.driver.parkPump(), "parking twice does nothing");

        const int clockSets = gClockSetCount;
        check(s.driver.resumePump(), "resumes");
        check(!s.driver.isPumpParked() && s.driver.isStreaming(), "streaming again");
        check(gLastAlt == 1, "back on its streaming alt");
        check(gClockSetCount > clockSets, "with the rate set again");
        check(gUnits[2].volume.at(1) == 0 && gUnits[2].muted.at(0) == 0,
              "and the DAC's volume at full scale, unmuted");
        int data = 0, fb = 0;
        pendingPackets(false, &data);
        pendingPackets(true, &fb);
        check(data == 4 && fb == 2, "a full set of transfers in flight");
        check(s.driver.playedFrames() == 0 && s.driver.writtenFrames() == 0,
              "position counts from zero");
        check(s.write(9600) == 9600, "takes audio");
        pump(6, st);
        check(s.driver.playedFrames() == 384, "and the position follows the DAC again");

        gLastAlt = -1;
        const int altSets = gAltSets;
        check(s.driver.resumePump() && gAltSets == altSets,
              "resuming a stream that is not parked changes nothing");

        check(s.driver.parkPump(), "parks again");
        const int altSetsParked = gAltSets;
        s.driver.stop();
        check(gClaimed == 0, "stop() lets both interfaces go");
        check(gAltSets == altSetsParked, "without setting alt 0 twice");
        check(!s.driver.isStreaming() && !s.driver.isPumpParked(), "and the stream is gone");
        check(!s.driver.resumePump(), "a stopped stream does not resume");
    }
    {
        resetUnits({{2, 0}});
        gClaimed = 0;
        gOutstanding = 0;
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 48000, 24);
        pump(8, s.stride());
        check(s.driver.parkPump(), "parks");
        gRefuseAlt = true;   // unplugged meanwhile, or wedged
        check(!s.driver.resumePump(), "a DAC that refuses its alt on resume: resume fails");
        gRefuseAlt = false;
        check(!s.driver.isStreaming() && !s.driver.isPumpParked(),
              "the stream is down, so playback goes elsewhere");
        check(s.driver.lastError() == StartError::SetAltFailed, "with the reason recorded");
        check(gOutstanding == 0, "and nothing left in flight");
        check(s.write(100) == 0, "writes are refused");
        s.driver.stop();
        check(gClaimed == 0, "stop() still lets the interfaces go");
    }
    {
        resetUnits({{2, 0}});
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        pump(8, s.stride());
        check(s.driver.parkPump(), "a full-speed stream parks");
        check(s.driver.start(48000, 16, 2), "another format starts from parked");
        check(gLastAlt == 2 && !s.driver.isPumpParked(), "on its own alt, not parked");
        Pumped p = pump(20, s.stride());
        check(p.maxBytes == 192, "and streams");
    }

    std::printf("-- ring memory\n");
    {
        LibusbUacDriver idle;
        check(idle.ringCapacityBytes() == 0, "a driver that never streamed holds no ring");
    }
    {
        resetUnits({{2, 0}});
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        check(s.driver.ringCapacityBytes() == 73728, "48 kHz/24-bit: 72 KiB, about 250 ms");
        check(s.driver.start(48000, 16, 2), "16-bit starts");
        check(s.driver.ringCapacityBytes() == 49152, "and its smaller ring really is smaller");
        s.driver.stop();
        check(s.driver.ringCapacityBytes() == 0, "stop() gives the ring back");
        check(s.write(100) == 0, "a write after stop is refused, not written to freed memory");
    }
    {
        Session s(asyncHs(0x0100), LIBUSB_SPEED_HIGH, 192000, 24);
        check(s.started && s.driver.ringCapacityBytes() == 290816,
              "192 kHz/24-bit: 284 KiB");
    }
    {
        // A DAC unplugged mid-song: the controller stops the driver on its
        // own thread while the playback thread keeps writing. Under
        // AddressSanitizer, a write into a ring stop() had freed fails here.
        resetUnits({{2, 0}});
        Session s(appleUac2(), LIBUSB_SPEED_FULL, 48000, 24);
        std::atomic<bool> done{false};
        std::thread writer([&] {
            std::vector<uint8_t> pcm(480 * 6, 0x5A);
            while (!done.load()) {
                s.driver.writePcm(pcm.data(), 480);
                s.driver.flushRing();
            }
        });
        bool allStarted = true;
        for (int i = 0; i < 100; ++i) {
            gPending.clear();
            allStarted = s.driver.start(48000, (i & 1) ? 16 : 24, 2) && allStarted;
            s.driver.stop();
        }
        done.store(true);
        writer.join();
        check(allStarted, "start and stop under a writer on another thread");
    }

    if (failures) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all checks passed\n");
    return 0;
}
