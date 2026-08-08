package tf.monochrome.android.audio.input

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.audio.dsp.LineInSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow

/**
 * Hardware stereo input for the DSP mixer.
 *
 * Captures from a USB audio interface, a USB DAC that exposes a capture
 * terminal, a USB-C dongle, the analog headset jack, or (as a last resort) the
 * built-in mic, and publishes it to the audio thread through a lock-free ring.
 * `MixBusProcessor` drains that ring one DSP block at a time and hands it to
 * the native engine as its aux input pair, so line-in is a genuine second input
 * to the console rather than something summed in ahead of it — each bus picks
 * `Playback`, `LineIn` or `Both` independently.
 *
 * ## Why AudioRecord and not the libusb driver
 *
 * The vendored libusb UAC driver is output-only (it claims the streaming
 * interface and drives the isochronous OUT endpoint; the only IN endpoint it
 * touches is the UAC2 async feedback one). Capturing over it would mean a
 * second, largely separate driver. `AudioRecord` with `setPreferredDevice`
 * already routes to every input class listed above through the platform's USB
 * audio HAL, so that is what this uses.
 *
 * ## Sample rate
 *
 * Capture is opened at whatever rate the playback pipeline is currently running
 * ([onPlaybackFormat]) rather than the device's native rate, and the framework
 * resamples on the way in. That costs a little quality on mismatched rates and
 * buys sample-alignment with the track in Mix mode, which is the difference
 * between "mixes correctly forever" and "drifts a second every eleven minutes".
 *
 * Threading: the capture thread is the ring's only producer and the audio
 * thread its only consumer; every start / stop / rebind is serialised onto a
 * private control thread. Nothing here blocks or allocates on the audio thread
 * — [readBlock] is a bounded copy out of the ring.
 */
@Singleton
class StereoInputEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : LineInSource {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callbackHandler = Handler(Looper.getMainLooper())

    private val ring = InputRingBuffer()
    private val stateLock = Any()

    /**
     * Every start / stop / rebind runs here rather than on its caller.
     *
     * Stopping a capture stream joins the capture thread, which can take a few
     * hundred milliseconds if it is parked in a blocking read — and the callers
     * are the ExoPlayer renderer thread (via [onPlaybackFormat]) and the main
     * thread (device hot-plug, UI). Blocking either would be heard as a
     * dropout, so control work is serialised onto a thread of its own and the
     * state flows update when it lands.
     */
    private val controlThread = HandlerThread("StereoInputControl").apply { start() }
    private val controlHandler = Handler(controlThread.looper)

    private fun postControl(block: () -> Unit) {
        controlHandler.post(block)
    }

    // ── Published state ──────────────────────────────────────────────────

    private val _devices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val devices: StateFlow<List<AudioDeviceInfo>> = _devices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<Int?>(null)
    val selectedDeviceId: StateFlow<Int?> = _selectedDeviceId.asStateFlow()

    private val _status = MutableStateFlow(StereoInputStatus.Idle)
    val status: StateFlow<StereoInputStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<StereoInputFailure?>(null)
    val lastError: StateFlow<StereoInputFailure?> = _lastError.asStateFlow()

    private val _activeFormat = MutableStateFlow<StereoInputFormat?>(null)
    val activeFormat: StateFlow<StereoInputFormat?> = _activeFormat.asStateFlow()

    private val _channelMode = MutableStateFlow(StereoInputChannelMode.Stereo)
    val channelMode: StateFlow<StereoInputChannelMode> = _channelMode.asStateFlow()

    /** Linear peak of the last captured block, per channel. Read once per UI frame. */
    private val _peakL = MutableStateFlow(0f)
    val peakL: StateFlow<Float> = _peakL.asStateFlow()
    private val _peakR = MutableStateFlow(0f)
    val peakR: StateFlow<Float> = _peakR.asStateFlow()

    /** Underrun / overrun / drift-trim counters, surfaced in the diagnostics card. */
    val underruns: Long get() = ring.underruns
    val overruns: Long get() = ring.overruns
    val driftTrims: Long get() = ring.driftTrims

    // ── Capture state ────────────────────────────────────────────────────

    @Volatile private var record: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var running = false

    /** Linear gain applied as the audio thread drains the ring. */
    @Volatile private var trimGain = 1f

    /** Sample rate reported by MixBusProcessor; 0 until the pipeline has configured once. */
    @Volatile private var playbackSampleRate = 0

    /** User intent. Capture only actually runs when this and a usable device coincide. */
    @Volatile private var wantCapture = false

    init {
        refreshDevices()
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
            },
            callbackHandler,
        )
    }

    // ── LineInSource (audio thread) ──────────────────────────────────────

    override val engaged: Boolean
        get() = running

    override fun readBlock(outL: FloatArray, outR: FloatArray, frames: Int): Boolean {
        if (!running) return false
        ring.read(outL, outR, frames, trimGain)
        // Deliberately true even on a total underrun: the buses routed to
        // line-in keep pulling zeros so their reverb tails and delay lines ring
        // out, instead of the bus flicking to "no signal" and cutting them.
        return true
    }

    override fun onPlaybackFormat(sampleRate: Int) {
        if (sampleRate <= 0 || sampleRate == playbackSampleRate) return
        playbackSampleRate = sampleRate
        // Rebind so capture follows the new rate. Only matters while we're
        // live; otherwise the next start() picks the new value up on its own.
        // Off-thread — this is called from the renderer's flush().
        postControl { rebindIfLive() }
    }

    private fun rebindIfLive() {
        synchronized(stateLock) {
            if (!wantCapture) return
            stopCaptureLocked()
            startCaptureLocked()
        }
    }

    // ── Device enumeration ───────────────────────────────────────────────

    private fun onDevicesChanged() {
        refreshDevices()
        postControl {
            synchronized(stateLock) {
                if (!wantCapture) return@synchronized
                val target = resolveDeviceLocked()
                if (target == null) {
                    stopCaptureLocked()
                    _status.value = StereoInputStatus.NoDevice
                } else if (!running || target.id != activeDeviceId) {
                    // The selected device was (re)plugged, or the fallback pick
                    // changed under us — rebind rather than keep streaming from
                    // a device the user is no longer pointing at.
                    stopCaptureLocked()
                    startCaptureLocked()
                }
            }
        }
    }

    @Volatile private var activeDeviceId: Int = -1

    private fun refreshDevices() {
        val inputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) }
            .getOrNull() ?: emptyArray()
        _devices.value = inputs
            .filter { it.type in USABLE_INPUT_TYPES }
            // A single physical device can surface more than once (different
            // encodings); the picker only needs one row per device id.
            .distinctBy { it.id }
            .sortedBy { PREFERENCE_ORDER.indexOf(it.type).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** Human-readable label for the picker, mirroring `UsbAudioRouter.describe`. */
    fun describe(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() }
        val kind = typeLabel(device.type)
        return if (product != null && !product.equals(kind, ignoreCase = true)) "$product · $kind" else kind
    }

    /** Secondary line for the picker: what the device says it can capture. */
    fun describeCapabilities(device: AudioDeviceInfo): String {
        val channels = device.channelCounts.filter { it in 1..2 }.maxOrNull()
        val rates = device.sampleRates.sortedDescending().take(3)
        val chText = when (channels) {
            2 -> "stereo"
            1 -> "mono"
            else -> "channels unreported"
        }
        val rateText = if (rates.isEmpty()) "rate follows playback"
        else rates.joinToString("/") { "${it / 1000}k" }
        return "$chText · $rateText"
    }

    /**
     * True when this device would feed itself: capturing on the built-in mic
     * while the speaker is the active output is a howlround waiting to happen,
     * and it is the one selection worth warning about up front.
     */
    fun isFeedbackRisk(device: AudioDeviceInfo): Boolean {
        if (device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ── Control ──────────────────────────────────────────────────────────

    fun selectDevice(deviceId: Int?) {
        _selectedDeviceId.value = deviceId
        postControl { rebindIfLive() }
    }

    fun setChannelMode(mode: StereoInputChannelMode) {
        if (_channelMode.value == mode) return
        _channelMode.value = mode
        postControl { rebindIfLive() }
    }

    /** Input trim in dB, clamped to a sane range and applied as the ring is drained. */
    fun setTrimDb(db: Float) {
        val clamped = if (db.isNaN()) 0f else db.coerceIn(MIN_TRIM_DB, MAX_TRIM_DB)
        trimGain = 10f.pow(clamped / 20f)
    }

    /** Start capture. Idempotent; safe to call before a device or rate is known. */
    fun start() {
        // Flip the intent synchronously so an immediately-following stop()
        // can't be reordered against it, then do the device work off-thread.
        wantCapture = true
        postControl {
            synchronized(stateLock) {
                if (!wantCapture || running) return@synchronized
                startCaptureLocked()
            }
        }
    }

    /** Stop capture and release the device. Idempotent. */
    fun stop() {
        wantCapture = false
        postControl {
            synchronized(stateLock) {
                if (wantCapture) return@synchronized
                stopCaptureLocked()
                _status.value = StereoInputStatus.Idle
                _lastError.value = null
            }
        }
    }

    private fun resolveDeviceLocked(): AudioDeviceInfo? {
        val all = _devices.value
        val wanted = _selectedDeviceId.value
        if (wanted != null) {
            all.firstOrNull { it.id == wanted }?.let { return it }
        }
        // No explicit pick (or it is unplugged): take the highest-priority
        // input available, which puts a USB interface ahead of the built-in mic.
        return all.minByOrNull {
            PREFERENCE_ORDER.indexOf(it.type).let { i -> if (i < 0) Int.MAX_VALUE else i }
        }
    }

    @SuppressLint("MissingPermission") // hasPermission() gates every path to here
    private fun startCaptureLocked() {
        if (running) return

        if (!hasPermission()) {
            _status.value = StereoInputStatus.PermissionRequired
            _lastError.value = StereoInputFailure.PermissionDenied
            return
        }

        val device = resolveDeviceLocked()
        if (device == null) {
            _status.value = StereoInputStatus.NoDevice
            return
        }

        _status.value = StereoInputStatus.Starting
        _lastError.value = null

        // Follow the playback clock when we know it; otherwise take the
        // device's own preference so a start before the first track still runs.
        val rate = playbackSampleRate.takeIf { it > 0 }
            ?: device.sampleRates.firstOrNull { it in SUPPORTED_FALLBACK_RATES }
            ?: DEFAULT_SAMPLE_RATE

        val wantStereo = _channelMode.value == StereoInputChannelMode.Stereo &&
            device.channelCounts.let { it.isEmpty() || it.any { c -> c >= 2 } }

        val started = tryOpen(device, rate, stereo = wantStereo)
            // Plenty of dongles advertise stereo capture and then refuse to
            // initialise with CHANNEL_IN_STEREO. Rather than surfacing a dead
            // end, fall back to mono and duplicate it across the pair.
            || (wantStereo && tryOpen(device, rate, stereo = false))

        if (!started) {
            stopCaptureLocked()
            _status.value = StereoInputStatus.Error
            return
        }

        activeDeviceId = device.id
        _status.value = StereoInputStatus.Running
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen(device: AudioDeviceInfo, rate: Int, stereo: Boolean): Boolean {
        val channelMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBytes = AudioRecord.getMinBufferSize(rate, channelMask, ENCODING)
        if (minBytes <= 0) {
            _lastError.value = StereoInputFailure.UnsupportedFormat
            return false
        }
        // Four times the platform minimum: enough slack that a scheduling hiccup
        // on the capture thread doesn't cost samples, small enough that it isn't
        // where monitoring latency comes from (the ring's drift trim caps that).
        val bufferBytes = minBytes * 4

        val source = preferredAudioSource()
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord build failed (rate=$rate stereo=$stereo src=$source)", e)
            _lastError.value = StereoInputFailure.InitFailed
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _lastError.value = StereoInputFailure.InitFailed
            return false
        }

        if (!rec.setPreferredDevice(device)) {
            // Not fatal — the platform may still route to it, and refusing to
            // capture because the hint was declined would be worse than a
            // possible fallback to the default input.
            Log.w(TAG, "setPreferredDevice(${device.id}) declined; continuing on default routing")
        }

        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
            rec.release()
            _lastError.value = StereoInputFailure.StartFailed
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            _lastError.value = StereoInputFailure.StartFailed
            return false
        }

        ring.clear()
        record = rec
        running = true
        _activeFormat.value = StereoInputFormat(
            sampleRate = rate,
            channelCount = if (stereo) 2 else 1,
            audioSource = source,
        )

        val frames = (bufferBytes / BYTES_PER_SAMPLE / (if (stereo) 2 else 1))
            .coerceIn(MIN_READ_FRAMES, MAX_READ_FRAMES)
        val thread = Thread({ captureLoop(rec, frames, stereo) }, "StereoInputCapture")
        thread.isDaemon = true
        captureThread = thread
        thread.start()
        return true
    }

    private fun captureLoop(rec: AudioRecord, framesPerRead: Int, stereo: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val samplesPerRead = framesPerRead * (if (stereo) 2 else 1)
        val buf = FloatArray(samplesPerRead)
        try {
            while (running && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = rec.read(buf, 0, samplesPerRead, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        _lastError.value = StereoInputFailure.ReadFailed
                        _status.value = StereoInputStatus.Error
                        break
                    }
                    continue
                }
                val frames = read / (if (stereo) 2 else 1)
                ring.write(buf, frames, monoSource = !stereo)
                publishPeaks(buf, read, stereo)
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture loop ended", e)
            _lastError.value = StereoInputFailure.ReadFailed
            _status.value = StereoInputStatus.Error
        }
    }

    private fun publishPeaks(buf: FloatArray, samples: Int, stereo: Boolean) {
        var l = 0f
        var r = 0f
        if (stereo) {
            var i = 0
            while (i + 1 < samples) {
                val al = abs(buf[i]); if (al > l) l = al
                val ar = abs(buf[i + 1]); if (ar > r) r = ar
                i += 2
            }
        } else {
            for (i in 0 until samples) {
                val a = abs(buf[i]); if (a > l) l = a
            }
            r = l
        }
        _peakL.value = l
        _peakR.value = r
    }

    private fun stopCaptureLocked() {
        running = false
        val thread = captureThread
        val rec = record
        captureThread = null
        record = null

        // Order matters. The capture thread is usually parked inside a blocking
        // read(); stop() is what wakes it, and release() while it is still in
        // there frees the buffer out from under a native read. So: stop, join,
        // then release.
        if (rec != null) {
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
        }
        var readerFinished = true
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(CAPTURE_JOIN_TIMEOUT_MS) }
            readerFinished = !thread.isAlive
        }
        if (rec != null) {
            if (readerFinished) {
                runCatching { rec.release() }
            } else {
                // A wedged reader is rare and recoverable; a use-after-free is
                // neither. Drop the reference and let finalization reclaim it.
                Log.w(TAG, "capture thread did not exit in ${CAPTURE_JOIN_TIMEOUT_MS}ms; " +
                    "leaving AudioRecord to be finalized rather than releasing under it")
            }
        }
        ring.clear()
        activeDeviceId = -1
        _activeFormat.value = null
        _peakL.value = 0f
        _peakR.value = 0f
    }

    /**
     * Pick the least-processed capture source the device admits to supporting.
     * `UNPROCESSED` is the only one guaranteed free of AGC / noise suppression
     * / echo cancellation, all of which would wreck a line-level signal;
     * `VOICE_RECOGNITION` is the conventional next-best and `MIC` the floor.
     */
    private fun preferredAudioSource(): Int {
        val unprocessed = runCatching {
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        }.getOrNull()
        return when {
            unprocessed?.equals("true", ignoreCase = true) == true ->
                MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    companion object {
        private const val TAG = "StereoInputEngine"

        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val BYTES_PER_SAMPLE = 4
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val CAPTURE_JOIN_TIMEOUT_MS = 500L

        private const val MIN_READ_FRAMES = 128
        private const val MAX_READ_FRAMES = 8192

        const val MIN_TRIM_DB = -24f
        const val MAX_TRIM_DB = 24f

        private val SUPPORTED_FALLBACK_RATES = intArrayOf(48000, 44100, 96000, 88200)

        /**
         * Input device types worth offering. Ordered by how likely they are to
         * be what someone means by "line in" — a USB interface first, the
         * built-in mic last, since it is the only one that can feed back.
         */
        private val PREFERENCE_ORDER = intArrayOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
        )

        private val USABLE_INPUT_TYPES = PREFERENCE_ORDER.toSet()

        fun typeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog Line In"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line In"
            AudioDeviceInfo.TYPE_AUX_LINE -> "Aux In"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset Jack"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            else -> "Audio Input"
        }
    }
}
