#pragma once
// Host-build stand-in for the NDK logging header, so the DSP engine can be
// compiled and tested on a desktop compiler (see run_host_tests.sh).
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
