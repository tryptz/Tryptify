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
#include <string>
#include <thread>
#include <vector>

#include <libusb.h>

namespace monotrypt::usb {

// Categorised reason the most recent start() returned false. Surfaced
// to Kotlin so the Settings screen can show actionable text instead of
// the prior "kernel still owns it" boilerplate which was wrong about
// half the time (rate negotiation failures, clock STALLs, alloc
// failures all looked the same to the user). Order matches Kotlin's
// LibusbUacDriver.StartError enum.
enum class StartError : int {
    Ok = 0,
    NoDevice,                  // start() before open()
    NoMatchingAlt,             // selectAltSetting found nothing
    ClaimInterfaceFailed,      // libusb_claim_interface (most common
                               // — kernel UAC driver owns the iface)
    SetAltFailed,              // libusb_set_interface_alt_setting
    SetSampleRateFailed,       // SET_CUR/GET_CUR all fell through
    IsoPumpAllocFailed,        // libusb_alloc_transfer returned null
    IsoPumpSubmitFailed,       // initial libusb_submit_transfer
};

// One subrange entry returned by GET_RANGE on a clock entity. UAC2
// §5.2.1 RANGE attribute — wNumSubRanges followed by N triples of
// (dMIN, dMAX, dRES) each 4-byte LE. Most DACs report each supported
// discrete rate as its own subrange with min==max; some (like USB
// audio interfaces with a continuous PLL) report a single subrange
// covering a range. We surface both honestly.
struct ClockRateRange {
    uint8_t clockId = 0;
    uint32_t minHz = 0;
    uint32_t maxHz = 0;
    uint32_t resHz = 0;
};

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
    // Iso transfer interval. For HS: 2^(bInterval-1) microframes
    // between packets — 1 = every 125us, 4 = every 1ms. For FS:
    // bInterval value in milliseconds. Without honoring this, our
    // pump computes frames/packet against the wrong clock and
    // either underflows the device's FIFO (silence) or — worse —
    // crams too many frames into too-small packets, which the
    // device interprets at its own clock rate and renders as a
    // pitch-shifted distorted stream.
    uint8_t bInterval = 1;
    // UAC version, decoded from bcdADC in the AudioControl Header
    // class-specific descriptor (UAC1 = 0x0100, UAC2 = 0x0200).
    // Different rate-set wire format and different AS_FORMAT_TYPE
    // layout — Focal Bathys is UAC1; most newer hi-res DACs are
    // UAC2.
    uint16_t uacVersion = 0x0200;
    // Async iso feedback IN endpoint (UAC2 §3.16.2.2). Zero if the
    // device is adaptive/sync — most quality DACs (incl. Focal Bathys)
    // are async and expose one. The host reads a 16.16 fixed-point
    // "samples per (micro)frame" value the device wants the host to
    // pace at; without honoring it we'd drift and the device's
    // rate-matching FIFO would eventually over- or under-flow.
    uint8_t feedbackEndpointAddress = 0;
    uint16_t feedbackMaxPacketSize = 0;
    uint8_t feedbackInterval = 0;
    // Every clock entity ID we found in the AudioControl topology,
    // in walk order. Used as a fall-through search list when SET_CUR
    // / GET_CUR on the topology-resolved clockSourceId fails — some
    // devices' Selector / Multiplier resolution is non-obvious and
    // it's cheaper to just try them all than misparse the graph.
    std::vector<uint8_t> candidateClockIds;
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

    // Discards any PCM still in the ring without tearing down the iso
    // pump. Use between tracks (Media3 calls flush() on track end) so
    // we don't release the streaming interface — releasing causes the
    // Android kernel to briefly re-grab the audio interface, after
    // which libusb_claim_interface returns BUSY on the next track and
    // playback dies until the user re-plugs the DAC. The pump keeps
    // running, sending silence iso packets, until the next handleBuffer
    // resumes feeding the ring.
    void flushRing();

    // Returns true when the iso pump is already streaming a stream
    // matching [sampleRate]/[bitsPerSample]/[channels]. Used by
    // LibusbAudioSink.configure() to skip a redundant stop/start when
    // the next track is the same format as the last (the common case
    // for an album with consistent encoding).
    bool isStreamingFormat(int sampleRate, int bitsPerSample, int channels) const;

    // Writes [frames] frames of interleaved PCM into the ring. Returns
    // the actual number written (may be less than requested if the
    // ring is nearly full). Caller will retry on the next audio-thread
    // tick. Buffer must be in the negotiated subslot size — write()
    // does not convert.
    int writePcm(const uint8_t* data, int frames);

    // How many frames can be written right now without blocking.
    int writableFrames() const;

    // Total PCM frames the iso pump has drained from the ring since
    // [start] — i.e. the frames the device has actually been told to
    // play. Used by LibusbAudioSink for getCurrentPositionUs / hasPendingData
    // / isEnded; reporting framesWritten there instead caused ExoPlayer
    // to think a track had finished within seconds (the renderer fills
    // the ring much faster than realtime), which manifested as 5-second
    // playbacks followed by an early skip to the next track.
    long playedFrames() const {
        return playedFrames_.load(std::memory_order_acquire);
    }

    // Total PCM frames the host has pushed into the ring since [start].
    long writtenFrames() const {
        return writtenFrames_.load(std::memory_order_acquire);
    }

    const StreamFormat& currentFormat() const { return format_; }

    // Reason the most recent start() returned false, or Ok if it
    // succeeded / hasn't been attempted. Reset to Ok on the next
    // successful start.
    StartError lastError() const {
        return lastError_.load(std::memory_order_acquire);
    }

    // Best-effort detail string accompanying [lastError]. May be empty.
    // Holds the libusb_strerror text or a contextual line written at
    // the failure site.
    std::string lastErrorDetail() const;

    // Snapshot of the GET_RANGE table reported by the device's clock
    // entities. Populated during start(); empty before any start
    // attempt or if the device returned nothing readable. Used by the
    // Settings UI to show what rates the DAC actually supports —
    // people want to know whether their hi-res 24/192 file is going
    // bit-perfect or quietly being downsampled to 48k.
    std::vector<ClockRateRange> supportedRates() const;

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

    // Issues GET_RANGE on the given clock entity and appends decoded
    // (min, max, res) subranges into supportedRates_. No-op if the
    // device returns less than the 2-byte wNumSubRanges header. Used
    // on the success path of setSampleRate so the UI can list "44.1 /
    // 48 / 88.2 / 96 / 176.4 / 192 kHz" beneath the active rate.
    void captureRangeForClock(uint8_t clockId);

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
    // True iff we currently hold libusb_claim_interface on
    // format_.interfaceNumber. Tracked separately from streaming_
    // because we keep the claim alive across format changes (e.g.
    // 16-bit → 24-bit between tracks of the same album) so the
    // Android kernel can't briefly re-attach snd-usb-audio in the
    // gap and bounce us with LIBUSB_ERROR_BUSY on the next claim.
    bool interfaceClaimed_ = false;
    // Same idea for the AudioControl interface. We need to own it
    // for class-specific control transfers (SET_CUR / GET_CUR /
    // GET_RANGE on clock entities) to reach the device — otherwise
    // the kernel's snd-usb-audio still has AC claimed and silently
    // returns LIBUSB_ERROR_IO on every control transfer with
    // wIndex pointing at the AC interface.
    bool controlInterfaceClaimed_ = false;
    uint8_t claimedControlIface_ = 0xFF;

    // Iso pump state — touched only from the event thread once
    // streaming_ is true.
    std::vector<libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> transferBuffers_;
    std::vector<libusb_transfer*> feedbackTransfers_;
    std::vector<std::vector<uint8_t>> feedbackBuffers_;
    std::atomic<int> inflight_{0};     // active transfers (data + fb)
    // Cumulative frame counters used for honest position reporting
    // (see playedFrames() / writtenFrames()). Reset on start().
    std::atomic<long> writtenFrames_{0};
    std::atomic<long> playedFrames_{0};
    std::thread eventThread_;

    // Per-packet frame count is computed from a 16.16 fixed-point
    // "frames per microframe" value. Two sources:
    //  - When no feedback EP exists, we seed it from the requested
    //    rate as (sampleRate / microframesPerSec) << 16, integer
    //    plus fractional (`rateRemainder_ << 16 / microframesPerSec_`).
    //    A fractional accumulator on the iso completion thread then
    //    walks one packet at a time — same effect as the old
    //    Bresenham, just in a single uint32 instead of two ints.
    //  - When a feedback IN EP exists, every feedback URB completion
    //    overwrites this atomic with the device-requested rate.
    //    Packets sized off the next read get the new rate, so the
    //    host paces to whatever the DAC's clock recovery wants.
    std::atomic<uint32_t> framesPerUframe_q16_{0};
    int microframesPerSec_ = 8000;
    int maxFramesPerPacket_ = 0;
    uint32_t fracAccumulator_q16_ = 0;  // event-thread-only state

    static void LIBUSB_CALL onFeedbackTrampoline(libusb_transfer* xfr);
    void onFeedback(libusb_transfer* xfr);

    // Persist the most recent failure category + a human-readable
    // detail line. Mutated only from start()'s call stack (under
    // mutex_) and from setSampleRate; read from any thread via
    // lastError() / lastErrorDetail() so we don't have to plumb a
    // result code back through several layers. Reset to Ok at the
    // top of every start() call.
    std::atomic<StartError> lastError_{StartError::Ok};
    mutable std::mutex errorMutex_;
    std::string lastErrorDetail_;

    // Filled at start() time by setSampleRate's GET_RANGE pass. Read
    // by the JNI getter to surface to the UI. Guarded by the same
    // errorMutex_ — readers and the start() writer don't race on the
    // hot path.
    std::vector<ClockRateRange> supportedRates_;
};

} // namespace monotrypt::usb
