package io.github.chronosauros.contour.usb

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Every TX/RX report as hex: Logcat tag [TAG] plus an in-memory ring buffer of the last [CAPACITY] lines. */
object UsbLog {
    const val TAG = "ContourUsb"
    private const val CAPACITY = 500
    private val t0 = SystemClock.elapsedRealtime()
    private val ring = ArrayDeque<String>(CAPACITY)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun hex(b: ByteArray, n: Int = b.size): String = buildString(n * 3) {
        for (i in 0 until n) {
            if (i > 0) append(' ')
            val v = b[i].toInt() and 0xFF
            append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0xF, 16))
        }
    }

    fun tx(report: ByteArray) = line("TX ${hex(report)}")
    fun rx(report: ByteArray) = line("RX ${hex(report)}")

    fun line(text: String) {
        Log.d(TAG, text)
        val stamped = "%8.1f %s".format((SystemClock.elapsedRealtime() - t0) / 1.0, text)
        synchronized(ring) {
            if (ring.size == CAPACITY) ring.removeFirst()
            ring.addLast(stamped)
            _lines.value = ring.toList()
        }
    }
}
