// SPDX-License-Identifier: GPL-3.0-or-later
// USB Audio Class 2.0 direct-output driver — libusb-backed PCM sink.
//
// Owns:
//   - the libusb context (one per process, lazily created)
//   - the device handle wrapping a Java-supplied UsbDeviceConnection fd
//   - the streaming-interface alt setting + claim
//   - a fixed pool of preallocated isochronous transfers
//   - an SPSC ring buffer the audio thread writes into
//   - an event-handling thread driving libusb_handle_events
//
// The audio thread (Media3's playback worker, via JNI) calls write();
// the iso completion callback drains from the same ring on the event
// thread. Single producer, single consumer — no locks on the hot path.

#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#include <libusb.h>

namespace monotrypt::usb {

// Negotiated PCM stream parameters (output of UAC2 enumeration).
struct StreamFormat {
    int sampleRateHz = 0;
    int bitsPerSample = 0;     // 16, 24, or 32
    int bytesPerSample = 0;    // bSubslotSize from AS_FORMAT_TYPE
    int channels = 0;
    uint8_t interfaceNumber = 0;
    uint8_t altSetting = 0;
    uint8_t endpointAddress = 0;
    uint16_t maxPacketSize = 0;
    uint8_t clockSourceId = 0;
    uint8_t controlInterfaceNum = 0;
    bool isHighSpeed = true;   // affects packet timing (125us vs 1ms)
};

class LibusbUacDriver {
public:
    LibusbUacDriver();
    ~LibusbUacDriver();
    LibusbUacDriver(const LibusbUacDriver&) = delete;
    LibusbUacDriver& operator=(const LibusbUacDriver&) = delete;

    bool ensureContext();
    bool open(int fileDescriptor);
    void close();
    bool isOpen() const { return device_ != nullptr; }

    // Negotiates UAC2 alt setting matching the requested format,
    // claims the streaming interface, sets the clock source rate via
    // a class-specific control transfer, and starts the iso pump.
    // Returns false if any step fails (reasons logged with TAG
    // "LibusbUacDriver" — most often `LIBUSB_ERROR_BUSY` from
    // libusb_claim_interface, meaning the kernel UAC driver still
    // owns the interface and the user needs to enable Developer
    // Options → "Disable USB audio routing").
    bool start(int sampleRateHz, int bitsPerSample, int channels);

    // Cancels in-flight transfers, waits for the event thread to
    // drain, releases the streaming interface, and resets to alt 0.
    // Safe to call from any thread, idempotent.
    void stop();

    bool isStreaming() const {
        return streaming_.load(std::memory_order_acquire);
    }

    // Writes [frames] frames of interleaved PCM into the ring. Returns
    // the actual number written (may be less than requested if the
    // ring is nearly full). Caller will retry on the next audio-thread
    // tick. Buffer must be in the negotiated subslot size — write()
    // does not convert.
    int writePcm(const uint8_t* data, int frames);

    // How many frames can be written right now without blocking.
    int writableFrames() const;

    const StreamFormat& currentFormat() const { return format_; }

private:
    // Walks the active config, finds an Audio Streaming alt-setting
    // whose AS_GENERAL/AS_FORMAT_TYPE descriptors match the request,
    // and fills out_fmt. Returns false if no match.
    bool selectAltSetting(int sampleRateHz, int bitsPerSample,
                          int channels, StreamFormat* out_fmt);

    // Sends a UAC2 SET_CUR(CS_SAM_FREQ_CONTROL) class-specific
    // control transfer to the clock source entity. UAC2 puts the rate
    // on a clock-source unit, not on the endpoint like UAC1 did.
    bool setSampleRate(uint32_t hz);

    bool startIsoPump();
    void stopIsoPump();

    // libusb iso completion callback — static trampoline into onIso().
    static void LIBUSB_CALL onIsoTrampoline(libusb_transfer* xfr);
    void onIso(libusb_transfer* xfr);

    // Drains [bytes] bytes from the ring into [dst]. Returns bytes
    // actually drained; pads remainder with silence so iso packets
    // ship even on underrun (better a glitch than a dropped URB).
    int drainRing(uint8_t* dst, int bytes);

    // SPSC ring buffer. Power-of-two size, atomic head/tail. Producer
    // is the audio thread (writePcm); consumer is the event thread
    // via onIso → drainRing.
    std::vector<uint8_t> ring_;
    size_t ringMask_ = 0;
    std::atomic<size_t> ringHead_{0};  // producer cursor (writePcm)
    std::atomic<size_t> ringTail_{0};  // consumer cursor (onIso)

    mutable std::mutex mutex_;          // guards open/start/stop only
    libusb_context* ctx_ = nullptr;
    libusb_device_handle* device_ = nullptr;
    int fd_ = -1;
    std::atomic<bool> contextReady_{false};

    StreamFormat format_;
    std::atomic<bool> streaming_{false};
    std::atomic<bool> stopRequested_{false};

    // Iso pump state — touched only from the event thread once
    // streaming_ is true.
    std::vector<libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> transferBuffers_;
    std::atomic<int> inflight_{0};     // active transfers
    std::thread eventThread_;

    // Fractional-frame Bresenham accumulator for non-multiple-of-8000
    // rates (44.1 / 88.2 / 176.4 kHz). On each packet:
    //   thisPacketFrames = baseFrames_;
    //   rateAccumulator_ += rateRemainder_;
    //   if (rateAccumulator_ >= microframesPerSec_) {
    //       thisPacketFrames++;
    //       rateAccumulator_ -= microframesPerSec_;
    //   }
    // Lives only on the iso completion thread once start() returns,
    // so no synchronization required.
    int baseFrames_ = 0;
    int rateRemainder_ = 0;
    int microframesPerSec_ = 8000;
    int rateAccumulator_ = 0;
    int maxFramesPerPacket_ = 0;
};

} // namespace monotrypt::usb
