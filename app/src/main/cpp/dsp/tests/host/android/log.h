// Host-build stand-in for <android/log.h>, so engine tests compile off-device.
#pragma once
#include <cstdio>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) ((void)(prio), (void)(tag), 0)
