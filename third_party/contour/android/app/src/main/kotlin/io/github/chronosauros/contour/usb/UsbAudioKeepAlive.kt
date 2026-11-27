package io.github.chronosauros.contour.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** A looping silent track routed to one USB DAC. Some DACs (KA15) answer HID only while their audio stream runs.
 * No audio focus is requested, so playback in other apps keeps going; zeros add nothing to the mix. */
class UsbAudioKeepAlive private constructor(private val track: AudioTrack) : AutoCloseable {
    companion object {
        private const val RATE = 48000
        // Time for the output stream to open on the DAC before the first HID query.
        private const val SETTLE_MS = 400L

        fun start(context: Context, device: UsbDevice): UsbAudioKeepAlive? {
            val audio = context.getSystemService(AudioManager::class.java) ?: return null
            val out = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                    device.productName?.let { name -> it.productName?.toString()?.contains(name, ignoreCase = true) } == true
            }
            if (out == null) { UsbLog.line("audio keep-alive: no USB output for ${device.productName}"); return null }
            val frames = RATE / 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 4)
                .build()
            return runCatching {
                track.write(ShortArray(frames * 2), 0, frames * 2)
                track.setLoopPoints(0, frames, -1)
                track.setPreferredDevice(out)
                track.play()
                UsbLog.line("audio keep-alive: silent track on ${out.productName} (id ${out.id})")
                Thread.sleep(SETTLE_MS)
                UsbAudioKeepAlive(track)
            }.getOrElse { track.release(); UsbLog.line("audio keep-alive failed: ${it.message}"); null }
        }
    }

    override fun close() {
        runCatching { track.stop() }
        track.release()
        UsbLog.line("audio keep-alive: stopped")
    }
}
