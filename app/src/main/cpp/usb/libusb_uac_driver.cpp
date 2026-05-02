// SPDX-License-Identifier: GPL-3.0-or-later
// See libusb_uac_driver.h.

#include "libusb_uac_driver.h"

#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <cstring>

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
constexpr uint8_t AS_GENERAL            = 0x01;
constexpr uint8_t AS_FORMAT_TYPE        = 0x02;
constexpr uint8_t FORMAT_TYPE_I         = 0x01;

// UAC2: SET_CUR on Sample Rate (CS_SAM_FREQ_CONTROL) of a clock
// source entity. UAC1: SET_CUR on SAMPLING_FREQ_CONTROL of the iso
// data endpoint, 3-byte LE rate. Both share bRequest = 0x01.
constexpr uint8_t REQ_SET_CUR              = 0x01;
constexpr uint16_t CS_SAM_FREQ_CONTROL_SEL = 0x01;

// Pump tuning. 4 transfers × 8 packets keeps the pipe primed without
// hogging memory; 1MB ring covers ~700ms at 384k/32-bit/2ch which is
// far more than the audio thread's typical wakeup jitter.
constexpr int kNumTransfers = 4;
constexpr int kPacketsPerTransfer = 8;
constexpr size_t kRingBytes = 1u << 20;  // 1 MiB, must be power of two

// Produced/consumed bytes count, not frame count. We pack interleaved
// PCM contiguously so byte-level accounting is the natural unit.
inline size_t ringSize(size_t head, size_t tail) {
    // head and tail are monotonic uint64-ish counters masked at access
    // time, so this works correctly across 32-bit wrap.
    return head - tail;
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

} // namespace

LibusbUacDriver::LibusbUacDriver() {
    ring_.resize(kRingBytes);
    ringMask_ = kRingBytes - 1;
}

LibusbUacDriver::~LibusbUacDriver() {
    stop();
    close();
    std::lock_guard<std::mutex> lock(mutex_);
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
}

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
        libusb_close(device_);
        device_ = nullptr;
        fd_ = -1;
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

    // Per-terminal: (terminalID -> bCSourceID).
    struct TermClock { uint8_t termId; uint8_t clockId; };
    std::vector<TermClock> terminals;
    // All clock-bearing entities, with their subtype so we can
    // distinguish CLOCK_SOURCE (settable rate) from CLOCK_SELECTOR
    // (selects between sources — SET_CUR on it picks pin index, not
    // rate) and CLOCK_MULTIPLIER (derived from another clock).
    struct ClockEntity { uint8_t id; uint8_t subtype; uint8_t baseId; };
    std::vector<ClockEntity> clockEntities;

    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != USB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != SUBCLASS_AUDIOCONTROL) continue;
            controlIface = alt.bInterfaceNumber;
            walkExtra(alt.extra, alt.extra_length,
                [&](const uint8_t* p, int len) {
                    if (isClassDescriptor(p, len, CS_INTERFACE, AC_HEADER) && len >= 5) {
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
    LOGI("AudioControl iface %u, UAC version 0x%04x%s — %zu clock entities, %zu terminals",
         controlIface, uacVersion,
         (uacVersion < 0x0200) ? " (UAC1)" : " (UAC2)",
         clockEntities.size(), terminals.size());

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
            bool rateSupportedByDescriptor = (uacVersion >= 0x0200);
            walkExtra(alt.extra, alt.extra_length,
                [&](const uint8_t* p, int len) {
                    if (isClassDescriptor(p, len, CS_INTERFACE, AS_GENERAL)) {
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
                            if (len >= 8) {
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
            // Required packet bytes = ceil(rate / packetsPerSec) * stride,
            // plus one frame of headroom for fractional-rate jitter.
            int reqBytesPerPacket = ((sampleRateHz + packetsPerSec - 1)
                                     / packetsPerSec + 1) * frameStride;
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
            out_fmt->isHighSpeed =
                libusb_get_device_speed(dev) >= LIBUSB_SPEED_HIGH;
            out_fmt->bInterval = iso->bInterval;
            out_fmt->uacVersion = uacVersion;
            if (feedback) {
                out_fmt->feedbackEndpointAddress = feedback->bEndpointAddress;
                out_fmt->feedbackMaxPacketSize = feedback->wMaxPacketSize;
                out_fmt->feedbackInterval = feedback->bInterval;
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
    }
    return found;
}

bool LibusbUacDriver::setSampleRate(uint32_t hz) {
    if (format_.uacVersion >= 0x0200) {
        // UAC2 §5.2.5.1.1 — SET_CUR on a clock entity, 32-bit LE.
        //   bmRequestType = 0x21 (Class | Interface | Host-to-Device)
        //   wValue        = CS_SAM_FREQ_CONTROL << 8
        //   wIndex        = (clockId << 8) | controlInterfaceNum
        //   wLength       = 4
        if (format_.clockSourceId == 0) {
            LOGW("UAC2: no clock entity resolved for terminal — skipping "
                 "SET_CUR (assuming fixed-rate clock at %u Hz)", hz);
            return true;
        }
        uint8_t data[4] = {
            static_cast<uint8_t>(hz & 0xFF),
            static_cast<uint8_t>((hz >> 8) & 0xFF),
            static_cast<uint8_t>((hz >> 16) & 0xFF),
            static_cast<uint8_t>((hz >> 24) & 0xFF),
        };
        int rc = libusb_control_transfer(
            device_, 0x21, REQ_SET_CUR,
            static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
            static_cast<uint16_t>(
                (format_.clockSourceId << 8) | format_.controlInterfaceNum),
            data, 4, 1000);
        if (rc == 4) return true;
        // STALL or I/O error: many UAC2 DACs (Focal Bathys included)
        // run a fixed-rate hardware clock that doesn't accept
        // programmatic rate changes — the rate is whatever the
        // hardware was wired for, and the host just sends data at
        // that rate. Verify the device's GET_CUR matches what we
        // want; if so, proceed without SET_CUR. If GET_CUR is also
        // a different rate, log a warning but still proceed (audio
        // may pitch-shift, but at least it'll play).
        // UAC2 §5.2.1: bRequest is 0x01 (CUR) for both SET and GET —
        // direction is in bmRequestType (0x21 = SET, 0xA1 = GET).
        // Earlier code passed 0x81 here which the device correctly
        // ignored, returning all-zero data and reporting "0 Hz" — at
        // which point we'd "proceed anyway" and stream 44.1k frames
        // at whatever rate the device's clock was actually locked to.
        uint8_t cur[4] = {0, 0, 0, 0};
        int gc = libusb_control_transfer(
            device_, /*bmRequestType=*/0xA1, /*bRequest=*/REQ_SET_CUR,
            static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
            static_cast<uint16_t>(
                (format_.clockSourceId << 8) | format_.controlInterfaceNum),
            cur, 4, 1000);
        uint32_t devHz = (gc == 4)
            ? (uint32_t(cur[0]) | (uint32_t(cur[1]) << 8) |
               (uint32_t(cur[2]) << 16) | (uint32_t(cur[3]) << 24))
            : 0;
        if (gc == 4 && devHz == hz) {
            LOGI("UAC2 SET_CUR stalled but GET_CUR confirms %u Hz already — fixed-rate clock", hz);
            return true;
        }
        // Device's clock is at a different rate than requested AND
        // it didn't accept the change. Pushing audio at a different
        // rate to a fixed-rate clock is the chipmunk-distortion bug
        // we used to ship. Fail bypass cleanly so LibusbAudioSink
        // falls back to the delegate (Android's HAL) which will
        // resample to the device's rate properly.
        LOGE("UAC2 SET_CUR %u Hz -> %d, device reports %u Hz — refusing "
             "bypass to avoid pitch-shifted distortion. Falling back to "
             "the delegate sink. Try selecting a track at the device's "
             "native rate (%u Hz) for true bit-perfect output.",
             hz, rc, devHz, devHz);
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

bool LibusbUacDriver::start(int sampleRateHz, int bitsPerSample, int channels) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!device_) {
        LOGE("start() called before open()");
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
            LOGE("claim_interface(%u) -> %d (%s) — Developer Options "
                 "'Disable USB audio routing' must be ON, AND the "
                 "older 'USB DAC bit-perfect routing' toggle must be "
                 "OFF (it grabs the device via the framework and "
                 "fights us for the claim)",
                 fmt.interfaceNumber, rc, libusb_strerror(rc));
            return false;
        }
        interfaceClaimed_ = true;
    }
    format_ = fmt;

    int rc = libusb_set_interface_alt_setting(
        device_, format_.interfaceNumber, format_.altSetting);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("set_interface_alt_setting(%u, %u) -> %d",
             format_.interfaceNumber, format_.altSetting, rc);
        return false;
    }
    if (!setSampleRate(static_cast<uint32_t>(sampleRateHz))) {
        return false;
    }

    // Reset the ring before priming the pump.
    ringHead_.store(0, std::memory_order_relaxed);
    ringTail_.store(0, std::memory_order_relaxed);
    stopRequested_.store(false, std::memory_order_relaxed);

    if (!startIsoPump()) {
        return false;
    }
    streaming_.store(true, std::memory_order_release);
    LOGI("streaming: %d Hz / %d-bit / %d ch via ep 0x%02x",
         format_.sampleRateHz, format_.bitsPerSample, format_.channels,
         format_.endpointAddress);
    return true;
}

void LibusbUacDriver::flushRing() {
    // Lockless reset of the SPSC ring. Producer + consumer both
    // observe head==tail meaning empty on the very next access.
    // Ordering: store tail first, then head — between the two
    // moments, drainRing sees fewer bytes than actually present
    // (worst case: it pads with silence, which is what we want
    // anyway). Other order (head, then tail) could briefly let
    // drainRing think there are huge ring contents that are
    // actually stale.
    ringTail_.store(0, std::memory_order_release);
    ringHead_.store(0, std::memory_order_release);
}

bool LibusbUacDriver::isStreamingFormat(int sampleRate, int bitsPerSample, int channels) const {
    if (!streaming_.load(std::memory_order_acquire)) return false;
    return format_.sampleRateHz == sampleRate
        && format_.bitsPerSample == bitsPerSample
        && format_.channels == channels;
}

void LibusbUacDriver::stop() {
    bool was = streaming_.exchange(false, std::memory_order_acq_rel);
    if (!was && transfers_.empty() && !interfaceClaimed_) return;

    std::lock_guard<std::mutex> lock(mutex_);
    stopIsoPump();
    // Full teardown: alt 0 then release. Caller is the toggle going
    // off, the queue clearing, or app pause — NOT a track-to-track
    // reconfigure, which goes through start() with the claim kept.
    if (device_ && interfaceClaimed_) {
        if (format_.altSetting != 0) {
            libusb_set_interface_alt_setting(
                device_, format_.interfaceNumber, 0);
        }
        libusb_release_interface(device_, format_.interfaceNumber);
        interfaceClaimed_ = false;
    }
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
    LOGI("iso pump: %d packets/sec (HS=%d, bInterval=%u), "
         "%d frames/packet base + %d/%d frac",
         microframesPerSec_, format_.isHighSpeed, format_.bInterval,
         baseFrames, rateRemainder, microframesPerSec_);
    uint32_t seed_q16 =
        (static_cast<uint32_t>(baseFrames) << 16) +
        static_cast<uint32_t>(
            (static_cast<uint64_t>(rateRemainder) << 16) /
            static_cast<uint32_t>(microframesPerSec_));
    framesPerUframe_q16_.store(seed_q16, std::memory_order_relaxed);
    fracAccumulator_q16_ = 0;
    maxFramesPerPacket_ = baseFrames + (rateRemainder > 0 ? 1 : 0)
                          // +1 headroom for feedback over-asks.
                          + 1;

    int frameStride = format_.channels * format_.bytesPerSample;
    int maxBytesPerPacket = maxFramesPerPacket_ * frameStride;

    // Hard-cap at the endpoint's max packet size to stay spec-legal.
    int maxPacket = libusb_get_max_iso_packet_size(
        libusb_get_device(device_), format_.endpointAddress);
    if (maxPacket > 0 && maxBytesPerPacket > maxPacket) {
        LOGE("computed packet %d > endpoint max %d",
             maxBytesPerPacket, maxPacket);
        return false;
    }

    transfers_.reserve(kNumTransfers);
    transferBuffers_.reserve(kNumTransfers);

    for (int t = 0; t < kNumTransfers; ++t) {
        libusb_transfer* xfr = libusb_alloc_transfer(kPacketsPerTransfer);
        if (!xfr) {
            LOGE("libusb_alloc_transfer failed at #%d", t);
            stopIsoPump();
            return false;
        }
        // Worst-case sized buffer — every packet is the +1 size.
        // Per-packet `length` (set in onIso) trims to actual.
        std::vector<uint8_t> buf(maxBytesPerPacket * kPacketsPerTransfer, 0);
        libusb_fill_iso_transfer(
            xfr, device_, format_.endpointAddress,
            buf.data(), buf.size(), kPacketsPerTransfer,
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
    // by 4) on full-speed. We allocate one 1-packet transfer per
    // feedback URB and keep two in flight so completions overlap
    // with each other; the event thread already drives all iso
    // completions on a single thread so no locking needed for the
    // atomic store.
    constexpr int kFeedbackTransfers = 2;
    if (format_.feedbackEndpointAddress != 0) {
        for (int t = 0; t < kFeedbackTransfers; ++t) {
            libusb_transfer* fxfr = libusb_alloc_transfer(/*iso pkts=*/1);
            if (!fxfr) {
                LOGE("alloc feedback transfer %d failed", t);
                stopIsoPump();
                return false;
            }
            int fbBufSize = format_.feedbackMaxPacketSize > 0
                ? format_.feedbackMaxPacketSize : 4;
            std::vector<uint8_t> fbBuf(fbBufSize, 0);
            libusb_fill_iso_transfer(
                fxfr, device_, format_.feedbackEndpointAddress,
                fbBuf.data(), fbBuf.size(), /*num_iso_packets=*/1,
                &LibusbUacDriver::onFeedbackTrampoline, this, /*timeout=*/0);
            libusb_set_iso_packet_lengths(fxfr, fbBufSize);
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
    for (libusb_transfer* xfr : transfers_) {
        if (xfr) libusb_free_transfer(xfr);
    }
    for (libusb_transfer* fxfr : feedbackTransfers_) {
        if (fxfr) libusb_free_transfer(fxfr);
    }
    transfers_.clear();
    transferBuffers_.clear();
    feedbackTransfers_.clear();
    feedbackBuffers_.clear();
    inflight_.store(0, std::memory_order_relaxed);
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
    if (xfr->status == LIBUSB_TRANSFER_COMPLETED &&
        xfr->num_iso_packets > 0 &&
        xfr->iso_packet_desc[0].status == LIBUSB_TRANSFER_COMPLETED) {
        int actual = xfr->iso_packet_desc[0].actual_length;
        const uint8_t* p = xfr->buffer;
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
        if (v_q16 > 0) {
            uint32_t maxAllowed_q16 =
                static_cast<uint32_t>(maxFramesPerPacket_) << 16;
            if (v_q16 <= maxAllowed_q16) {
                framesPerUframe_q16_.store(v_q16, std::memory_order_release);
            }
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

    int rc = libusb_submit_transfer(xfr);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("resubmit_transfer -> %d", rc);
        inflight_.fetch_sub(1, std::memory_order_acq_rel);
    }
}

// --- Ring buffer ------------------------------------------------------

int LibusbUacDriver::drainRing(uint8_t* dst, int bytes) {
    size_t head = ringHead_.load(std::memory_order_acquire);
    size_t tail = ringTail_.load(std::memory_order_relaxed);
    size_t available = head - tail;
    int n = static_cast<int>(std::min<size_t>(available, static_cast<size_t>(bytes)));
    if (n > 0) {
        size_t off = tail & ringMask_;
        size_t first = std::min<size_t>(n, kRingBytes - off);
        std::memcpy(dst, ring_.data() + off, first);
        if (first < static_cast<size_t>(n)) {
            std::memcpy(dst + first, ring_.data(), n - first);
        }
        ringTail_.store(tail + n, std::memory_order_release);
    }
    if (n < bytes) {
        // Underrun — pad with silence so the iso packet still ships.
        // The DAC hears a click rather than dropping the entire URB.
        std::memset(dst + n, 0, bytes - n);
    }
    return n;
}

int LibusbUacDriver::writePcm(const uint8_t* data, int frames) {
    if (frames <= 0 || format_.channels == 0) return 0;
    int bytes = frames * format_.channels * format_.bytesPerSample;
    size_t head = ringHead_.load(std::memory_order_relaxed);
    size_t tail = ringTail_.load(std::memory_order_acquire);
    size_t free = kRingBytes - (head - tail);
    int writable = static_cast<int>(std::min<size_t>(free, static_cast<size_t>(bytes)));
    // Round down to whole frames to avoid splitting a frame across
    // calls — saves the consumer from having to track partial frames.
    int frameStride = format_.channels * format_.bytesPerSample;
    if (frameStride > 0) writable -= writable % frameStride;
    if (writable <= 0) return 0;

    size_t off = head & ringMask_;
    size_t first = std::min<size_t>(writable, kRingBytes - off);
    std::memcpy(ring_.data() + off, data, first);
    if (first < static_cast<size_t>(writable)) {
        std::memcpy(ring_.data(), data + first, writable - first);
    }
    ringHead_.store(head + writable, std::memory_order_release);
    return writable / frameStride;
}

int LibusbUacDriver::writableFrames() const {
    if (format_.channels == 0) return 0;
    size_t head = ringHead_.load(std::memory_order_relaxed);
    size_t tail = ringTail_.load(std::memory_order_acquire);
    size_t free = kRingBytes - (head - tail);
    int frameStride = format_.channels * format_.bytesPerSample;
    return static_cast<int>(free / frameStride);
}

} // namespace monotrypt::usb
