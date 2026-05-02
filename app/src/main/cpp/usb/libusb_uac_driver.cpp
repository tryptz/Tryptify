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
constexpr uint8_t AC_CLOCK_SOURCE       = 0x0A;
constexpr uint8_t AS_GENERAL            = 0x01;
constexpr uint8_t AS_FORMAT_TYPE        = 0x02;
constexpr uint8_t FORMAT_TYPE_I         = 0x01;

// UAC2 class-specific request: SET_CUR on Sample Rate (CS_SAM_FREQ_CONTROL).
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

    // First pass: locate AudioControl interface so we can discover
    // the clock-source ID. UAC2 puts sample-rate control on a clock
    // entity, not on the streaming endpoint — without an ID, we
    // can't issue SET_CUR.
    uint8_t controlIface = 0xFF;
    uint8_t clockId = 0;
    bool foundControl = false;
    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != USB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != SUBCLASS_AUDIOCONTROL) continue;
            controlIface = alt.bInterfaceNumber;
            walkExtra(alt.extra, alt.extra_length,
                [&](const uint8_t* p, int len) {
                    if (isClassDescriptor(p, len, CS_INTERFACE, AC_CLOCK_SOURCE) && len >= 4) {
                        clockId = p[3];   // bClockID
                        return true;
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
            walkExtra(alt.extra, alt.extra_length,
                [&](const uint8_t* p, int len) {
                    if (isClassDescriptor(p, len, CS_INTERFACE, AS_GENERAL) && len >= 16) {
                        // bNrChannels at offset 10 (after bTerminalLink+bmControls+bFormatType+bmFormats[4])
                        altChannels = p[10];
                    } else if (isClassDescriptor(p, len, CS_INTERFACE, AS_FORMAT_TYPE) && len >= 6) {
                        if (p[3] == FORMAT_TYPE_I) {
                            altSubslot = p[4]; // bSubslotSize
                            altBits = p[5];    // bBitResolution
                        }
                    }
                    return false;
                });

            if (altChannels != channels || altBits != bitsPerSample) continue;

            // Find an OUT iso endpoint.
            const libusb_endpoint_descriptor* iso = nullptr;
            for (int e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                if ((ep.bmAttributes & 0x03) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS &&
                    (ep.bEndpointAddress & 0x80) == 0) {
                    iso = &ep;
                    break;
                }
            }
            if (!iso) continue;

            out_fmt->sampleRateHz = sampleRateHz;
            out_fmt->bitsPerSample = bitsPerSample;
            out_fmt->bytesPerSample = altSubslot ? altSubslot : bitsPerSample / 8;
            out_fmt->channels = channels;
            out_fmt->interfaceNumber = alt.bInterfaceNumber;
            out_fmt->altSetting = alt.bAlternateSetting;
            out_fmt->endpointAddress = iso->bEndpointAddress;
            out_fmt->maxPacketSize = iso->wMaxPacketSize;
            out_fmt->clockSourceId = clockId;
            out_fmt->controlInterfaceNum = controlIface;
            out_fmt->isHighSpeed =
                libusb_get_device_speed(dev) >= LIBUSB_SPEED_HIGH;
            found = true;
            LOGI("matched alt %u on iface %u: %dch %d-bit, ep 0x%02x mps=%u",
                 alt.bAlternateSetting, alt.bInterfaceNumber,
                 channels, bitsPerSample, iso->bEndpointAddress,
                 iso->wMaxPacketSize);
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
    // UAC2 5.2.5.1.1 layout:
    //   bmRequestType = 0x21 (Class | Interface | Host-to-Device)
    //   bRequest      = SET_CUR (0x01)
    //   wValue        = (CS_SAM_FREQ_CONTROL << 8) | 0
    //   wIndex        = (clockSourceId << 8) | controlInterfaceNum
    //   wLength       = 4 (uint32 LE rate)
    uint8_t data[4] = {
        static_cast<uint8_t>(hz & 0xFF),
        static_cast<uint8_t>((hz >> 8) & 0xFF),
        static_cast<uint8_t>((hz >> 16) & 0xFF),
        static_cast<uint8_t>((hz >> 24) & 0xFF),
    };
    int rc = libusb_control_transfer(
        device_,
        /*bmRequestType=*/0x21,
        /*bRequest=*/REQ_SET_CUR,
        /*wValue=*/static_cast<uint16_t>(CS_SAM_FREQ_CONTROL_SEL << 8),
        /*wIndex=*/static_cast<uint16_t>(
            (format_.clockSourceId << 8) | format_.controlInterfaceNum),
        data, 4,
        /*timeout=*/1000);
    if (rc != 4) {
        LOGE("SET_CUR sample rate %u Hz -> %d", hz, rc);
        return false;
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
        LOGW("start() called while already streaming — stop first");
        return false;
    }

    StreamFormat fmt{};
    if (!selectAltSetting(sampleRateHz, bitsPerSample, channels, &fmt)) {
        return false;
    }
    format_ = fmt;

    int rc = libusb_claim_interface(device_, format_.interfaceNumber);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("claim_interface(%u) -> %d (%s) — likely Developer "
             "Options 'Disable USB audio routing' is OFF",
             format_.interfaceNumber, rc, libusb_strerror(rc));
        format_ = {};
        return false;
    }
    rc = libusb_set_interface_alt_setting(
        device_, format_.interfaceNumber, format_.altSetting);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("set_interface_alt_setting(%u, %u) -> %d",
             format_.interfaceNumber, format_.altSetting, rc);
        libusb_release_interface(device_, format_.interfaceNumber);
        format_ = {};
        return false;
    }
    if (!setSampleRate(static_cast<uint32_t>(sampleRateHz))) {
        libusb_release_interface(device_, format_.interfaceNumber);
        format_ = {};
        return false;
    }

    // Reset the ring before priming the pump.
    ringHead_.store(0, std::memory_order_relaxed);
    ringTail_.store(0, std::memory_order_relaxed);
    stopRequested_.store(false, std::memory_order_relaxed);

    if (!startIsoPump()) {
        libusb_release_interface(device_, format_.interfaceNumber);
        format_ = {};
        return false;
    }
    streaming_.store(true, std::memory_order_release);
    LOGI("streaming: %d Hz / %d-bit / %d ch via ep 0x%02x",
         format_.sampleRateHz, format_.bitsPerSample, format_.channels,
         format_.endpointAddress);
    return true;
}

void LibusbUacDriver::stop() {
    bool was = streaming_.exchange(false, std::memory_order_acq_rel);
    if (!was && transfers_.empty()) return;

    std::lock_guard<std::mutex> lock(mutex_);
    stopIsoPump();
    if (device_ && format_.interfaceNumber != 0xFF && format_.altSetting != 0) {
        libusb_set_interface_alt_setting(device_, format_.interfaceNumber, 0);
        libusb_release_interface(device_, format_.interfaceNumber);
    }
    LOGI("stopped streaming");
}

// --- Iso pump ---------------------------------------------------------

bool LibusbUacDriver::startIsoPump() {
    // High-speed runs 8 microframes/ms, full-speed 1 frame/ms. UAC2
    // packetization is one slot per (micro)frame. For rates that
    // don't divide evenly (44.1 / 88.2 / 176.4 kHz) we send a
    // variable number of frames per packet, controlled by a
    // Bresenham-style accumulator in onIso(). For the Focal Bathys
    // this is the path 44.1 kHz takes — base 5 frames/uframe with
    // a +1 every ~2nd packet, averaging 5.5125 fps/uframe.
    microframesPerSec_ = format_.isHighSpeed ? 8000 : 1000;
    baseFrames_ = format_.sampleRateHz / microframesPerSec_;
    rateRemainder_ = format_.sampleRateHz % microframesPerSec_;
    rateAccumulator_ = 0;
    maxFramesPerPacket_ = baseFrames_ + (rateRemainder_ > 0 ? 1 : 0);

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
        // first transmission is silence at the exact average rate.
        // onIso refines using the accumulator from the second URB on.
        libusb_set_iso_packet_lengths(
            xfr, baseFrames_ * frameStride);

        transferBuffers_.emplace_back(std::move(buf));
        transfers_.push_back(xfr);
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
    // fill the ring before the iso pipeline drains it.
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
    transfers_.clear();
    transferBuffers_.clear();
    inflight_.store(0, std::memory_order_relaxed);
}

void LibusbUacDriver::onIsoTrampoline(libusb_transfer* xfr) {
    static_cast<LibusbUacDriver*>(xfr->user_data)->onIso(xfr);
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

    // Per-packet sizing via the Bresenham accumulator — handles
    // 44.1k / 88.2k / 176.4k by dispatching base-or-base+1 frames per
    // packet so the average matches the requested rate to within one
    // sample. libusb iso buffers are TIGHTLY PACKED by per-packet
    // length (packet i starts at offset = sum of lengths[0..i-1]),
    // so we drain into a running cursor — no worst-case padding.
    int frameStride = format_.channels * format_.bytesPerSample;
    uint8_t* cursor = xfr->buffer;
    for (int p = 0; p < xfr->num_iso_packets; ++p) {
        int frames = baseFrames_;
        rateAccumulator_ += rateRemainder_;
        if (rateAccumulator_ >= microframesPerSec_) {
            frames++;
            rateAccumulator_ -= microframesPerSec_;
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
