// SPDX-License-Identifier: GPL-3.0-or-later
// See libusb_uac_driver.h for design notes.

#include "libusb_uac_driver.h"

#include <android/log.h>
#include <libusb.h>

#define TAG "LibusbUacDriver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace monotrypt::usb {

LibusbUacDriver::LibusbUacDriver() = default;

LibusbUacDriver::~LibusbUacDriver() {
    close();
    std::lock_guard<std::mutex> lock(mutex_);
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
}

// libusb's Android port requires NO_DEVICE_DISCOVERY before init —
// non-root processes can't read /dev/bus/usb/... so the default
// enumeration path fails. The wrap_sys_device path is the supported
// way to get a handle from an Android UsbManager-acquired fd.
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
        LOGE("libusb_init failed: %d (%s)", rc, libusb_strerror(rc));
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
        // Different fd — release the old one first.
        libusb_close(device_);
        device_ = nullptr;
        fd_ = -1;
    }

    libusb_device_handle* handle = nullptr;
    int rc = libusb_wrap_sys_device(ctx_,
                                    static_cast<intptr_t>(fileDescriptor),
                                    &handle);
    if (rc != LIBUSB_SUCCESS || handle == nullptr) {
        LOGE("libusb_wrap_sys_device(fd=%d) -> %d (%s)",
             fileDescriptor, rc, libusb_strerror(rc));
        return false;
    }

    device_ = handle;
    fd_ = fileDescriptor;
    LOGI("opened device via fd=%d", fileDescriptor);
    // TODO Stage 2: walk descriptors, find UAC2 Audio Streaming
    // interface, claim it, pick alt setting matching desired rate.
    return true;
}

void LibusbUacDriver::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_ != nullptr) {
        // TODO Stage 3: cancel + free pending iso transfers, release
        // the streaming interface, set alt 0 to put the DAC back in
        // its idle state.
        libusb_close(device_);
        device_ = nullptr;
        fd_ = -1;
        currentSampleRate_ = 0;
        LOGI("closed device");
    }
}

} // namespace monotrypt::usb
