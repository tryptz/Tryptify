// SPDX-License-Identifier: GPL-3.0-or-later
// See libusb_uac_driver.h.

#include "libusb_uac_driver.h"

#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <cstring>
#include <new>

#define TAG "LibusbUacDriver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace monotrypt::usb {

namespace {

// USB Audio Class spec constants we don't get from libusb.h.
constexpr uint8_t USB_CLASS_AUDIO       = 0x01;
constexpr uint8_t SUBCLASS_AUDIOCONTROL = 0x01;
constexpr uint8_t SUBCLASS_AUDIOSTREAM  = 0x02;

constexpr uint8_t CS_INTERFACE          = 0x24;
constexpr uint8_t AC_HEADER             = 0x01;
constexpr uint8_t AC_INPUT_TERMINAL     = 0x02;
constexpr uint8_t AC_OUTPUT_TERMINAL    = 0x03;
constexpr uint8_t AC_CLOCK_SOURCE       = 0x0A;
constexpr uint8_t AC_CLOCK_SELECTOR     = 0x0B;
constexpr uint8_t AC_CLOCK_MULTIPLIER   = 0x0C;
constexpr uint8_t AC_FEATURE_UNIT       = 0x06;
constexpr uint8_t AS_GENERAL            = 0x01;
constexpr uint8_t AS_FORMAT_TYPE        = 0x02;
constexpr uint8_t FORMAT_TYPE_I         = 0x01;

// bInterfaceProtocol of an audio interface: IP_VERSION_02_00 /
// IP_VERSION_03_00. UAC3's AC Header has no bcdADC, so this is the
// only place the version shows up.
constexpr uint8_t IP_VERSION_03_00      = 0x30;
// Interface Association descriptor; its bFunctionSubClass carries the
// UAC3 BADD profile (0x20 generic I/O .. 0x2F), 0x01 for a full UAC3
// function and 0x00 before UAC3.
constexpr uint8_t DT_INTERFACE_ASSOC    = 0x0B;
constexpr uint8_t UAC3_BADD_PROFILE_MIN = 0x20;
constexpr int     UAC3_BADD_RATE_HZ     = 48000;
// BADD fixes its topology instead of describing it: every profile's
// playback path is IT 1 -> FU 2 -> OT 3, mute on the master channel
// and volume on each logical channel (kernel: UAC3_BADD_FU_ID2).
constexpr uint8_t UAC3_BADD_PLAYBACK_FU = 2;

// Feature Unit control selectors and the requests that address them.
// UAC2/UAC3 have one CUR and one RANGE request for every control;
// UAC1 has GET_MIN / GET_MAX / GET_RES instead of RANGE.
constexpr uint8_t FU_MUTE_CONTROL      = 0x01;
constexpr uint8_t FU_VOLUME_CONTROL    = 0x02;
constexpr uint8_t REQ_RANGE            = 0x02;
constexpr uint8_t REQ_UAC1_GET_CUR     = 0x81;
constexpr uint8_t REQ_UAC1_GET_MAX     = 0x83;

// UAC2: SET_CUR on Sample Rate (CS_SAM_FREQ_CONTROL) of a clock
// source entity. UAC1: SET_CUR on SAMPLING_FREQ_CONTROL of the iso
// data endpoint, 3-byte LE rate. Both share bRequest = 0x01.
constexpr uint8_t REQ_SET_CUR              = 0x01;
constexpr uint16_t CS_SAM_FREQ_CONTROL_SEL = 0x01;

// Pump tuning. Each data transfer carries about kTransferUs of audio,
// and never fewer than kMinPacketsPerTransfer packets; kNumTransfers
// are in flight. Every completion wakes the event thread, and transfers
// of 8 packets meant 1,000 wakeups a second at high speed with a packet
// per microframe, plus one per feedback packet. There it is now 32
// packets (4 ms, ~16 ms queued) and 250 wakeups, about what Linux's
// snd-usb-audio queues (up to 48 packets a URB at high speed). With
// one packet a millisecond (full speed, or a high-speed alt with
// bInterval 4) 4 ms would be 4 packets and twice the old wakeups, so
// those keep their 8 packets: 8 ms each, ~32 ms queued, 125 a second.
//
// Lyrics, the scrubber and A/V sync read the played position, which
// counts a transfer only once it completes (see onIso), so a deeper
// queue does not put the position ahead of the DAC.
constexpr int kNumTransfers = 4;
constexpr int kTransferUs = 4000;
constexpr int kMinPacketsPerTransfer = 8;
constexpr int kMaxPacketsPerTransfer = 32;
// Feedback: kFeedbackTransfers in flight of about kFeedbackTransferUs
// each, 8 packets at the common 1 ms feedback interval. The value
// moves at clock-drift speed, and onIso reads the newest one once per
// data transfer, so a fresher reading would change nothing.
constexpr int kFeedbackTransfers = 2;
constexpr int kFeedbackTransferUs = 8000;
constexpr int kMaxFeedbackPackets = 64;
// Heartbeat once a minute.
constexpr int kIsoLogSeconds = 60;
// Ring sizing: the ring is resized at start() to ~250 ms of audio at
// the negotiated format, rounded up to a page multiple. The old fixed
// 1 MiB ring held ~5.9 s at 44.1k/16/2ch — a partial write at that
// depth meant seconds of rescrambled/duplicated PCM on retry, and
// pause/seek had to throw away seconds of queued audio before going
// quiet. 250 ms still absorbs the audio thread's wakeup jitter with
// plenty of margin. The old power-of-two/mask invariant was dropped in
// favor of modulo indexing: head/tail are monotonic byte counters
// reset at start()/flush(), so `head % ringBytes_` is exact, and it
// lets the size hit the latency target instead of jumping to the
// next power of two (which overshot to ~450 ms at 96k/24-bit).
constexpr size_t kRingMinBytes = 1u << 15;   // 32 KiB floor (~186 ms @ 44.1k/16/2ch)
constexpr size_t kRingQuantum = 4096;        // size rounding

// Microseconds between packets of an endpoint with this bInterval,
// with the same reading of bInterval as the rest of the pump.
int packetPeriodUs(bool highSpeed, uint8_t bInterval) {
    if (highSpeed) {
        int exp = bInterval > 0 ? bInterval - 1 : 0;
        if (exp > 15) exp = 15;
        return 125 << exp;
    }
    return 1000 * (bInterval > 0 ? bInterval : 1);
}

// Packets that fill about [targetUs] at [periodUs] a packet, at least
// [minPackets] and at most [maxPackets].
int packetsFor(int targetUs, int periodUs, int minPackets, int maxPackets) {
    int n = periodUs > 0 ? targetUs / periodUs : 1;
    return std::max(minPackets, std::min(n, maxPackets));
}

bool isClassDescriptor(const uint8_t* p, size_t remaining,
                       uint8_t descType, uint8_t subtype) {
    if (remaining < 3) return false;
    return p[1] == descType && p[2] == subtype;
}

// Walks an `extra` blob (returned by libusb in interface_descriptor /
// config_descriptor) and invokes `cb` for each descriptor in turn.
// `cb(p, len) -> bool` returns true to stop the walk early.
template<typename Cb>
void walkExtra(const uint8_t* extra, int extraLen, Cb&& cb) {
    int i = 0;
    while (i + 2 <= extraLen) {
        int len = extra[i];
        if (len < 2 || i + len > extraLen) break;
        if (cb(extra + i, len)) return;
        i += len;
    }
}

// UAC3 BADD §4 fixes every AS endpoint's wMaxPacketSize by channel
// count, sample size and sync type (48 kHz, 1 ms service interval;
// async adds one frame of headroom). A BADD configuration carries no
// AS_GENERAL / FORMAT_TYPE descriptors, so this table is the format.
// Same values as the kernel's UAC3_BADD_EP_MAXPSIZE_* constants.
bool baddFormatFromMaxPacket(uint16_t mps, int* channels, int* subslot) {
    switch (mps & 0x07FF) {
        case 0x0060: case 0x0062: *channels = 1; *subslot = 2; return true;
        case 0x0090: case 0x0093: *channels = 1; *subslot = 3; return true;
        case 0x00C0: case 0x00C4: *channels = 2; *subslot = 2; return true;
        case 0x0120: case 0x0126: *channels = 2; *subslot = 3; return true;
        default: return false;
    }
}

// bFunctionSubClass of the audio Interface Association that covers
// `ifaceNum`, or -1 if there is none. libusb leaves an IAD in whatever
// `extra` blob precedes the function's first interface: the config's
// own for interface 0, otherwise the previous interface's or
// endpoint's, so look everywhere.
int audioFunctionSubclass(const libusb_config_descriptor* config,
                          uint8_t ifaceNum) {
    int found = -1;
    auto scan = [&](const unsigned char* extra, int len) {
        walkExtra(extra, len, [&](const uint8_t* p, int l) {
            if (l >= 8 && p[1] == DT_INTERFACE_ASSOC &&
                p[4] == USB_CLASS_AUDIO &&
                ifaceNum >= p[2] && ifaceNum < p[2] + p[3]) {
                found = p[5];
                return true;
            }
            return false;
        });
    };
    scan(config->extra, config->extra_length);
    for (uint8_t i = 0; i < config->bNumInterfaces && found < 0; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting && found < 0; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            scan(alt.extra, alt.extra_length);
            for (int e = 0; e < alt.bNumEndpoints && found < 0; ++e) {
                scan(alt.endpoint[e].extra, alt.endpoint[e].extra_length);
            }
        }
    }
    return found;
}

} // namespace

// The ring is left empty until a stream starts: this object is built at
// app launch whether or not a DAC is ever plugged in.
LibusbUacDriver::LibusbUacDriver() = default;

LibusbUacDriver::~LibusbUacDriver() {
    stop();
    close();
    std::lock_guard<std::mutex> lock(mutex_);
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
    // libusb is gone, so nothing can complete into these any more.
    freeAbandonedTransfers();
}

std::string LibusbUacDriver::lastErrorDetail() const {
    std::lock_guard<std::mutex> lock(errorMutex_);
    return lastErrorDetail_;
}

std::vector<ClockRateRange> LibusbUacDriver::supportedRates() const {
    std::lock_guard<std::mutex> lock(errorMutex_);
    return supportedRates_;
}

namespace {

// Members `lastError_` / `lastErrorDetail_` are private, so we route
// through a small lambda created at the failure site. Keeping the
// mechanism inline (rather than a setter method) avoids polluting the
// public surface with something only the implementation needs.

} // namespace

// Sets [code] + [detail], logs at WARN. Internal-only — every call site
// in this file calls it through a lambda capture so the lock + log
// stay co-located with the failure context.
namespace {
struct ErrorSink {
    std::atomic<StartError>* code;
    std::mutex* m;
    std::string* detail;
    void operator()(StartError c, std::string text) const {
        code->store(c, std::memory_order_release);
        std::lock_guard<std::mutex> lock(*m);
        *detail = std::move(text);
        LOGW("start error %d: %s", static_cast<int>(c), detail->c_str());
    }
};
} // namespace

bool LibusbUacDriver::ensureContext() {
    if (contextReady_.load(std::memory_order_acquire)) return true;
    std::lock_guard<std::mutex> lock(mutex_);
    if (contextReady_.load(std::memory_order_relaxed)) return true;

    int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (rc != LIBUSB_SUCCESS) {
        LOGW("libusb_set_option(NO_DEVICE_DISCOVERY) -> %d", rc);
    }
    rc = libusb_init(&ctx_);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_init failed: %d", rc);
        ctx_ = nullptr;
        return false;
    }
    contextReady_.store(true, std::memory_order_release);
    LOGI("libusb context ready");
    return true;
}

bool LibusbUacDriver::open(int fileDescriptor) {
    if (!ensureContext()) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_ != nullptr) {
        if (fd_ == fileDescriptor) return true;
        // Another connection while this one is open, possibly streaming. The
        // pump's transfers and its event thread use the handle, so they are
        // stopped and the interfaces given back first, exactly as close()
        // does; closing the handle under them was a use-after-free.
        streaming_.store(false, std::memory_order_release);
        stopLocked();
        libusb_close(device_);
        device_ = nullptr;
        fd_ = -1;
        format_ = {};
    }
    libusb_device_handle* handle = nullptr;
    int rc = libusb_wrap_sys_device(ctx_,
                                    static_cast<intptr_t>(fileDescriptor),
                                    &handle);
    if (rc != LIBUSB_SUCCESS || handle == nullptr) {
        LOGE("libusb_wrap_sys_device(fd=%d) -> %d", fileDescriptor, rc);
        return false;
    }
    device_ = handle;
    fd_ = fileDescriptor;
    // Best-effort kernel-driver detach. On Android the snd-usb-audio
    // driver isn't present in user space (the framework owns USB
    // audio via tinyalsa), so this is normally a no-op — but if a
    // future Android version exposes it, this pre-emptively releases.
    libusb_set_auto_detach_kernel_driver(device_, 1);
    LOGI("opened device via fd=%d", fileDescriptor);
    return true;
}

void LibusbUacDriver::close() {
    stop();
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_ != nullptr) {
        libusb_close(device_);
        device_ = nullptr;
        fd_ = -1;
        format_ = {};
        LOGI("closed device");
    }
}

// --- UAC2 enumeration -------------------------------------------------

bool LibusbUacDriver::selectAltSetting(int sampleRateHz, int bitsPerSample,
                                      int channels, StreamFormat* out_fmt) {
    libusb_device* dev = libusb_get_device(device_);
    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(dev, &config);
    if (rc != LIBUSB_SUCCESS) {
        rc = libusb_get_config_descriptor(dev, 0, &config);
    }
    if (rc != LIBUSB_SUCCESS || !config) {
        LOGE("get_config_descriptor -> %d", rc);
        return false;
    }

    // First pass: locate AudioControl interface, decode bcdADC
    // (UAC version) from its Header class-specific descriptor, and
    // for UAC2 inventory every clock-bearing entity (CLOCK_SOURCE /
    // CLOCK_SELECTOR / CLOCK_MULTIPLIER) and every terminal so we
    // can resolve which clock the streaming path actually uses.
    // Bathys (and many other UAC2 DACs) don't expose a standalone
    // CLOCK_SOURCE — the clock is referenced via the Input or
    // Output Terminal's bCSourceID, so naively grabbing the first
    // CLOCK_SOURCE we find leaves clockId=0 → SET_CUR fails with
    // wIndex=0x0001.
    uint8_t controlIface = 0xFF;
    uint16_t uacVersion = 0x0200;
    bool foundControl = false;
    bool sawHeader = false;
    uint8_t controlProtocol = 0;

    // Per-terminal: (terminalID -> bCSourceID).
    struct TermClock { uint8_t termId; uint8_t clockId; };
    std::vector<TermClock> terminals;
    // All clock-bearing entities, with their subtype so we can
    // distinguish CLOCK_SOURCE (settable rate) from CLOCK_SELECTOR
    // (selects between sources — SET_CUR on it picks pin index, not
    // rate) and CLOCK_MULTIPLIER (derived from another clock).
    struct ClockEntity { uint8_t id; uint8_t subtype; uint8_t baseId; };
    std::vector<ClockEntity> clockEntities;
    // Feature Units, decoded once the UAC version is known (the
    // bmaControls layout differs between UAC1 and UAC2). Points into
    // `config`, which outlives every use below.
    struct RawUnit { uint8_t id; uint8_t sourceId; const uint8_t* p; int len; };
    std::vector<RawUnit> featureUnits;

    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != USB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != SUBCLASS_AUDIOCONTROL) continue;
            controlIface = alt.bInterfaceNumber;
            controlProtocol = alt.bInterfaceProtocol;
            walkExtra(alt.extra, alt.extra_length,
                [&](const uint8_t* p, int len) {
                    if (isClassDescriptor(p, len, CS_INTERFACE, AC_HEADER) && len >= 5) {
                        sawHeader = true;
                        uacVersion = static_cast<uint16_t>(p[3]) |
                                     (static_cast<uint16_t>(p[4]) << 8);
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AC_CLOCK_SOURCE) && len >= 4) {
                        clockEntities.push_back({p[3], AC_CLOCK_SOURCE, 0});
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AC_CLOCK_SELECTOR) && len >= 6) {
                        // CLOCK_SELECTOR layout: bClockID @p[3],
                        // bNrInPins @p[4], baCSourceID(1..N) @p[5..].
                        // We grab the first input-pin source as the
                        // "underlying" clock to set the rate on —
                        // SET_CUR on the selector itself selects the
                        // pin index, not the rate.
                        clockEntities.push_back({p[3], AC_CLOCK_SELECTOR, p[5]});
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AC_CLOCK_MULTIPLIER) && len >= 5) {
                        clockEntities.push_back({p[3], AC_CLOCK_MULTIPLIER, p[4]});
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AC_FEATURE_UNIT) && len >= 7) {
                        featureUnits.push_back({p[3], p[4], p, len});
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AC_INPUT_TERMINAL) && len >= 8) {
                        // UAC2 IT: bTerminalID @p[3], bCSourceID @p[7].
                        terminals.push_back({p[3], p[7]});
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AC_OUTPUT_TERMINAL) && len >= 9) {
                        // UAC2 OT: bTerminalID @p[3], bCSourceID @p[8].
                        terminals.push_back({p[3], p[8]});
                    }
                    return false;
                });
            foundControl = true;
            break;
        }
        if (foundControl) break;
    }
    if (!foundControl) {
        LOGE("no AudioControl interface found");
        libusb_free_config_descriptor(config);
        return false;
    }
    // UAC3 has no bcdADC (its AC Header starts with bCategory), so the
    // version comes from the interface protocol instead. A BADD
    // function says so in its IAD; a configuration with no AC Header
    // at all can only be BADD too, since every other UAC3 topology
    // has to describe itself.
    bool badd = false;
    if (controlProtocol == IP_VERSION_03_00) {
        uacVersion = 0x0300;
        // Anything before this point read UAC2 offsets out of UAC3
        // descriptors; none of it means anything here.
        clockEntities.clear();
        terminals.clear();
        featureUnits.clear();
        int profile = audioFunctionSubclass(config, controlIface);
        badd = profile >= UAC3_BADD_PROFILE_MIN || (profile < 0 && !sawHeader);
        LOGI("AudioControl iface %u, UAC3 (function subclass %d)%s",
             controlIface, profile, badd ? " — BADD profile" : "");
        if (!badd) {
            LOGE("full UAC3 topology (cluster descriptors) is not supported");
            std::lock_guard<std::mutex> elock(errorMutex_);
            selectHint_ = "This DAC is running a full USB Audio 3.0 "
                          "configuration, which the bypass can't drive yet.";
            libusb_free_config_descriptor(config);
            return false;
        }
    } else {
        LOGI("AudioControl iface %u, UAC version 0x%04x%s — %zu clock entities, %zu terminals",
             controlIface, uacVersion,
             (uacVersion < 0x0200) ? " (UAC1)" : " (UAC2)",
             clockEntities.size(), terminals.size());
    }
    for (const auto& ce : clockEntities) {
        const char* kind =
            ce.subtype == AC_CLOCK_SOURCE ? "SOURCE" :
            ce.subtype == AC_CLOCK_SELECTOR ? "SELECTOR" :
            ce.subtype == AC_CLOCK_MULTIPLIER ? "MULTIPLIER" : "?";
        LOGI("  clock id=%u %s baseId=%u", ce.id, kind, ce.baseId);
    }
    for (const auto& tc : terminals) {
        LOGI("  terminal id=%u → clock id=%u", tc.termId, tc.clockId);
    }

    // Second pass: walk AudioStreaming alt settings and find one
    // matching the requested rate/depth/channels. UAC2 doesn't list
    // rates in AS_FORMAT_TYPE (they're on the clock source via
    // CS_SAM_FREQ_CONTROL GET_RANGE, which we skip — we just attempt
    // SET_CUR and let the device STALL if unsupported).
    bool found = false;
    for (uint8_t i = 0; i < config->bNumInterfaces && !found; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != USB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != SUBCLASS_AUDIOSTREAM) continue;
            if (alt.bAlternateSetting == 0) continue;   // alt 0 = idle

            int altChannels = 0, altBits = 0, altSubslot = 0;
            uint8_t altTerminalLink = 0;
            // bTerminalLink sits at the same offset in UAC1's
            // AS_GENERAL; altTerminalLink stays UAC2-only because the
            // clock resolution below reads UAC2 terminals with it.
            uint8_t streamTerminal = 0;
            bool rateSupportedByDescriptor = (uacVersion >= 0x0200);
            walkExtra(alt.extra, alt.extra_length,
                [&](const uint8_t* p, int len) {
                    if (isClassDescriptor(p, len, CS_INTERFACE, AS_GENERAL)) {
                        if (len >= 4) streamTerminal = p[3];
                        // UAC2 AS_GENERAL is 16 bytes and carries
                        // bTerminalLink @p[3] (which terminal this AS
                        // is paired with) and bNrChannels @p[10].
                        if (uacVersion >= 0x0200 && len >= 16) {
                            altTerminalLink = p[3];
                            altChannels = p[10];
                        }
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AS_FORMAT_TYPE)
                               && len >= 4 && p[3] == FORMAT_TYPE_I) {
                        if (uacVersion >= 0x0200) {
                            // UAC2 FORMAT_TYPE_I is exactly 6 bytes:
                            //   bLength, bDescType, bSubType,
                            //   bFormatType @p[3], bSubslotSize @p[4],
                            //   bBitResolution @p[5].
                            if (len >= 6) {
                                altSubslot = p[4];
                                altBits = p[5];
                            }
                        } else if (len >= 7) {
                            // UAC1 FORMAT_TYPE_I starts at 8 bytes:
                            //   bNrChannels @p[4], bSubframeSize @p[5],
                            //   bBitResolution @p[6], bSamFreqType @p[7],
                            //   then a sample-frequency table (continuous
                            //   range or discrete list of 24-bit LE rates).
                            altChannels = p[4];
                            altSubslot = p[5];
                            altBits = p[6];
                            // Walk the sample-frequency table to
                            // verify the requested rate is supported.
                            // Type=0 means continuous [lo, hi]; other
                            // values are the discrete rate count.
                            //
                            // Also push every advertised rate into
                            // supportedRates_ so the UI can list what
                            // this UAC1 device supports — UAC1 has no
                            // clock entities, so we use clockId=0 as
                            // the synthetic "endpoint clock" marker.
                            // Only do this for alts that match our
                            // requested channels/bits, otherwise an
                            // unrelated 5.1 alt would pollute the list.
                            if (len >= 8 && altChannels == channels
                                && altBits == bitsPerSample) {
                                int kind = p[7];
                                int reqRate = sampleRateHz;
                                if (kind == 0 && len >= 14) {
                                    auto rd24 = [](const uint8_t* q) {
                                        return static_cast<uint32_t>(q[0]) |
                                               (static_cast<uint32_t>(q[1]) << 8) |
                                               (static_cast<uint32_t>(q[2]) << 16);
                                    };
                                    uint32_t lo = rd24(p + 8);
                                    uint32_t hi = rd24(p + 11);
                                    rateSupportedByDescriptor =
                                        (static_cast<uint32_t>(reqRate) >= lo &&
                                         static_cast<uint32_t>(reqRate) <= hi);
                                    std::lock_guard<std::mutex> elock(errorMutex_);
                                    bool dup = false;
                                    for (const auto& e : supportedRates_) {
                                        if (e.minHz == lo && e.maxHz == hi) {
                                            dup = true; break;
                                        }
                                    }
                                    if (!dup) supportedRates_.push_back({0, lo, hi, 0});
                                } else if (kind > 0) {
                                    rateSupportedByDescriptor = false;
                                    std::lock_guard<std::mutex> elock(errorMutex_);
                                    for (int k = 0; k < kind; ++k) {
                                        int off = 8 + k * 3;
                                        if (off + 3 > len) break;
                                        uint32_t hz =
                                            static_cast<uint32_t>(p[off]) |
                                            (static_cast<uint32_t>(p[off + 1]) << 8) |
                                            (static_cast<uint32_t>(p[off + 2]) << 16);
                                        if (static_cast<int>(hz) == reqRate) {
                                            rateSupportedByDescriptor = true;
                                        }
                                        bool dup = false;
                                        for (const auto& e : supportedRates_) {
                                            if (e.minHz == hz && e.maxHz == hz) {
                                                dup = true; break;
                                            }
                                        }
                                        if (!dup) supportedRates_.push_back({0, hz, hz, 0});
                                    }
                                }
                            } else if (len >= 8) {
                                // Channels/bits don't match — keep the
                                // rate-supported check working without
                                // polluting the inventory.
                                int kind = p[7];
                                int reqRate = sampleRateHz;
                                if (kind == 0 && len >= 14) {
                                    auto rd24 = [](const uint8_t* q) {
                                        return static_cast<uint32_t>(q[0]) |
                                               (static_cast<uint32_t>(q[1]) << 8) |
                                               (static_cast<uint32_t>(q[2]) << 16);
                                    };
                                    uint32_t lo = rd24(p + 8);
                                    uint32_t hi = rd24(p + 11);
                                    rateSupportedByDescriptor =
                                        (static_cast<uint32_t>(reqRate) >= lo &&
                                         static_cast<uint32_t>(reqRate) <= hi);
                                } else if (kind > 0) {
                                    rateSupportedByDescriptor = false;
                                    for (int k = 0; k < kind; ++k) {
                                        int off = 8 + k * 3;
                                        if (off + 3 > len) break;
                                        uint32_t hz =
                                            static_cast<uint32_t>(p[off]) |
                                            (static_cast<uint32_t>(p[off + 1]) << 8) |
                                            (static_cast<uint32_t>(p[off + 2]) << 16);
                                        if (static_cast<int>(hz) == reqRate) {
                                            rateSupportedByDescriptor = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return false;
                });
            if (badd) {
                // No AS descriptors to read: the OUT data endpoint's
                // wMaxPacketSize is the format, and the rate is 48 kHz.
                altChannels = altBits = altSubslot = 0;
                for (int e = 0; e < alt.bNumEndpoints; ++e) {
                    const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                    if ((ep.bmAttributes & 0x03) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS ||
                        (ep.bEndpointAddress & 0x80) != 0) continue;
                    if (baddFormatFromMaxPacket(ep.wMaxPacketSize,
                                                &altChannels, &altSubslot)) {
                        altBits = altSubslot * 8;
                    }
                    break;
                }
                if (altChannels == 0) continue;   // capture alt, or not a BADD size
                LOGI("BADD alt %u: %dch %d-bit @ %d Hz (mps-implied)",
                     alt.bAlternateSetting, altChannels, altBits,
                     UAC3_BADD_RATE_HZ);
                rateSupportedByDescriptor = (sampleRateHz == UAC3_BADD_RATE_HZ);
                std::lock_guard<std::mutex> elock(errorMutex_);
                if (supportedRates_.empty()) {
                    supportedRates_.push_back(
                        {0, UAC3_BADD_RATE_HZ, UAC3_BADD_RATE_HZ, 0});
                }
            }

            if (altChannels != channels || altBits != bitsPerSample) continue;
            if (!rateSupportedByDescriptor) {
                LOGW("alt %u advertises %dch/%db but not %d Hz",
                     alt.bAlternateSetting, channels, bitsPerSample, sampleRateHz);
                continue;
            }

            // Find the OUT iso data EP and (optionally) the IN iso
            // feedback EP. Async UAC2 endpoints have:
            //   bmAttributes bits 1:0 = 01 (iso)
            //                bits 3:2 = 01 (async)
            //                bits 5:4 = 00 data, 01 feedback,
            //                          10 implicit-feedback data
            // Direction lives in bEndpointAddress bit 7 (1 = IN).
            // Also check that the requested rate FITS in the alt's
            // wMaxPacketSize against its bInterval — devices often
            // expose multiple alts (e.g. one for telephony at 24
            // kHz, one for music at 48 kHz+) and naively picking
            // the first one that matches channels/bits leaves us
            // shoving 44.1k samples into packets sized for 24k,
            // which the DAC plays at its own clock rate as
            // pitch-shifted distortion.
            const libusb_endpoint_descriptor* iso = nullptr;
            const libusb_endpoint_descriptor* feedback = nullptr;
            for (int e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                if ((ep.bmAttributes & 0x03) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) continue;
                bool isIn = (ep.bEndpointAddress & 0x80) != 0;
                uint8_t usage = (ep.bmAttributes >> 4) & 0x03;
                if (!isIn && usage == 0x00 && !iso) iso = &ep;
                else if (isIn && usage == 0x01 && !feedback) feedback = &ep;
            }
            if (!iso) continue;

            // Verify mps can fit the requested rate.
            bool isHs = libusb_get_device_speed(dev) >= LIBUSB_SPEED_HIGH;
            int microframesPerInterval = isHs
                ? (1 << (iso->bInterval > 0 ? iso->bInterval - 1 : 0))
                : iso->bInterval;     // FS: bInterval is in ms
            int microframesPerSecond = isHs ? 8000 : 1000;
            int packetsPerSec = microframesPerInterval > 0
                ? microframesPerSecond / microframesPerInterval
                : microframesPerSecond;
            int frameStride = (altSubslot ? altSubslot : bitsPerSample / 8) * channels;
            // Required packet bytes = ceil(rate / packetsPerSec) * stride:
            // the largest packet the open-loop pacer ever sends. The
            // extra frame an async device's feedback may ask for is
            // headroom, not a requirement — startIsoPump() takes it
            // only where the endpoint has room. Demanding it here
            // rejected every synchronous alt sized exactly to its
            // rate, e.g. 48 kHz/24-bit/2ch at full speed = 288 bytes.
            int reqBytesPerPacket = ((sampleRateHz + packetsPerSec - 1)
                                     / packetsPerSec) * frameStride;
            int actualMps = iso->wMaxPacketSize & 0x07FF;
            int extraTransactions = ((iso->wMaxPacketSize >> 11) & 0x3) + 1;
            int realMps = actualMps * extraTransactions;
            if (reqBytesPerPacket > realMps) {
                LOGW("alt %u (mps=%d, bInterval=%u) can't fit %dHz/%dch/%db "
                     "(needs %d bytes/packet) — skipping",
                     alt.bAlternateSetting, realMps, iso->bInterval,
                     sampleRateHz, channels, bitsPerSample,
                     reqBytesPerPacket);
                continue;
            }

            out_fmt->sampleRateHz = sampleRateHz;
            out_fmt->bitsPerSample = bitsPerSample;
            out_fmt->bytesPerSample = altSubslot ? altSubslot : bitsPerSample / 8;
            out_fmt->channels = channels;
            out_fmt->interfaceNumber = alt.bInterfaceNumber;
            out_fmt->altSetting = alt.bAlternateSetting;
            out_fmt->endpointAddress = iso->bEndpointAddress;
            out_fmt->maxPacketSize = iso->wMaxPacketSize;
            // Resolve the rate-bearing CLOCK_SOURCE entity for this
            // streaming alt by walking the topology graph:
            //   AS_GENERAL.bTerminalLink → terminal → bCSourceID
            //   → if SELECTOR: follow its first input-pin source
            //   → if MULTIPLIER: follow its base-clock-source
            //   → continue until we land on a CLOCK_SOURCE
            // Fall back to first CLOCK_SOURCE in the device, then to
            // any clock entity, then to 0 (skip SET_CUR). The "follow
            // selector" step is what fixes Bathys-style topologies
            // where AS_GENERAL.bTerminalLink → terminal.bCSourceID
            // points at a CLOCK_SELECTOR (subtype 0x0B), and SET_CUR
            // on a Selector picks a pin index — not a sample rate.
            uint8_t resolvedClock = 0;
            for (const auto& tc : terminals) {
                if (tc.termId == altTerminalLink) {
                    resolvedClock = tc.clockId;
                    break;
                }
            }
            // Walk through Selector/Multiplier indirection up to a
            // bounded depth (4 — UAC2 doesn't actually constrain
            // this, but real topologies don't exceed 2). Stops on
            // the first CLOCK_SOURCE.
            for (int hop = 0; hop < 4 && resolvedClock != 0; ++hop) {
                const ClockEntity* ent = nullptr;
                for (const auto& ce : clockEntities) {
                    if (ce.id == resolvedClock) { ent = &ce; break; }
                }
                if (!ent) break;
                if (ent->subtype == AC_CLOCK_SOURCE) break;
                if (ent->baseId == 0) break;
                resolvedClock = ent->baseId;
            }
            if (resolvedClock == 0) {
                for (const auto& ce : clockEntities) {
                    if (ce.subtype == AC_CLOCK_SOURCE) {
                        resolvedClock = ce.id;
                        break;
                    }
                }
            }
            if (resolvedClock == 0 && !clockEntities.empty()) {
                resolvedClock = clockEntities.front().id;
            }
            out_fmt->clockSourceId = resolvedClock;
            out_fmt->controlInterfaceNum = controlIface;
            // Persist every candidate so setSampleRate can fall
            // through them in priority order: SOURCE first (most
            // likely to accept SET_CUR), then SELECTOR/MULTIPLIER,
            // then anything else.
            out_fmt->candidateClockIds.clear();
            for (const auto& ce : clockEntities)
                if (ce.subtype == AC_CLOCK_SOURCE)
                    out_fmt->candidateClockIds.push_back(ce.id);
            for (const auto& ce : clockEntities)
                if (ce.subtype != AC_CLOCK_SOURCE)
                    out_fmt->candidateClockIds.push_back(ce.id);
            out_fmt->isHighSpeed =
                libusb_get_device_speed(dev) >= LIBUSB_SPEED_HIGH;
            out_fmt->bInterval = iso->bInterval;
            out_fmt->uacVersion = uacVersion;
            out_fmt->badd = badd;
            if (feedback) {
                out_fmt->feedbackEndpointAddress = feedback->bEndpointAddress;
                out_fmt->feedbackMaxPacketSize = feedback->wMaxPacketSize;
                out_fmt->feedbackInterval = feedback->bInterval;
            }
            // The volume and mute this stream passes through: BADD's
            // fixed unit, or every Feature Unit downstream of the
            // streaming terminal (a capture path's units hang off a
            // microphone terminal instead, so they're never reached).
            out_fmt->featureControls.clear();
            if (badd) {
                out_fmt->featureControls.push_back(
                    {UAC3_BADD_PLAYBACK_FU, 0, false, true});
                for (int c = 1; c <= channels; ++c) {
                    out_fmt->featureControls.push_back(
                        {UAC3_BADD_PLAYBACK_FU, static_cast<uint8_t>(c), true, false});
                }
            } else if (streamTerminal != 0) {
                std::vector<uint8_t> reached{streamTerminal};
                for (int hop = 0; hop < 8; ++hop) {
                    bool grew = false;
                    for (const auto& fu : featureUnits) {
                        if (std::find(reached.begin(), reached.end(), fu.id) != reached.end()) continue;
                        if (std::find(reached.begin(), reached.end(), fu.sourceId) == reached.end()) continue;
                        reached.push_back(fu.id);
                        grew = true;
                        const uint8_t* p = fu.p;
                        if (uacVersion >= 0x0200) {
                            // bmaControls: 4 bytes per channel from p[5],
                            // two bits per control, 0b11 = host-settable.
                            int n = (fu.len - 6) / 4;
                            for (int c = 0; c < n; ++c) {
                                uint32_t bm = static_cast<uint32_t>(p[5 + c * 4]) |
                                              (static_cast<uint32_t>(p[6 + c * 4]) << 8);
                                bool mute = (bm & 0x3) == 0x3;
                                bool vol = ((bm >> 2) & 0x3) == 0x3;
                                if (mute || vol) {
                                    out_fmt->featureControls.push_back(
                                        {fu.id, static_cast<uint8_t>(c), vol, mute});
                                }
                            }
                        } else if (fu.len >= 7 && p[5] > 0) {
                            // UAC1: bControlSize @p[5], then one
                            // bitmap per channel; D0 mute, D1 volume.
                            int size = p[5];
                            int n = (fu.len - 7) / size;
                            for (int c = 0; c < n; ++c) {
                                uint8_t bm = p[6 + c * size];
                                if (bm & 0x3) {
                                    out_fmt->featureControls.push_back(
                                        {fu.id, static_cast<uint8_t>(c),
                                         (bm & 0x2) != 0, (bm & 0x1) != 0});
                                }
                            }
                        }
                    }
                    if (!grew) break;
                }
            }
            found = true;
            LOGI("matched alt %u on iface %u: %dch %d-bit, data ep 0x%02x "
                 "mps=%d (real %d) bInterval=%u clockId=%u%s",
                 alt.bAlternateSetting, alt.bInterfaceNumber,
                 channels, bitsPerSample, iso->bEndpointAddress,
                 iso->wMaxPacketSize, realMps, iso->bInterval, resolvedClock,
                 feedback ? " + feedback" : " (no feedback EP)");
            break;
        }
    }

    libusb_free_config_descriptor(config);
    if (!found) {
        LOGE("no AS alt setting matches %dHz/%d-bit/%dch",
             sampleRateHz, bitsPerSample, channels);
        if (badd && sampleRateHz != UAC3_BADD_RATE_HZ) {
            std::lock_guard<std::mutex> elock(errorMutex_);
            selectHint_ = "This DAC runs in USB Audio 3.0 basic mode, "
                          "which is fixed at 48 kHz.";
        }
    }
    return found;
}

void LibusbUacDriver::captureRangeForClock(uint8_t clockId) {
    if (clockId == 0 || !device_) return;
    uint8_t rng[256] = {0};
    int gr = libusb_control_transfer(
        device_, /*bmRequestType=*/0xA1, /*RANGE=*/0x02,
        static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
        static_cast<uint16_t>(
            (clockId << 8) | format_.controlInterfaceNum),
        rng, sizeof(rng), 1000);
    if (gr < 2) {
        LOGW("captureRangeForClock(%u): GET_RANGE returned %d (no rate "
             "inventory available — UI won't show supported rates)",
             clockId, gr);
        return;
    }
    int n = rng[0] | (rng[1] << 8);
    std::lock_guard<std::mutex> elock(errorMutex_);
    for (int i = 0; i < n && (2 + (i + 1) * 12) <= gr; ++i) {
        const uint8_t* t = rng + 2 + i * 12;
        uint32_t mn = uint32_t(t[0]) | (uint32_t(t[1]) << 8) |
                      (uint32_t(t[2]) << 16) | (uint32_t(t[3]) << 24);
        uint32_t mx = uint32_t(t[4]) | (uint32_t(t[5]) << 8) |
                      (uint32_t(t[6]) << 16) | (uint32_t(t[7]) << 24);
        uint32_t res = uint32_t(t[8]) | (uint32_t(t[9]) << 8) |
                       (uint32_t(t[10]) << 16) | (uint32_t(t[11]) << 24);
        bool dup = false;
        for (const auto& e : supportedRates_) {
            if (e.minHz == mn && e.maxHz == mx && e.resHz == res) {
                dup = true; break;
            }
        }
        if (!dup) supportedRates_.push_back({clockId, mn, mx, res});
    }
    LOGI("captureRangeForClock(%u): %d subrange(s) cached", clockId, n);
}

bool LibusbUacDriver::setSampleRate(uint32_t hz) {
    if (format_.badd) {
        // BADD has no clock entity to address; the rate is fixed by
        // the profile and selectAltSetting already refused any other.
        if (hz == static_cast<uint32_t>(UAC3_BADD_RATE_HZ)) return true;
        LOGE("UAC3 BADD runs at %d Hz only, refusing %u Hz",
             UAC3_BADD_RATE_HZ, hz);
        return false;
    }
    if (format_.uacVersion >= 0x0200) {
        // UAC2 §5.2.5.1.1 — SET_CUR(SAM_FREQ_CONTROL) on a clock
        // entity, 32-bit LE.
        //   bmRequestType = 0x21 (Class | Interface | Host-to-Device)
        //   wValue        = CS_SAM_FREQ_CONTROL << 8
        //   wIndex        = (clockId << 8) | controlInterfaceNum
        //   wLength       = 4
        // Topology resolution can be non-obvious on some devices
        // (Bathys + 2 clock entities + 4 terminals seen in logs);
        // try the resolved clockSourceId first, then fall through to
        // every other candidate. Same for the GET_CUR readback.
        std::vector<uint8_t> tryIds;
        if (format_.clockSourceId != 0) tryIds.push_back(format_.clockSourceId);
        for (uint8_t id : format_.candidateClockIds) {
            if (id != 0 && id != format_.clockSourceId) tryIds.push_back(id);
        }
        if (tryIds.empty()) {
            LOGW("UAC2: no clock entities at all — skipping SET_CUR "
                 "(assuming fixed-rate clock at %u Hz)", hz);
            return true;
        }

        uint8_t data[4] = {
            static_cast<uint8_t>(hz & 0xFF),
            static_cast<uint8_t>((hz >> 8) & 0xFF),
            static_cast<uint8_t>((hz >> 16) & 0xFF),
            static_cast<uint8_t>((hz >> 24) & 0xFF),
        };
        int rc = -1;
        uint8_t winningId = 0;
        for (uint8_t id : tryIds) {
            int r = libusb_control_transfer(
                device_, 0x21, REQ_SET_CUR,
                static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
                static_cast<uint16_t>(
                    (id << 8) | format_.controlInterfaceNum),
                data, 4, 1000);
            if (r == 4) { rc = r; winningId = id; break; }
            LOGW("UAC2 SET_CUR clockId=%u -> %d (will try next)", id, r);
        }
        if (rc == 4) {
            if (winningId != format_.clockSourceId) {
                LOGI("UAC2: clock entity id=%u accepted SET_CUR (topology "
                     "resolution had picked id=%u — promoting)",
                     winningId, format_.clockSourceId);
                format_.clockSourceId = winningId;
            }
            // Capture the rate inventory on the success path too so
            // the Settings UI can show "supports 44.1 / 48 / 88.2 /
            // 96 / 176.4 / 192 kHz" beneath the active rate. Failures
            // already get this via the diagnostic loop below; on
            // success we only need to ask the winning clock entity.
            captureRangeForClock(winningId);
            return true;
        }
        // STALL or I/O error: many UAC2 DACs (Focal Bathys included)
        // run a fixed-rate hardware clock that doesn't accept
        // programmatic rate changes — the rate is whatever the
        // hardware was wired for, and the host just sends data at
        // that rate. Verify the device's GET_CUR matches what we
        // want; if so, proceed without SET_CUR. If GET_CUR is also
        // a different rate, log a warning but still proceed (audio
        // may pitch-shift, but at least it'll play).
        // UAC2 §5.2.1: bRequest is 0x01 (CUR) for SET and GET both —
        // direction is in bmRequestType (0x21 SET, 0xA1 GET). For
        // each candidate, try GET_CUR; if any reports a non-zero
        // rate that matches our request, proceed (fixed-rate clock
        // case). Also try GET_RANGE so the log shows what rates the
        // device actually claims to support — invaluable diagnostic.
        uint32_t devHz = 0;
        for (uint8_t id : tryIds) {
            uint8_t cur[4] = {0, 0, 0, 0};
            int gc = libusb_control_transfer(
                device_, /*bmRequestType=*/0xA1, /*bRequest=*/REQ_SET_CUR,
                static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
                static_cast<uint16_t>(
                    (id << 8) | format_.controlInterfaceNum),
                cur, 4, 1000);
            uint32_t hzGot = (gc == 4)
                ? (uint32_t(cur[0]) | (uint32_t(cur[1]) << 8) |
                   (uint32_t(cur[2]) << 16) | (uint32_t(cur[3]) << 24))
                : 0;
            // GET_RANGE diagnostic — UAC2 §5.2.1 RANGE attribute.
            //   bRequest=0x02 (RANGE), bmRequestType=0xA1.
            //   Returns wNumSubRanges (LE uint16), then N triples
            //   of (dMIN, dMAX, dRES) each 4-byte LE uint32.
            uint8_t rng[256] = {0};
            int gr = libusb_control_transfer(
                device_, 0xA1, /*RANGE=*/0x02,
                static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
                static_cast<uint16_t>(
                    (id << 8) | format_.controlInterfaceNum),
                rng, sizeof(rng), 1000);
            if (gc == 4 || gr >= 2) {
                LOGI("UAC2 clockId=%u: GET_CUR=%u Hz, GET_RANGE bytes=%d",
                     id, hzGot, gr);
                if (gr >= 2) {
                    int n = rng[0] | (rng[1] << 8);
                    std::lock_guard<std::mutex> elock(errorMutex_);
                    for (int i = 0; i < n && (2 + (i + 1) * 12) <= gr; ++i) {
                        const uint8_t* t = rng + 2 + i * 12;
                        uint32_t mn = uint32_t(t[0]) | (uint32_t(t[1]) << 8) |
                                      (uint32_t(t[2]) << 16) | (uint32_t(t[3]) << 24);
                        uint32_t mx = uint32_t(t[4]) | (uint32_t(t[5]) << 8) |
                                      (uint32_t(t[6]) << 16) | (uint32_t(t[7]) << 24);
                        uint32_t res = uint32_t(t[8]) | (uint32_t(t[9]) << 8) |
                                       (uint32_t(t[10]) << 16) | (uint32_t(t[11]) << 24);
                        LOGI("    range[%d]: %u..%u Hz res=%u", i, mn, mx, res);
                        // Avoid duplicating entries if a previous
                        // candidate clock already reported the same
                        // subrange — happens on devices where the
                        // SELECTOR mirrors the SOURCE's range.
                        bool dup = false;
                        for (const auto& e : supportedRates_) {
                            if (e.minHz == mn && e.maxHz == mx && e.resHz == res) {
                                dup = true; break;
                            }
                        }
                        if (!dup) supportedRates_.push_back({id, mn, mx, res});
                    }
                }
            }
            if (gc == 4 && hzGot == hz) {
                LOGI("UAC2 clockId=%u is fixed at %u Hz already — accepting", id, hz);
                format_.clockSourceId = id;
                return true;
            }
            if (gc == 4 && devHz == 0) devHz = hzGot;  // remember for error msg
        }

        // No candidate accepted SET_CUR and none reports our rate via
        // GET_CUR. Pushing audio at the wrong rate to a fixed-rate
        // clock is the chipmunk-distortion bug we used to ship.
        // Fail bypass cleanly so LibusbAudioSink falls back to the
        // delegate (Android's HAL) which resamples to the device's
        // rate properly.
        LOGE("UAC2: no clock entity accepted %u Hz (best GET_CUR=%u Hz). "
             "Refusing bypass to avoid distortion. Try a track at the "
             "device's native rate; the GET_RANGE log lines above show "
             "what rates each clock supports.",
             hz, devHz);
        return false;
    }
    // UAC1 §5.2.3.2.3.1 — set on the iso DATA endpoint, 24-bit LE.
    //   bmRequestType = 0x22 (Class | Endpoint | Host-to-Device)
    //   bRequest      = SET_CUR (0x01)
    //   wValue        = (SAMPLING_FREQ_CONTROL << 8) | 0  (0x0100)
    //   wIndex        = endpointAddress
    //   wLength       = 3
    uint8_t data[3] = {
        static_cast<uint8_t>(hz & 0xFF),
        static_cast<uint8_t>((hz >> 8) & 0xFF),
        static_cast<uint8_t>((hz >> 16) & 0xFF),
    };
    int rc = libusb_control_transfer(
        device_,
        /*bmRequestType=*/0x22,
        /*bRequest=*/REQ_SET_CUR,
        /*wValue=*/static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
        /*wIndex=*/static_cast<uint16_t>(format_.endpointAddress),
        data, 3,
        /*timeout=*/1000);
    if (rc != 3) {
        // Many UAC1 devices accept the 32-bit form on the endpoint
        // too — try as a fallback before giving up.
        uint8_t data4[4] = { data[0], data[1], data[2], 0 };
        rc = libusb_control_transfer(
            device_, 0x22, REQ_SET_CUR,
            static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
            static_cast<uint16_t>(format_.endpointAddress),
            data4, 4, 1000);
        if (rc != 4) {
            LOGE("UAC1 SET_CUR sample rate %u Hz -> %d (3-byte and 4-byte both failed)", hz, rc);
            return false;
        }
    }
    return true;
}

void LibusbUacDriver::applyHardwareVolume() {
    format_.hwVolumeDb256 = kHwVolumeNone;
    if (format_.featureControls.empty()) {
        LOGI("hw volume: no settable volume or mute on the playback path");
        return;
    }
    const bool uac1 = format_.uacVersion < 0x0200;
    const uint8_t iface = format_.controlInterfaceNum;
    auto request = [&](uint8_t type, uint8_t req, uint8_t selector,
                       const HwFeatureControl& c, uint8_t* data, uint16_t len) {
        return libusb_control_transfer(
            device_, type, req,
            static_cast<uint16_t>((selector << 8) | c.channel),
            static_cast<uint16_t>((c.unitId << 8) | iface),
            data, len, 1000);
    };
    auto rd16 = [](const uint8_t* q) {
        return static_cast<int16_t>(q[0] | (q[1] << 8));
    };

    for (const auto& c : format_.featureControls) {
        if (c.mute) {
            uint8_t off = 0;
            int r = request(0x21, REQ_SET_CUR, FU_MUTE_CONTROL, c, &off, 1);
            LOGI("hw volume: unit %u ch %u unmute -> %d", c.unitId, c.channel, r);
        }
        if (!c.volume) continue;

        // The top of the range, but never above 0 dB: a DAC that
        // offers gain past full scale would clip the digital signal.
        int max = 0;
        bool haveMax = false;
        uint8_t buf[2 + 6 * 8] = {0};
        if (uac1) {
            if (request(0xA1, REQ_UAC1_GET_MAX, FU_VOLUME_CONTROL, c, buf, 2) == 2) {
                max = rd16(buf);
                haveMax = true;
            }
        } else {
            int got = request(0xA1, REQ_RANGE, FU_VOLUME_CONTROL, c, buf, sizeof(buf));
            if (got >= 8) {
                int n = rd16(buf);
                for (int i = 0; i < n && 2 + (i + 1) * 6 <= got; ++i) {
                    int hi = rd16(buf + 2 + i * 6 + 2);
                    if (!haveMax || hi > max) max = hi;
                    haveMax = true;
                }
            }
        }
        int target = haveMax ? std::min(max, 0) : 0;
        uint8_t set[2] = {static_cast<uint8_t>(target & 0xFF),
                          static_cast<uint8_t>((target >> 8) & 0xFF)};
        int r = request(0x21, REQ_SET_CUR, FU_VOLUME_CONTROL, c, set, 2);

        uint8_t cur[2] = {0, 0};
        int g = request(0xA1, uac1 ? REQ_UAC1_GET_CUR : REQ_SET_CUR,
                        FU_VOLUME_CONTROL, c, cur, 2);
        int now = (g == 2) ? rd16(cur) : (r == 2 ? target : kHwVolumeNone);
        LOGI("hw volume: unit %u ch %u max %s%.2f dB -> set %.2f dB (%d), now %s",
             c.unitId, c.channel, haveMax ? "" : "unknown, assuming ",
             max / 256.0, target / 256.0, r,
             now == kHwVolumeNone ? "unknown" : std::to_string(now / 256.0).c_str());
        // Report the quietest channel: that's the one a listener hears
        // as "not at full".
        if (now != kHwVolumeNone &&
            (format_.hwVolumeDb256 == kHwVolumeNone || now < format_.hwVolumeDb256)) {
            format_.hwVolumeDb256 = now;
        }
    }
}

bool LibusbUacDriver::start(int sampleRateHz, int bitsPerSample, int channels) {
    std::lock_guard<std::mutex> lock(mutex_);
    return startLocked(sampleRateHz, bitsPerSample, channels);
}

bool LibusbUacDriver::startLocked(int sampleRateHz, int bitsPerSample, int channels) {
    if (bringUpLocked(sampleRateHz, bitsPerSample, channels)) return true;
    // A stream that did not come up has no use for audio memory, and no
    // write should land in a ring that nothing drains. Every way out of
    // bringUpLocked leaves no pump running, so this is safe.
    releaseRing();
    return false;
}

bool LibusbUacDriver::bringUpLocked(int sampleRateHz, int bitsPerSample, int channels) {
    ErrorSink err{&lastError_, &errorMutex_, &lastErrorDetail_};
    // Whatever happens below, the stream is no longer parked: it is
    // either running again or down.
    parked_ = false;
    // Optimistic clear; failure sites overwrite. Successful return
    // also clears at the bottom.
    lastError_.store(StartError::Ok, std::memory_order_release);
    {
        std::lock_guard<std::mutex> elock(errorMutex_);
        lastErrorDetail_.clear();
        // Drop the previous start's rate inventory — we'll repopulate
        // on this start (or leave empty if the device returns nothing).
        supportedRates_.clear();
        selectHint_.clear();
    }
    if (!device_) {
        err(StartError::NoDevice, "start() called before open() — "
            "no UsbDeviceConnection wrapped yet");
        return false;
    }
    if (streaming_.load(std::memory_order_acquire)) {
        // Already streaming — caller wants a different format. Stop
        // the iso pump but KEEP the interface claim so the kernel
        // can't grab it in the gap. Common path on a mixed-format
        // album (16-bit FLAC followed by 24-bit FLAC).
        stopIsoPump();
        streaming_.store(false, std::memory_order_release);
    }

    StreamFormat fmt{};
    if (!selectAltSetting(sampleRateHz, bitsPerSample, channels, &fmt)) {
        std::string hint;
        {
            std::lock_guard<std::mutex> elock(errorMutex_);
            hint = selectHint_;
        }
        err(StartError::NoMatchingAlt,
            "no AS alt setting matches " +
            std::to_string(sampleRateHz) + " Hz / " +
            std::to_string(bitsPerSample) + "-bit / " +
            std::to_string(channels) + "ch — " +
            (hint.empty()
                 ? std::string("DAC may not support this rate at this bit depth")
                 : hint));
        return false;
    }

    // (Re-)claim only when needed: not held yet, or a different
    // interface number on the new alt setting (vanishingly rare —
    // most DACs put all alt settings under one streaming interface).
    bool needClaim = !interfaceClaimed_ ||
                     format_.interfaceNumber != fmt.interfaceNumber;
    if (needClaim) {
        if (interfaceClaimed_) {
            libusb_release_interface(device_, format_.interfaceNumber);
            interfaceClaimed_ = false;
        }
        int rc = libusb_claim_interface(device_, fmt.interfaceNumber);
        if (rc != LIBUSB_SUCCESS) {
            const char* libErr = libusb_strerror(rc);
            LOGE("claim_interface(%u) -> %d (%s) — Developer Options "
                 "'Disable USB audio routing' must be ON, AND the "
                 "older 'USB DAC bit-perfect routing' toggle must be "
                 "OFF (it grabs the device via the framework and "
                 "fights us for the claim)",
                 fmt.interfaceNumber, rc, libErr);
            err(StartError::ClaimInterfaceFailed,
                std::string("libusb_claim_interface(") +
                std::to_string(fmt.interfaceNumber) + ") -> " +
                libErr + ". Most likely Android's audio HAL still " +
                "owns the streaming interface — turn ON Developer " +
                "Options → Disable USB audio routing, and ensure " +
                "the framework-routing toggle (above) is OFF.");
            return false;
        }
        interfaceClaimed_ = true;
    }
    // The AudioControl interface needs to be claimed too —
    // class-specific control transfers (SET_CUR / GET_CUR /
    // GET_RANGE on the clock entities) target it via wIndex's low
    // byte, and the kernel's snd-usb-audio driver silently rejects
    // them with LIBUSB_ERROR_IO if we don't own AC. Without this,
    // the descriptor walker matches the alt fine but every clock
    // request fails -> bypass refuses to engage. Discovered with
    // the Focal Bathys whose topology (2 CLOCK_SOURCEs, 4 terminals)
    // had clock entities looked correct but every SET_CUR returned
    // -1 because the kernel never let the transfer reach the device.
    if (fmt.controlInterfaceNum != 0xFF &&
        (!controlInterfaceClaimed_ ||
         claimedControlIface_ != fmt.controlInterfaceNum)) {
        if (controlInterfaceClaimed_) {
            libusb_release_interface(device_, claimedControlIface_);
            controlInterfaceClaimed_ = false;
        }
        int rc = libusb_claim_interface(device_, fmt.controlInterfaceNum);
        if (rc != LIBUSB_SUCCESS) {
            LOGW("claim AudioControl iface %u -> %d (continuing — "
                 "control transfers may fail with LIBUSB_ERROR_IO)",
                 fmt.controlInterfaceNum, rc);
        } else {
            controlInterfaceClaimed_ = true;
            claimedControlIface_ = fmt.controlInterfaceNum;
            LOGI("claimed AudioControl iface %u", fmt.controlInterfaceNum);
        }
    }
    format_ = fmt;

    int rc = libusb_set_interface_alt_setting(
        device_, format_.interfaceNumber, format_.altSetting);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("set_interface_alt_setting(%u, %u) -> %d",
             format_.interfaceNumber, format_.altSetting, rc);
        err(StartError::SetAltFailed,
            std::string("libusb_set_interface_alt_setting(iface=") +
            std::to_string(format_.interfaceNumber) + ", alt=" +
            std::to_string(format_.altSetting) + ") -> " +
            libusb_strerror(rc));
        return false;
    }
    if (!setSampleRate(static_cast<uint32_t>(sampleRateHz))) {
        err(StartError::SetSampleRateFailed,
            "no clock entity accepted SET_CUR(" +
            std::to_string(sampleRateHz) +
            " Hz) and GET_CUR didn't already report this rate. "
            "Check the GET_RANGE log lines for what rates each "
            "clock supports.");
        return false;
    }

    applyHardwareVolume();

    if (!prepareRing()) {
        err(StartError::IsoPumpAllocFailed,
            "could not allocate the " + std::to_string(sampleRateHz) +
            " Hz ring buffer — out of memory");
        return false;
    }
    stopRequested_.store(false, std::memory_order_relaxed);

    if (!startIsoPump()) {
        // startIsoPump() sets the specific subcategory itself based
        // on whether alloc or submit failed. Don't overwrite here.
        if (lastError_.load(std::memory_order_relaxed) == StartError::Ok) {
            err(StartError::IsoPumpAllocFailed,
                "iso pump failed to start (see logcat for details)");
        }
        return false;
    }
    streaming_.store(true, std::memory_order_release);
    LOGI("streaming: %d Hz / %d-bit / %d ch via ep 0x%02x",
         format_.sampleRateHz, format_.bitsPerSample, format_.channels,
         format_.endpointAddress);
    return true;
}

void LibusbUacDriver::flushRing() {
    // Everything written so far is discarded: drainRing skips its tail
    // up to this cursor on the next packet, so the DAC goes quiet after
    // the transfers already in flight. Both position counters count
    // from here too — flushRing is called between tracks (Media3
    // flush()), and stale frame counts would let getCurrentPositionUs
    // report frames from the previous track. Frames sent from before
    // the flush end at or below this cursor, so they never count.
    //
    // The cursors themselves are never reset here. Storing 0 into the
    // tail from this thread raced the iso thread's own store of it,
    // which could leave the tail past the head: the ring then looked
    // fuller than its size to both sides and played stale bytes.
    std::lock_guard<std::mutex> lock(ringMutex_);
    flushCursor_.store(ringHead_.load(std::memory_order_relaxed),
                       std::memory_order_release);
}

int64_t LibusbUacDriver::playedFrames() const {
    const int64_t stride = ringStride_.load(std::memory_order_acquire);
    if (stride <= 0) return 0;
    // The flush cursor first: whatever was sent before it is not ours.
    const uint64_t base = flushCursor_.load(std::memory_order_acquire);
    const uint64_t sent = sentCursor_.load(std::memory_order_acquire);
    return sent > base ? static_cast<int64_t>(sent - base) / stride : 0;
}

int64_t LibusbUacDriver::writtenFrames() const {
    const int64_t stride = ringStride_.load(std::memory_order_acquire);
    if (stride <= 0) return 0;
    // Base before head: the head only grows, and a flush sets the base
    // to a head value, so the difference can't go negative.
    const uint64_t base = flushCursor_.load(std::memory_order_acquire);
    const uint64_t head = ringHead_.load(std::memory_order_acquire);
    return head > base ? static_cast<int64_t>(head - base) / stride : 0;
}

size_t LibusbUacDriver::ringCapacityBytes() const {
    std::lock_guard<std::mutex> lock(ringMutex_);
    return ringBytes_;
}

bool LibusbUacDriver::prepareRing() {
    // ~250 ms of audio at the negotiated format (see kRingMinBytes).
    const size_t stride = static_cast<size_t>(format_.channels) *
                          format_.bytesPerSample;
    size_t want = (static_cast<size_t>(format_.sampleRateHz) * stride) / 4;
    want = ((want + kRingQuantum - 1) / kRingQuantum) * kRingQuantum;
    if (want < kRingMinBytes) want = kRingMinBytes;

    std::lock_guard<std::mutex> lock(ringMutex_);
    if (!ring_ || want != ringBytes_) {
        // A new block rather than a resize, so a smaller ring really is
        // smaller. The old one is freed before the new one is taken.
        ring_.reset();
        ringBytes_ = 0;
        ringStride_.store(0, std::memory_order_release);
        uint8_t* block = new (std::nothrow) uint8_t[want];
        if (!block) {
            LOGE("ring allocation of %zu bytes failed", want);
            return false;
        }
        ring_.reset(block);
        ringBytes_ = want;
        LOGI("ring sized to %zu bytes (~%zu ms) for %d Hz/%d-bit/%dch",
             want, want * 1000 / (format_.sampleRateHz * stride),
             format_.sampleRateHz, format_.bitsPerSample, format_.channels);
    }
    // Safe: no pump runs (start() stopped it), and ringMutex_ keeps any
    // write out until the cursors agree again.
    ringHead_.store(0, std::memory_order_relaxed);
    ringTail_.store(0, std::memory_order_relaxed);
    flushCursor_.store(0, std::memory_order_relaxed);
    sentCursor_.store(0, std::memory_order_relaxed);
    ringStride_.store(static_cast<int>(stride), std::memory_order_release);
    return true;
}

void LibusbUacDriver::releaseRing() {
    std::lock_guard<std::mutex> lock(ringMutex_);
    if (ring_) LOGI("ring released (%zu bytes)", ringBytes_);
    ring_.reset();
    ringBytes_ = 0;
    ringStride_.store(0, std::memory_order_release);
    ringHead_.store(0, std::memory_order_relaxed);
    ringTail_.store(0, std::memory_order_relaxed);
    flushCursor_.store(0, std::memory_order_relaxed);
    sentCursor_.store(0, std::memory_order_relaxed);
}

bool LibusbUacDriver::parkPump() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!device_ || !streaming_.load(std::memory_order_acquire) || parked_) {
        return false;
    }
    stopIsoPump();
    // Alt 0 is the DAC's own idle: no bandwidth reserved, and most stop
    // their clock recovery. The claims stay, so Android can't take the
    // interface back meanwhile. A DAC that refuses (or has gone) is
    // still parked: the pump is stopped either way, and resumePump()
    // finds out whether it will stream again.
    int rc = LIBUSB_SUCCESS;
    if (format_.altSetting != 0) {
        rc = libusb_set_interface_alt_setting(device_, format_.interfaceNumber, 0);
    }
    parked_ = true;
    LOGI("pump parked: transfers cancelled, iface %u on alt 0 (rc=%d), "
         "interfaces still claimed", format_.interfaceNumber, rc);
    return true;
}

bool LibusbUacDriver::resumePump() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!parked_) return streaming_.load(std::memory_order_acquire);
    LOGI("resuming parked stream: %d Hz / %d-bit / %d ch",
         format_.sampleRateHz, format_.bitsPerSample, format_.channels);
    // The same path as any start of this format, so a resumed stream is
    // set up exactly like a fresh one, rate and volume included: a DAC
    // may forget either on its way through alt 0.
    const int rate = format_.sampleRateHz;
    const int bits = format_.bitsPerSample;
    const int channels = format_.channels;
    return startLocked(rate, bits, channels);
}

bool LibusbUacDriver::isPumpParked() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return parked_;
}

bool LibusbUacDriver::isStreamingFormat(int sampleRate, int bitsPerSample, int channels) const {
    if (!streaming_.load(std::memory_order_acquire)) return false;
    return format_.sampleRateHz == sampleRate
        && format_.bitsPerSample == bitsPerSample
        && format_.channels == channels;
}

void LibusbUacDriver::stop() {
    bool was = streaming_.exchange(false, std::memory_order_acq_rel);
    if (!was && transfers_.empty() && !interfaceClaimed_ && !controlInterfaceClaimed_) return;

    std::lock_guard<std::mutex> lock(mutex_);
    stopLocked();
}

void LibusbUacDriver::stopLocked() {
    stopIsoPump();
    // Full teardown: alt 0 then release. Caller is the toggle going
    // off, the queue clearing, or app pause — NOT a track-to-track
    // reconfigure, which goes through start() with the claim kept.
    // A parked stream is on alt 0 already.
    if (device_ && interfaceClaimed_) {
        if (format_.altSetting != 0 && !parked_) {
            libusb_set_interface_alt_setting(
                device_, format_.interfaceNumber, 0);
        }
        libusb_release_interface(device_, format_.interfaceNumber);
        interfaceClaimed_ = false;
    }
    if (device_ && controlInterfaceClaimed_) {
        libusb_release_interface(device_, claimedControlIface_);
        controlInterfaceClaimed_ = false;
    }
    parked_ = false;
    // The pump is gone, so nothing reads the ring any more.
    releaseRing();
    LOGI("stopped streaming (full teardown)");
}

// --- Iso pump ---------------------------------------------------------

bool LibusbUacDriver::startIsoPump() {
    // Compute the actual *packet* rate from bInterval. Naively
    // assuming 1 packet per microframe was the source of distortion
    // when a device's alt uses bInterval > 1 (Bathys' alt 1 is
    // bInterval=4 = 1 packet per ms = 1000 packets/sec, NOT the 8000
    // microframes/sec we'd been pumping at).
    //   HS: packet interval = 2^(bInterval-1) microframes
    //   FS: packet interval = bInterval frames (1ms each)
    int hostPeriodHz = format_.isHighSpeed ? 8000 : 1000;
    int packetIntervalUframes = format_.isHighSpeed
        ? (1 << (format_.bInterval > 0 ? format_.bInterval - 1 : 0))
        : format_.bInterval;
    if (packetIntervalUframes < 1) packetIntervalUframes = 1;
    microframesPerSec_ = hostPeriodHz / packetIntervalUframes;
    int baseFrames = format_.sampleRateHz / microframesPerSec_;
    int rateRemainder = format_.sampleRateHz % microframesPerSec_;
    const int packetUs = packetPeriodUs(format_.isHighSpeed, format_.bInterval);
    packetsPerTransfer_ = packetsFor(kTransferUs, packetUs, kMinPacketsPerTransfer,
                                     kMaxPacketsPerTransfer);
    LOGI("iso pump: %d packets/sec (HS=%d, bInterval=%u), "
         "%d frames/packet base + %d/%d frac, %d packets/transfer "
         "(%d us each, %d in flight)",
         microframesPerSec_, format_.isHighSpeed, format_.bInterval,
         baseFrames, rateRemainder, microframesPerSec_,
         packetsPerTransfer_, packetsPerTransfer_ * packetUs, kNumTransfers);
    uint32_t seed_q16 =
        (static_cast<uint32_t>(baseFrames) << 16) +
        static_cast<uint32_t>(
            (static_cast<uint64_t>(rateRemainder) << 16) /
            static_cast<uint32_t>(microframesPerSec_));
    framesPerUframe_q16_.store(seed_q16, std::memory_order_relaxed);
    fracAccumulator_q16_ = 0;
    isoCallbacks_ = 0;
    paddedBytes_ = 0;
    isoLogEvery_ = static_cast<uint32_t>(std::max(
        1, kIsoLogSeconds * microframesPerSec_ / packetsPerTransfer_));
    // The open-loop pacer never sends more than ceil(rate / pps)
    // frames; that much has to fit. One more frame of headroom lets
    // an async device's feedback over-ask, but only when the endpoint
    // has room for it — a synchronous alt is often sized exactly to
    // its rate, and onIso caps every packet at this value anyway.
    int nominalFrames = baseFrames + (rateRemainder > 0 ? 1 : 0);
    maxFramesPerPacket_ = nominalFrames + 1;

    int frameStride = format_.channels * format_.bytesPerSample;

    // Hard-cap at the endpoint's max packet size to stay spec-legal.
    // Taken from the matched alt itself: libusb's
    // get_max_iso_packet_size answers from the first alt that uses the
    // endpoint address, which on a device with one alt per bit depth
    // is the wrong one for every alt but the first.
    int maxPacket = (format_.maxPacketSize & 0x07FF) *
                    (((format_.maxPacketSize >> 11) & 0x3) + 1);
    if (maxPacket > 0) {
        int fitFrames = maxPacket / frameStride;
        if (nominalFrames > fitFrames) {
            LOGE("computed packet %d > endpoint max %d",
                 nominalFrames * frameStride, maxPacket);
            return false;
        }
        maxFramesPerPacket_ = std::min(maxFramesPerPacket_, fitFrames);
    }
    int maxBytesPerPacket = maxFramesPerPacket_ * frameStride;

    transfers_.reserve(kNumTransfers);
    transferBuffers_.reserve(kNumTransfers);
    // The first transfers carry silence, none of the ring.
    transferEndCursor_.assign(kNumTransfers, 0);

    auto setErr = [this](StartError c, const std::string& d) {
        lastError_.store(c, std::memory_order_release);
        std::lock_guard<std::mutex> lock(errorMutex_);
        lastErrorDetail_ = d;
    };

    for (int t = 0; t < kNumTransfers; ++t) {
        libusb_transfer* xfr = libusb_alloc_transfer(packetsPerTransfer_);
        if (!xfr) {
            LOGE("libusb_alloc_transfer failed at #%d", t);
            setErr(StartError::IsoPumpAllocFailed,
                   "libusb_alloc_transfer returned null at transfer #" +
                   std::to_string(t) + " — likely OOM");
            stopIsoPump();
            return false;
        }
        // Worst-case sized buffer — every packet is the +1 size.
        // Per-packet `length` (set in onIso) trims to actual.
        std::vector<uint8_t> buf(maxBytesPerPacket * packetsPerTransfer_, 0);
        libusb_fill_iso_transfer(
            xfr, device_, format_.endpointAddress,
            buf.data(), buf.size(), packetsPerTransfer_,
            &LibusbUacDriver::onIsoTrampoline, this, /*timeout=*/0);
        // Initial packet lengths use the integer base frames so the
        // first transmission is silence at roughly the average rate.
        // onIso refines per-packet from the fractional accumulator
        // and (when present) the latest feedback value.
        libusb_set_iso_packet_lengths(xfr, baseFrames * frameStride);

        transferBuffers_.emplace_back(std::move(buf));
        transfers_.push_back(xfr);
    }

    // Optional feedback EP. UAC2 §5.2.2.4.1: feedback IN, 4 bytes
    // (16.16 fixed) on high-speed, 3 bytes (10.10 fixed shifted left
    // by 4) on full-speed. Two transfers in flight, each a few ms of
    // the endpoint's packets (see kFeedbackTransferUs): one packet a
    // transfer woke the event thread for every reading, 1,000 times a
    // second at the usual 1 ms interval. The event thread already
    // drives all iso completions on a single thread so no locking
    // needed for the atomic store.
    feedbackPacketsPerTransfer_ = 0;
    if (format_.feedbackEndpointAddress != 0) {
        feedbackPacketsPerTransfer_ = packetsFor(
            kFeedbackTransferUs,
            packetPeriodUs(format_.isHighSpeed, format_.feedbackInterval),
            1, kMaxFeedbackPackets);
        for (int t = 0; t < kFeedbackTransfers; ++t) {
            libusb_transfer* fxfr = libusb_alloc_transfer(feedbackPacketsPerTransfer_);
            if (!fxfr) {
                LOGE("alloc feedback transfer %d failed", t);
                setErr(StartError::IsoPumpAllocFailed,
                       "libusb_alloc_transfer (feedback) returned null at #" +
                       std::to_string(t));
                stopIsoPump();
                return false;
            }
            // Each packet gets the endpoint's whole wMaxPacketSize;
            // onFeedback finds packet i at i times that.
            int fbPacketBytes = (format_.feedbackMaxPacketSize & 0x07FF) > 0
                ? (format_.feedbackMaxPacketSize & 0x07FF) : 4;
            std::vector<uint8_t> fbBuf(
                static_cast<size_t>(fbPacketBytes) * feedbackPacketsPerTransfer_, 0);
            libusb_fill_iso_transfer(
                fxfr, device_, format_.feedbackEndpointAddress,
                fbBuf.data(), fbBuf.size(), feedbackPacketsPerTransfer_,
                &LibusbUacDriver::onFeedbackTrampoline, this, /*timeout=*/0);
            libusb_set_iso_packet_lengths(fxfr, fbPacketBytes);
            feedbackBuffers_.emplace_back(std::move(fbBuf));
            feedbackTransfers_.push_back(fxfr);
        }
    }

    // Spin up the event thread before we submit so any immediate
    // completion has somewhere to go.
    eventThread_ = std::thread([this]() {
        while (!stopRequested_.load(std::memory_order_acquire)) {
            timeval tv{};
            tv.tv_sec = 0;
            tv.tv_usec = 100000;  // 100ms
            libusb_handle_events_timeout(ctx_, &tv);
        }
    });

    // Initial submit. Buffers are zero-padded so the first ms of
    // output is silence — gives the audio thread a head-start to
    // fill the ring before the iso pipeline drains it. Feedback
    // transfers go in first so the device knows we're listening
    // before the data EP starts demanding samples.
    for (libusb_transfer* fxfr : feedbackTransfers_) {
        int rc = libusb_submit_transfer(fxfr);
        if (rc != LIBUSB_SUCCESS) {
            LOGE("initial submit feedback -> %d", rc);
            setErr(StartError::IsoPumpSubmitFailed,
                   std::string("libusb_submit_transfer (feedback) -> ") +
                   libusb_strerror(rc));
            stopRequested_.store(true, std::memory_order_release);
            stopIsoPump();
            return false;
        }
        inflight_.fetch_add(1, std::memory_order_relaxed);
    }
    for (libusb_transfer* xfr : transfers_) {
        int rc = libusb_submit_transfer(xfr);
        if (rc != LIBUSB_SUCCESS) {
            LOGE("initial submit_transfer -> %d", rc);
            setErr(StartError::IsoPumpSubmitFailed,
                   std::string("libusb_submit_transfer -> ") +
                   libusb_strerror(rc));
            stopRequested_.store(true, std::memory_order_release);
            stopIsoPump();
            return false;
        }
        inflight_.fetch_add(1, std::memory_order_relaxed);
    }
    return true;
}

void LibusbUacDriver::stopIsoPump() {
    stopRequested_.store(true, std::memory_order_release);
    for (libusb_transfer* xfr : transfers_) {
        if (xfr) libusb_cancel_transfer(xfr);
    }
    for (libusb_transfer* fxfr : feedbackTransfers_) {
        if (fxfr) libusb_cancel_transfer(fxfr);
    }
    // Drain the event loop until every transfer has reported completion
    // (cancellation counts). Bounded wait — if libusb wedges we'd
    // rather log and move on than hang the audio thread.
    for (int spin = 0; spin < 200; ++spin) {
        if (inflight_.load(std::memory_order_acquire) == 0) break;
        timeval tv{0, 5000};
        libusb_handle_events_timeout(ctx_, &tv);
    }
    if (eventThread_.joinable()) eventThread_.join();
    const int stuck = inflight_.load(std::memory_order_acquire);
    if (stuck == 0) {
        // Everything has reported back, this pump's and any earlier
        // stop's leftovers alike, so none of them can be touched again.
        for (libusb_transfer* xfr : transfers_) {
            if (xfr) libusb_free_transfer(xfr);
        }
        for (libusb_transfer* fxfr : feedbackTransfers_) {
            if (fxfr) libusb_free_transfer(fxfr);
        }
        transferBuffers_.clear();
        feedbackBuffers_.clear();
        freeAbandonedTransfers();
    } else {
        // Kept, not freed: a late completion still writes into the
        // buffer and reads the transfer. inflight_ keeps counting them,
        // so their CANCELLED callbacks balance it later instead of
        // driving the next pump's count below zero.
        LOGW("stopIsoPump: %d transfer(s) still in flight; keeping them", stuck);
        auto keep = [this](std::vector<libusb_transfer*>& xfrs,
                           std::vector<std::vector<uint8_t>>& bufs) {
            for (libusb_transfer* xfr : xfrs) {
                if (xfr) abandonedTransfers_.push_back(xfr);
            }
            for (auto& buf : bufs) abandonedBuffers_.emplace_back(std::move(buf));
            bufs.clear();
        };
        keep(transfers_, transferBuffers_);
        keep(feedbackTransfers_, feedbackBuffers_);
    }
    transfers_.clear();
    feedbackTransfers_.clear();
}

void LibusbUacDriver::freeAbandonedTransfers() {
    for (libusb_transfer* xfr : abandonedTransfers_) libusb_free_transfer(xfr);
    abandonedTransfers_.clear();
    abandonedBuffers_.clear();
}

void LibusbUacDriver::onIsoTrampoline(libusb_transfer* xfr) {
    static_cast<LibusbUacDriver*>(xfr->user_data)->onIso(xfr);
}

void LibusbUacDriver::onFeedbackTrampoline(libusb_transfer* xfr) {
    static_cast<LibusbUacDriver*>(xfr->user_data)->onFeedback(xfr);
}

// Decodes a UAC feedback packet and updates the atomic rate the
// data-EP completion callback reads on its next pass.
//
// High-speed (USB 2.0): 4 bytes, little-endian, 16.16 fixed-point.
//   Value = number of samples per microframe the device wants.
//   Direct fit for our framesPerUframe_q16_ atomic.
//
// Full-speed (USB 1.1): 3 bytes, little-endian, 10.10 fixed-point
// shifted left by 4 — i.e. the 24-bit value is "samples per frame
// in 10.14 format". To normalize to the same q16 representation
// the data EP uses, we left-shift by 2 (10.14 → 10.16).
//
// Out-of-range values (zero, or > maxFramesPerPacket_+1) are
// ignored — better to keep the previous rate than chase a glitchy
// reading that would overrun the iso buffer or starve the DAC.
//
// A transfer carries several readings, one per packet; the newest good
// one wins. Packets that failed, came back short or out of range are
// skipped, so a DAC that answers only some polls still steers the pump.
void LibusbUacDriver::onFeedback(libusb_transfer* xfr) {
    if (xfr->status == LIBUSB_TRANSFER_CANCELLED ||
        xfr->status == LIBUSB_TRANSFER_NO_DEVICE) {
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }
    if (stopRequested_.load(std::memory_order_acquire)) {
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }
    if (xfr->status == LIBUSB_TRANSFER_COMPLETED) {
        const uint32_t maxAllowed_q16 =
            static_cast<uint32_t>(maxFramesPerPacket_) << 16;
        uint32_t newest_q16 = 0;
        for (int i = 0; i < xfr->num_iso_packets; ++i) {
            const libusb_iso_packet_descriptor& d = xfr->iso_packet_desc[i];
            if (d.status != LIBUSB_TRANSFER_COMPLETED) continue;
            const int actual = static_cast<int>(d.actual_length);
            const uint8_t* p = libusb_get_iso_packet_buffer_simple(xfr, i);
            if (!p) continue;
            uint32_t v_q16 = 0;
            if (actual >= 4) {
                v_q16 =  static_cast<uint32_t>(p[0])
                      | (static_cast<uint32_t>(p[1]) << 8)
                      | (static_cast<uint32_t>(p[2]) << 16)
                      | (static_cast<uint32_t>(p[3]) << 24);
            } else if (actual >= 3) {
                uint32_t v_q14 =
                      static_cast<uint32_t>(p[0])
                    | (static_cast<uint32_t>(p[1]) << 8)
                    | (static_cast<uint32_t>(p[2]) << 16);
                v_q16 = v_q14 << 2;
            }
            if (v_q16 > 0 && v_q16 <= maxAllowed_q16) newest_q16 = v_q16;
        }
        if (newest_q16 != 0) {
            framesPerUframe_q16_.store(newest_q16, std::memory_order_release);
        }
    }
    int rc = libusb_submit_transfer(xfr);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("resubmit feedback -> %d", rc);
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
    }
}

void LibusbUacDriver::onIso(libusb_transfer* xfr) {
    if (xfr->status == LIBUSB_TRANSFER_CANCELLED ||
        xfr->status == LIBUSB_TRANSFER_NO_DEVICE) {
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }
    if (xfr->status != LIBUSB_TRANSFER_COMPLETED) {
        LOGW("iso transfer status=%d (will resubmit)", xfr->status);
    }
    if (stopRequested_.load(std::memory_order_acquire)) {
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    // This transfer's audio has now gone to the DAC: move the played
    // position up to the end of what it carried. Counting at fill time,
    // as this used to, put the position ahead of the DAC by everything
    // still queued in the other transfers. A transfer that came back
    // with an error counts too; its audio is gone from the ring either
    // way, and leaving it out would keep the track from ever ending.
    // A cursor, not a sum, so a transfer that dropped out of the
    // rotation is covered by the next one to complete.
    size_t slot = 0;
    while (slot < transfers_.size() && transfers_[slot] != xfr) ++slot;
    const bool tracked = slot < transfers_.size() && slot < transferEndCursor_.size();
    if (tracked) {
        const uint64_t sent = transferEndCursor_[slot];
        if (sent > sentCursor_.load(std::memory_order_relaxed)) {
            sentCursor_.store(sent, std::memory_order_release);
        }
    }

    // Per-packet sizing from the 16.16 fixed-point rate the device
    // most recently asked for (or the seeded open-loop value if no
    // feedback EP). libusb iso buffers are TIGHTLY PACKED by
    // per-packet length (packet i starts at offset = sum of
    // lengths[0..i-1]), so we drain into a running cursor — no
    // worst-case padding.
    int frameStride = format_.channels * format_.bytesPerSample;
    uint32_t rate_q16 = framesPerUframe_q16_.load(std::memory_order_acquire);
    uint8_t* cursor = xfr->buffer;
    for (int p = 0; p < xfr->num_iso_packets; ++p) {
        fracAccumulator_q16_ += rate_q16;
        int frames = static_cast<int>(fracAccumulator_q16_ >> 16);
        fracAccumulator_q16_ &= 0xFFFF;
        if (frames > maxFramesPerPacket_) {
            // Should never happen, but cap so we don't overrun the
            // worst-case-sized iso buffer.
            frames = maxFramesPerPacket_;
        }
        int bytes = frames * frameStride;
        xfr->iso_packet_desc[p].length = bytes;
        if (bytes > 0) drainRing(cursor, bytes);
        cursor += bytes;
    }
    if (tracked) transferEndCursor_[slot] = ringTail_.load(std::memory_order_relaxed);

    // Heartbeat once a minute, so a wedged or starved pump is visible
    // in logcat. Without it, the only iso-side log lines are LOGW on
    // failure, so a pump that "completes" but never advances
    // playedFrames (because every drainRing pads with silence) looks
    // identical to a pump that's perfectly healthy; the silence count
    // tells the two apart.
    if (++isoCallbacks_ >= isoLogEvery_) {
        isoCallbacks_ = 0;
        uint64_t head = ringHead_.load(std::memory_order_acquire);
        uint64_t tail = ringTail_.load(std::memory_order_acquire);
        LOGI("iso heartbeat (1/min): played=%lld written=%lld ring=%llu "
             "inflight=%d silence-padded=%llu bytes",
             static_cast<long long>(playedFrames()),
             static_cast<long long>(writtenFrames()),
             static_cast<unsigned long long>(head - tail),
             inflight_.load(std::memory_order_relaxed),
             static_cast<unsigned long long>(paddedBytes_));
        paddedBytes_ = 0;
    }

    int rc = libusb_submit_transfer(xfr);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("resubmit_transfer -> %d", rc);
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
    }
}

// --- Ring buffer ------------------------------------------------------

int LibusbUacDriver::drainRing(uint8_t* dst, int bytes) {
    uint64_t tail = ringTail_.load(std::memory_order_relaxed);
    // A flush discards everything before its cursor. Read it before the
    // head: it was a head value when stored, so the head read after it
    // is at least as far on and the difference can't go negative.
    const uint64_t flushed = flushCursor_.load(std::memory_order_acquire);
    if (flushed > tail) tail = flushed;
    const uint64_t head = ringHead_.load(std::memory_order_acquire);
    uint64_t available = head - tail;
    int n = static_cast<int>(std::min<uint64_t>(available, static_cast<uint64_t>(bytes)));
    if (n > 0) {
        size_t off = static_cast<size_t>(tail % ringBytes_);
        size_t first = std::min<size_t>(n, ringBytes_ - off);
        std::memcpy(dst, ring_.get() + off, first);
        if (first < static_cast<size_t>(n)) {
            std::memcpy(dst + first, ring_.get(), n - first);
        }
        tail += n;
    }
    // Stored even when only the flush moved it, so the producer sees
    // the discarded audio's room come free.
    ringTail_.store(tail, std::memory_order_release);
    if (n < bytes) {
        // Underrun — pad with silence so the iso packet still ships.
        // The DAC hears a click rather than dropping the entire URB.
        // Not counted as played: the position follows the song's own
        // frames, which the silence only delays.
        std::memset(dst + n, 0, bytes - n);
        paddedBytes_ += static_cast<uint64_t>(bytes - n);
    }
    return n;
}

int LibusbUacDriver::writePcm(const uint8_t* data, int frames) {
    // try_lock, never a wait: the lock is only ever held long by a
    // start() or stop() replacing the ring, and a write that loses to
    // one is refused like a write to a full ring, which the caller
    // already retries.
    std::unique_lock<std::mutex> lock(ringMutex_, std::try_to_lock);
    if (!lock.owns_lock() || !ring_ || ringBytes_ == 0) return 0;
    const int frameStride = ringStride_.load(std::memory_order_relaxed);
    if (frames <= 0 || frameStride <= 0) return 0;
    int bytes = frames * frameStride;
    uint64_t head = ringHead_.load(std::memory_order_relaxed);
    uint64_t tail = ringTail_.load(std::memory_order_acquire);
    uint64_t free = ringBytes_ - (head - tail);
    int writable = static_cast<int>(std::min<uint64_t>(free, static_cast<uint64_t>(bytes)));
    // Round down to whole frames to avoid splitting a frame across
    // calls — saves the consumer from having to track partial frames.
    writable -= writable % frameStride;
    if (writable <= 0) return 0;

    size_t off = static_cast<size_t>(head % ringBytes_);
    size_t first = std::min<size_t>(writable, ringBytes_ - off);
    std::memcpy(ring_.get() + off, data, first);
    if (first < static_cast<size_t>(writable)) {
        std::memcpy(ring_.get(), data + first, writable - first);
    }
    ringHead_.store(head + writable, std::memory_order_release);
    return writable / frameStride;
}

int LibusbUacDriver::writableFrames() const {
    std::unique_lock<std::mutex> lock(ringMutex_, std::try_to_lock);
    if (!lock.owns_lock() || ringBytes_ == 0) return 0;
    const int frameStride = ringStride_.load(std::memory_order_relaxed);
    if (frameStride <= 0) return 0;
    uint64_t head = ringHead_.load(std::memory_order_relaxed);
    uint64_t tail = ringTail_.load(std::memory_order_acquire);
    uint64_t free = ringBytes_ - (head - tail);
    return static_cast<int>(free / frameStride);
}

} // namespace monotrypt::usb
