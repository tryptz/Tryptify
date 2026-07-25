package tf.monochrome.android.performance

import android.os.Build
import java.io.File

data class DeviceSnapshot(
    val cores: Int,
    val bigCores: Int,
    val maxFreqMhz: Int,
    val ramMb: Int,
    val abi: String,
    val sdk: Int,
)

/**
 * Synchronous, no-Context device probe. Runs from [tf.monochrome.android.MonochromeApp]'s
 * companion-object `init` block — before `Dispatchers.Default` is first touched and
 * before `Application.onCreate`. Every probe is defensive so a missing sysfs node
 * on emulators / some OEMs can't crash startup; we fall back to `availableProcessors`.
 */
object DeviceCapabilities {

    @Volatile
    private var cached: Pair<DeviceTier, DeviceSnapshot>? = null

    fun detect(): Pair<DeviceTier, DeviceSnapshot> {
        cached?.let { return it }
        val snapshot = probe()
        val tier = classify(snapshot)
        val result = tier to snapshot
        cached = result
        return result
    }

    private fun probe(): DeviceSnapshot {
        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(2)
        val freqs = readCoreMaxFreqsMhz(cores)
        val maxFreq = freqs.maxOrNull() ?: 0
        val bigCores = if (maxFreq > 0) freqs.count { it >= maxFreq - BIG_CLUSTER_TOLERANCE_MHZ } else 0
        val ramMb = readTotalRamMb()
        val abi = runCatching { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }.getOrDefault("")
        val sdk = Build.VERSION.SDK_INT
        return DeviceSnapshot(
            cores = cores,
            bigCores = bigCores,
            maxFreqMhz = maxFreq,
            ramMb = ramMb,
            abi = abi,
            sdk = sdk,
        )
    }

    private fun readCoreMaxFreqsMhz(cores: Int): IntArray {
        if (cores <= 0) return IntArray(0)
        val out = IntArray(cores)
        for (i in 0 until cores) {
            out[i] = runCatching {
                val node = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (!node.canRead()) return@runCatching 0
                // cpuinfo_max_freq is reported in kHz.
                node.readText().trim().toLongOrNull()?.let { (it / 1000L).toInt() } ?: 0
            }.getOrDefault(0)
        }
        return out
    }

    private fun readTotalRamMb(): Int = runCatching {
        val meminfo = File("/proc/meminfo")
        if (!meminfo.canRead()) return@runCatching 0
        // "MemTotal:       16123456 kB"
        meminfo.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("MemTotal")) {
                    val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return@runCatching 0
                    return@runCatching (kb / 1024L).toInt()
                }
            }
            0
        }
    }.getOrDefault(0)

    private fun classify(s: DeviceSnapshot): DeviceTier {
        // cpufreq sysfs is unreadable on emulators and some OEM kernels — there
        // maxFreqMhz probes 0 and bigCores is UNKNOWN, not "none". Missing data
        // must stay neutral: frequency-based signals only count when a real
        // reading exists, otherwise an 8-core/12GB emulator lands in LOW and
        // every blur/glass feature silently vanishes from the test device.
        val freqKnown = s.maxFreqMhz > 0

        // LOW: any single "too weak" signal wins, so we err toward smoothness on constrained hw.
        val ramLow = s.ramMb in 1..LOW_RAM_MB_MAX
        val weakCpu = freqKnown && s.bigCores == 0 && s.maxFreqMhz <= LOW_FREQ_MHZ_MAX
        if (s.cores <= LOW_CORES_MAX || ramLow || weakCpu) return DeviceTier.LOW

        // HIGH: many cores + plenty of RAM, and — when frequency data exists —
        // a real big-core cluster. Without cpufreq the big-core requirement is
        // waived rather than auto-failed.
        val manyCores = s.cores >= HIGH_CORES_MIN
        val manyBig = !freqKnown || s.bigCores >= HIGH_BIG_CORES_MIN
        val plentyRam = s.ramMb >= HIGH_RAM_MB_MIN
        if (manyCores && manyBig && plentyRam) return DeviceTier.HIGH

        return DeviceTier.MID
    }

    // "Same cluster" tolerance for big-core counting. Kryo/Cortex clusters typically
    // differ by 200+ MHz; this keeps us tolerant of dvfs governor noise.
    private const val BIG_CLUSTER_TOLERANCE_MHZ = 100

    private const val LOW_CORES_MAX = 4
    private const val LOW_RAM_MB_MAX = 3 * 1024
    private const val LOW_FREQ_MHZ_MAX = 1800

    private const val HIGH_CORES_MIN = 8
    private const val HIGH_BIG_CORES_MIN = 3
    private const val HIGH_RAM_MB_MIN = 6 * 1024
}
