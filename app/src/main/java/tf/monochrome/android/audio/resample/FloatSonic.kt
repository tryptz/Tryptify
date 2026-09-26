/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2010 Bill Cox, Sonic Library
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tf.monochrome.android.audio.resample

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Media3's `Sonic` (androidx.media3.common.audio, 1.5.1), ported from 16-bit
 * samples to float.
 *
 * Media3's Sonic takes 16-bit PCM and nothing else, in every release up to at
 * least 1.8. That is why a speed change used to move a hi-res stream off the
 * float path onto the 16-bit one — a mid-track rebuild of the AudioTrack that
 * both dropped out and, returning to 1.00x, fed the 16-bit pipeline's leftovers
 * through processors already reconfigured for float. With a float Sonic the
 * float path can time-stretch itself, and never has to switch.
 *
 * The port is line for line, with two deliberate differences:
 *
 * - **The pitch-period search runs on a 16-bit copy.** Sonic's AMDF sums and
 *   its "was the previous period better" test are integer arithmetic, tuned
 *   for 16-bit magnitudes. Searching a quantized copy keeps every period
 *   choice identical to Media3's; only the overlap-add and the interpolation,
 *   which build the output samples, run in float. So for input a 16-bit file
 *   can hold, the output matches Media3's Sonic to within its integer rounding
 *   ([FloatSonicTest] holds that), and for anything wider nothing is narrowed.
 * - **Speed and pitch can change mid-stream** ([setSpeed], [setPitch]).
 *   Media3 rebuilds its Sonic on a change, which needs a pipeline flush and
 *   drops what the old one held. The algorithm reads both per block anyway;
 *   the one piece of state tied to a ratio, the rate interpolator's position,
 *   is restarted when pitch moves.
 */
internal class FloatSonic(
    private val inputSampleRateHz: Int,
    private val channelCount: Int,
    speed: Float,
    pitch: Float,
    outputSampleRateHz: Int,
) {
    var speed: Float = speed
        private set
    var pitch: Float = pitch
        private set
    private val rate: Float = inputSampleRateHz.toFloat() / outputSampleRateHz
    private val minPeriod = inputSampleRateHz / MAXIMUM_PITCH
    private val maxPeriod = inputSampleRateHz / MINIMUM_PITCH
    private val maxRequiredFrameCount = 2 * maxPeriod
    private val downSampleBuffer = ShortArray(maxRequiredFrameCount)

    private var inputBuffer = FloatArray(maxRequiredFrameCount * channelCount)
    private var inputFrameCount = 0
    private var outputBuffer = FloatArray(maxRequiredFrameCount * channelCount)
    private var outputFrameCount = 0
    private var pitchBuffer = FloatArray(maxRequiredFrameCount * channelCount)
    private var pitchFrameCount = 0
    private var oldRatePosition = 0
    private var newRatePosition = 0
    private var remainingInputToCopyFrameCount = 0
    private var prevPeriod = 0
    private var prevMinDiff = 0
    private var minDiff = 0
    private var maxDiff = 0
    private var accumulatedSpeedAdjustmentError = 0.0

    fun setSpeed(value: Float) {
        speed = value
    }

    fun setPitch(value: Float) {
        if (value == pitch) return
        pitch = value
        // The interpolator's two positions count in units of the old ratio;
        // carried into a new one they no longer meet where it expects.
        oldRatePosition = 0
        newRatePosition = 0
    }

    /** Frames queued but not yet turned into output — the analogue of Sonic's pending bytes. */
    val pendingInputFrameCount: Int get() = inputFrameCount

    val outputFrameCountAvailable: Int get() = outputFrameCount

    /** Queues [frameCount] frames of interleaved samples from [samples] at [offset]. */
    fun queueInput(samples: FloatArray, offset: Int, frameCount: Int) {
        inputBuffer = ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, frameCount)
        System.arraycopy(samples, offset, inputBuffer, inputFrameCount * channelCount, frameCount * channelCount)
        inputFrameCount += frameCount
        processStreamInput()
    }

    /** Moves up to [maxFrames] frames of output into [dst]; returns how many. */
    fun getOutput(dst: FloatArray, maxFrames: Int): Int {
        val framesToRead = min(maxFrames, outputFrameCount)
        System.arraycopy(outputBuffer, 0, dst, 0, framesToRead * channelCount)
        outputFrameCount -= framesToRead
        System.arraycopy(
            outputBuffer, framesToRead * channelCount,
            outputBuffer, 0,
            outputFrameCount * channelCount,
        )
        return framesToRead
    }

    fun queueEndOfStream() {
        val remainingFrameCount = inputFrameCount
        val s = speed.toDouble() / pitch
        val r = rate.toDouble() * pitch
        val adjustedRemainingFrames = remainingFrameCount - remainingInputToCopyFrameCount
        val expectedOutputFrames = outputFrameCount +
            ((adjustedRemainingFrames / s +
                remainingInputToCopyFrameCount +
                accumulatedSpeedAdjustmentError +
                pitchFrameCount) / r + 0.5).toInt()
        accumulatedSpeedAdjustmentError = 0.0

        // Enough silence to flush both the input and the pitch buffers.
        inputBuffer = ensureSpaceForAdditionalFrames(
            inputBuffer, inputFrameCount, remainingFrameCount + 2 * maxRequiredFrameCount,
        )
        java.util.Arrays.fill(
            inputBuffer,
            remainingFrameCount * channelCount,
            (remainingFrameCount + 2 * maxRequiredFrameCount) * channelCount,
            0f,
        )
        inputFrameCount += 2 * maxRequiredFrameCount
        processStreamInput()
        // Throw away what the added silence produced.
        if (outputFrameCount > expectedOutputFrames) outputFrameCount = expectedOutputFrames
        inputFrameCount = 0
        remainingInputToCopyFrameCount = 0
        pitchFrameCount = 0
    }

    fun flush() {
        inputFrameCount = 0
        outputFrameCount = 0
        pitchFrameCount = 0
        oldRatePosition = 0
        newRatePosition = 0
        remainingInputToCopyFrameCount = 0
        prevPeriod = 0
        prevMinDiff = 0
        minDiff = 0
        maxDiff = 0
        accumulatedSpeedAdjustmentError = 0.0
    }

    // ── Internals ──────────────────────────────────────────────────────

    private fun ensureSpaceForAdditionalFrames(
        buffer: FloatArray,
        frameCount: Int,
        additionalFrameCount: Int,
    ): FloatArray {
        val currentCapacityFrames = buffer.size / channelCount
        if (frameCount + additionalFrameCount <= currentCapacityFrames) return buffer
        val newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount
        return buffer.copyOf(newCapacityFrames * channelCount)
    }

    private fun removeProcessedInputFrames(positionFrames: Int) {
        val remainingFrames = inputFrameCount - positionFrames
        System.arraycopy(
            inputBuffer, positionFrames * channelCount,
            inputBuffer, 0,
            remainingFrames * channelCount,
        )
        inputFrameCount = remainingFrames
    }

    private fun copyToOutput(samples: FloatArray, positionFrames: Int, frameCount: Int) {
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, frameCount)
        System.arraycopy(
            samples, positionFrames * channelCount,
            outputBuffer, outputFrameCount * channelCount,
            frameCount * channelCount,
        )
        outputFrameCount += frameCount
    }

    private fun copyInputToOutput(positionFrames: Int): Int {
        val frameCount = min(maxRequiredFrameCount, remainingInputToCopyFrameCount)
        copyToOutput(inputBuffer, positionFrames, frameCount)
        remainingInputToCopyFrameCount -= frameCount
        return frameCount
    }

    /**
     * Averages [skip] frames (and all channels) into each 16-bit value of
     * [downSampleBuffer]. Also used with skip 1 for mono, where Media3 searched
     * the input directly: the search always reads 16-bit values, so it always
     * decides as Media3's does.
     */
    private fun downSampleInput(samples: FloatArray, position: Int, skip: Int) {
        val frameCount = maxRequiredFrameCount / skip
        val samplesPerValue = channelCount * skip
        val start = position * channelCount
        for (i in 0 until frameCount) {
            var value = 0
            for (j in 0 until samplesPerValue) {
                value += toShort(samples[start + i * samplesPerValue + j])
            }
            value /= samplesPerValue
            downSampleBuffer[i] = value.toShort()
        }
    }

    private fun findPitchPeriodInRange(samples: ShortArray, minPeriod: Int, maxPeriod: Int): Int {
        // For now, as in Sonic, the pitch of the (mixed) first channel only.
        var bestPeriod = 0
        var worstPeriod = 255
        var minDiff = 1
        var maxDiff = 0
        for (period in minPeriod..maxPeriod) {
            var diff = 0
            for (i in 0 until period) {
                diff += abs(samples[i] - samples[period + i])
            }
            // diff is at most a 24-bit number (fewer than 256 terms), so these
            // products cannot overflow.
            if (diff * bestPeriod < minDiff * period) {
                minDiff = diff
                bestPeriod = period
            }
            if (diff * worstPeriod > maxDiff * period) {
                maxDiff = diff
                worstPeriod = period
            }
        }
        this.minDiff = minDiff / bestPeriod
        this.maxDiff = maxDiff / worstPeriod
        return bestPeriod
    }

    private fun previousPeriodBetter(minDiff: Int, maxDiff: Int): Boolean {
        if (minDiff == 0 || prevPeriod == 0) return false
        if (maxDiff > minDiff * 3) return false
        if (minDiff * 2 <= prevMinDiff * 3) return false
        return true
    }

    private fun findPitchPeriod(samples: FloatArray, position: Int): Int {
        val skip = if (inputSampleRateHz > AMDF_FREQUENCY) inputSampleRateHz / AMDF_FREQUENCY else 1
        var period: Int
        if (channelCount == 1 && skip == 1) {
            downSampleInput(samples, position, 1)
            period = findPitchPeriodInRange(downSampleBuffer, minPeriod, maxPeriod)
        } else {
            downSampleInput(samples, position, skip)
            period = findPitchPeriodInRange(downSampleBuffer, minPeriod / skip, maxPeriod / skip)
            if (skip != 1) {
                period *= skip
                val minP = maxOf(period - skip * 4, minPeriod)
                val maxP = minOf(period + skip * 4, maxPeriod)
                downSampleInput(samples, position, 1)
                period = findPitchPeriodInRange(downSampleBuffer, minP, maxP)
            }
        }
        val retPeriod = if (previousPeriodBetter(minDiff, maxDiff)) prevPeriod else period
        prevMinDiff = minDiff
        prevPeriod = period
        return retPeriod
    }

    private fun moveNewSamplesToPitchBuffer(originalOutputFrameCount: Int) {
        val frameCount = outputFrameCount - originalOutputFrameCount
        pitchBuffer = ensureSpaceForAdditionalFrames(pitchBuffer, pitchFrameCount, frameCount)
        System.arraycopy(
            outputBuffer, originalOutputFrameCount * channelCount,
            pitchBuffer, pitchFrameCount * channelCount,
            frameCount * channelCount,
        )
        outputFrameCount = originalOutputFrameCount
        pitchFrameCount += frameCount
    }

    private fun removePitchFrames(frameCount: Int) {
        if (frameCount == 0) return
        System.arraycopy(
            pitchBuffer, frameCount * channelCount,
            pitchBuffer, 0,
            (pitchFrameCount - frameCount) * channelCount,
        )
        pitchFrameCount -= frameCount
    }

    private fun interpolate(input: FloatArray, inPos: Int, oldSampleRate: Long, newSampleRate: Long): Float {
        val left = input[inPos]
        val right = input[inPos + channelCount]
        val position = newRatePosition * oldSampleRate
        val leftPosition = oldRatePosition * newSampleRate
        val rightPosition = (oldRatePosition + 1) * newSampleRate
        val ratio = (rightPosition - position).toDouble()
        val width = (rightPosition - leftPosition).toDouble()
        return ((ratio * left + (width - ratio) * right) / width).toFloat()
    }

    private fun adjustRate(rate: Float, originalOutputFrameCount: Int) {
        if (outputFrameCount == originalOutputFrameCount) return
        var newSampleRate = (inputSampleRateHz / rate).toLong()
        var oldSampleRate = inputSampleRateHz.toLong()
        while (newSampleRate != 0L && oldSampleRate != 0L &&
            newSampleRate % 2 == 0L && oldSampleRate % 2 == 0L
        ) {
            newSampleRate /= 2
            oldSampleRate /= 2
        }
        moveNewSamplesToPitchBuffer(originalOutputFrameCount)
        // Leave at least one pitch sample in the buffer.
        for (position in 0 until pitchFrameCount - 1) {
            while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
                outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, 1)
                for (i in 0 until channelCount) {
                    outputBuffer[outputFrameCount * channelCount + i] =
                        interpolate(pitchBuffer, position * channelCount + i, oldSampleRate, newSampleRate)
                }
                newRatePosition++
                outputFrameCount++
            }
            oldRatePosition++
            if (oldRatePosition.toLong() == oldSampleRate) {
                oldRatePosition = 0
                // Media3 asserts newRatePosition == newSampleRate here. It
                // holds whenever the ratio has been constant since the last
                // restart, which setPitch guarantees.
                newRatePosition = 0
            }
        }
        removePitchFrames(pitchFrameCount - 1)
    }

    private fun skipPitchPeriod(samples: FloatArray, position: Int, speed: Double, period: Int): Int {
        val newFrameCount: Int
        if (speed >= 2.0) {
            val expectedFrameCount = period / (speed - 1.0) + accumulatedSpeedAdjustmentError
            newFrameCount = Math.round(expectedFrameCount).toInt()
            accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount
        } else {
            newFrameCount = period
            val expectedInputToCopy =
                period * (2.0 - speed) / (speed - 1.0) + accumulatedSpeedAdjustmentError
            remainingInputToCopyFrameCount = Math.round(expectedInputToCopy).toInt()
            accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount
        }
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, newFrameCount)
        overlapAdd(
            newFrameCount, channelCount,
            outputBuffer, outputFrameCount,
            samples, position,
            samples, position + period,
        )
        outputFrameCount += newFrameCount
        return newFrameCount
    }

    private fun insertPitchPeriod(samples: FloatArray, position: Int, speed: Double, period: Int): Int {
        val newFrameCount: Int
        if (speed < 0.5) {
            val expectedFrameCount = period * speed / (1.0 - speed) + accumulatedSpeedAdjustmentError
            newFrameCount = Math.round(expectedFrameCount).toInt()
            accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount
        } else {
            newFrameCount = period
            val expectedInputToCopy =
                period * (2.0 * speed - 1.0) / (1.0 - speed) + accumulatedSpeedAdjustmentError
            remainingInputToCopyFrameCount = Math.round(expectedInputToCopy).toInt()
            accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount
        }
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, period + newFrameCount)
        System.arraycopy(
            samples, position * channelCount,
            outputBuffer, outputFrameCount * channelCount,
            period * channelCount,
        )
        overlapAdd(
            newFrameCount, channelCount,
            outputBuffer, outputFrameCount + period,
            samples, position + period,
            samples, position,
        )
        outputFrameCount += period + newFrameCount
        return newFrameCount
    }

    private fun changeSpeed(speed: Double) {
        if (inputFrameCount < maxRequiredFrameCount) return
        val frameCount = inputFrameCount
        var positionFrames = 0
        do {
            if (remainingInputToCopyFrameCount > 0) {
                positionFrames += copyInputToOutput(positionFrames)
            } else {
                val period = findPitchPeriod(inputBuffer, positionFrames)
                positionFrames += if (speed > 1.0) {
                    period + skipPitchPeriod(inputBuffer, positionFrames, speed, period)
                } else {
                    insertPitchPeriod(inputBuffer, positionFrames, speed, period)
                }
            }
        } while (positionFrames + maxRequiredFrameCount <= frameCount)
        removeProcessedInputFrames(positionFrames)
    }

    private fun processStreamInput() {
        val originalOutputFrameCount = outputFrameCount
        val s = speed.toDouble() / pitch
        val r = rate * pitch
        if (s > 1.00001 || s < 0.99999) {
            changeSpeed(s)
        } else {
            copyToOutput(inputBuffer, 0, inputFrameCount)
            inputFrameCount = 0
            // Everything queued was just copied, including any frames the last
            // non-unity block had scheduled for a straight copy. Media3 never
            // gets here with that count set — its speed cannot change on a
            // live Sonic — but ours can, and a stale count would copy the next
            // block's frames unprocessed once speed moves off unity again.
            remainingInputToCopyFrameCount = 0
        }
        if (r != 1.0f) adjustRate(r, originalOutputFrameCount)
    }

    private companion object {
        const val MINIMUM_PITCH = 65
        const val MAXIMUM_PITCH = 400
        const val AMDF_FREQUENCY = 4000

        /** A float sample as the 16-bit value the period search compares. */
        fun toShort(sample: Float): Int = (sample * 32768f).roundToInt().coerceIn(-32768, 32767)

        fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            out: FloatArray,
            outPosition: Int,
            rampDown: FloatArray,
            rampDownPosition: Int,
            rampUp: FloatArray,
            rampUpPosition: Int,
        ) {
            for (i in 0 until channelCount) {
                var o = outPosition * channelCount + i
                var u = rampUpPosition * channelCount + i
                var d = rampDownPosition * channelCount + i
                for (t in 0 until frameCount) {
                    out[o] = (rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount
                    o += channelCount
                    d += channelCount
                    u += channelCount
                }
            }
        }
    }
}
