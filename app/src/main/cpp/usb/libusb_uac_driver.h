// SPDX-License-Identifier: GPL-3.0-or-later
// USB Audio Class direct-output driver — Android side.
//
// Stage 1 (this file): scaffolding. Holds the libusb context + device
// handle obtained via libusb_wrap_sys_device on a Java-supplied USB
// file descriptor. Stage 2 will add UAC2 descriptor enumeration; Stage
// 3 will add the isochronous-transfer pump that drains a SPSC ring
// buffer fed from LibusbAudioSink.handleBuffer.
//
// All public methods are safe to call from any thread; internal state
// is guarded by a single mutex. The audio thread (PCM writer) and the
// permission/UI thread can call independently.

#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

struct libusb_context;
struct libusb_device_handle;

namespace monotrypt::usb {

class LibusbUacDriver {
public:
    LibusbUacDriver();
    ~LibusbUacDriver();
    LibusbUacDriver(const LibusbUacDriver&) = delete;
    LibusbUacDriver& operator=(const LibusbUacDriver&) = delete;

    // One-shot init guarded by an atomic flag — safe to call from any
    // thread, only the first caller pays the libusb_init cost.
    bool ensureContext();

    // Open the USB device wrapping a Java-supplied UsbDeviceConnection
    // file descriptor. NO-OP and returns true if a device handle is
    // already open and the fd matches.
    bool open(int fileDescriptor);

    // Detaches from libusb. Safe to call multiple times.
    void close();

    bool isOpen() const { return device_ != nullptr; }

    // Returns the negotiated alt-setting's nominal sample rate, or 0
    // when nothing's negotiated yet. Stub for Stage 2.
    int currentSampleRate() const { return currentSampleRate_; }

private:
    mutable std::mutex mutex_;
    libusb_context* ctx_ = nullptr;
    libusb_device_handle* device_ = nullptr;
    int fd_ = -1;
    std::atomic<bool> contextReady_{false};
    int currentSampleRate_ = 0;
};

} // namespace monotrypt::usb
