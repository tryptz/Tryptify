/* Picks libarchive's hand-written configuration: the phone's, or the desktop's
 * for the host tests. Passed to libarchive as PLATFORM_CONFIG_H. */
#if defined(__ANDROID__)
#include "android.h"
#else
#include "linux_host.h"
#endif
