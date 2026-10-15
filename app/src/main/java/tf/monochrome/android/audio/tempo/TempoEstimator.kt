package tf.monochrome.android.audio.tempo

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * A track's tempo, from its audio as it plays.
 *
 * Four stages:
 *
 *  1. **Onset envelope.** Every [ENVELOPE_HZ]th of a second, the energy of
 *     four bands — the kick's (under 180 Hz), the whole signal, the hats'
 *     (over 2.5 kHz) and the body's (under 2.5 kHz: kick and snare, without
 *     the hats) — and how much each *rose* since the last step. The first
 *     three make the envelope stage 2 searches; the kick and body bands
 *     make the one stages 3 and 4 place the beat on.
 *     Only rises count (spectral flux, half-wave rectified): a beat is
 *     something starting. The energy is smoothed over [SMOOTH_MS] first, or
 *     a kick's own cycle (18 ms at 55 Hz, longer than a step) would ripple
 *     into onsets of its own, and compressed as log(1 + λ·energy), the
 *     usual onset compression: a quiet passage still counts, but silence
 *     cannot, where a plain log turns the faintest sound after it into the
 *     biggest onset in the song.
 *  2. **Periodicity.** The envelope's autocorrelation over the last
 *     [WINDOW_FRAMES] steps (about ten seconds), searched between [MIN_BPM]
 *     and [MAX_BPM]; its strongest peaks, each refined by a parabola to a lag
 *     finer than one step, are the candidates.
 *  3. **The beat.** Of the candidates, the one whose grid the kicks and
 *     snares land on (the kick and body bands' onsets, without the hats,
 *     which tick on the off-beats of most dance music), at its best phase,
 *     weighted by a
 *     log-normal prior centred on 120 BPM (Ellis, "Beat Tracking by Dynamic
 *     Programming", 2007). The autocorrelation alone took a DnB two-step's
 *     kicks, 2.5 beats apart, for 116 BPM.
 *  4. **The octave.** Then half or double time, which a prior alone cannot
 *     settle: 87 and 174, 100 and 200, look alike to it, and the prior chose
 *     the slower, so hardcore and DnB read at half. Of the beat's levels in
 *     range, the tempo is the fastest whose positions nearly all hold a kick
 *     or a snare ([octave]): whether there is a hit, not how loud it is, so
 *     the mix does not move it.
 *
 * Known limit: a DnB two-step whose snare sits far under its kick (a kick
 * twice as loud, or a snare half as loud, as in an even mix) reads at half
 * time. The kick on the "and" just before the second snare hides that snare's
 * onset, and the level at 174 then has only two hits in four.
 *
 * [estimate] is null until there is enough audio, and for anything without
 * a steady pulse (an intro, ambient, speech).
 *
 * Pure Kotlin without Android types, so it is tested against synthetic beats.
 * Cheap enough for the playback thread: three biquads and four smoothed
 * energies a sample, and one estimate a second of ~300k multiply-adds.
 */
class TempoEstimator(sampleRate: Int) {

    private val hop = max(1, (sampleRate / ENVELOPE_HZ.toFloat()).roundToInt())
    private val envelopeHz = sampleRate.toFloat() / hop

    private val lowPass = Biquad.lowPass(sampleRate.toFloat(), 180f)
    private val highPass = Biquad.highPass(sampleRate.toFloat(), 2500f)
    private val bodyPass = Biquad.lowPass(sampleRate.toFloat(), 2500f)

    private var hopCount = 0
    private val smoothCoef = 1f - exp(-1f / (SMOOTH_MS * 0.001f * sampleRate))
    private val bandEnergy = FloatArray(BANDS)
    private val previousLog = FloatArray(BANDS)
    private var primed = false

    private val envelope = FloatArray(WINDOW_FRAMES)

    /**
     * The kick and body bands' onsets: the kick and the snare, which say
     * where the beat is, without the hats. Hats tick on the off-beats of most
     * dance music, so with them a four-on-the-floor kick read as twice its
     * tempo. Not the whole signal's band either, which hears the hats too:
     * loud ones made a boom-bap groove's eighth notes all beats.
     */
    private val beatEnvelope = FloatArray(WINDOW_FRAMES)

    /** The kick band's onsets alone. */
    private val kickEnvelope = FloatArray(WINDOW_FRAMES)
    private var written = 0L

    // An estimate's working space, made once: it runs on the playback thread.
    private val work = FloatArray(WINDOW_FRAMES)
    private val workBeats = FloatArray(WINDOW_FRAMES)
    private val workKicks = FloatArray(WINDOW_FRAMES)
    private val scores = FloatArray(WINDOW_FRAMES / 2 + 3)
    private val candidates = IntArray(CANDIDATES)
    private val periods = FloatArray(CANDIDATES)
    private val gridScores = FloatArray(CANDIDATES)
    private val positionValues = FloatArray((WINDOW_FRAMES * MAX_BPM / (60f * envelopeHz)).toInt() + 4)
    private val sortedValues = FloatArray(positionValues.size)

    /** Feeds [count] mono samples. */
    fun feed(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            // A NaN or an infinity (a corrupt float file) is silence, and a
            // sample far past full scale is clipped: either would otherwise
            // stay in the filters for the rest of the track.
            val x = samples[i].let { if (it.isFinite()) it.coerceIn(-LOUDEST, LOUDEST) else 0f }
            val low = lowPass.process(x)
            val high = highPass.process(x)
            val body = bodyPass.process(x)
            bandEnergy[0] += smoothCoef * (low * low - bandEnergy[0])
            bandEnergy[1] += smoothCoef * (x * x - bandEnergy[1])
            bandEnergy[2] += smoothCoef * (high * high - bandEnergy[2])
            bandEnergy[3] += smoothCoef * (body * body - bandEnergy[3])
            if (++hopCount == hop) {
                pushFrame()
                hopCount = 0
            }
        }
    }

    private fun pushFrame() {
        var flux = 0f
        var beatFlux = 0f
        var kickFlux = 0f
        for (b in 0 until BANDS) {
            val log = ln(1f + COMPRESSION * bandEnergy[b])
            if (primed) {
                val rise = BAND_WEIGHT[b] * max(0f, log - previousLog[b])
                if (b != BODY_BAND) flux += rise
                if (b == KICK_BAND || b == BODY_BAND) beatFlux += rise
                if (b == KICK_BAND) kickFlux = rise
            }
            previousLog[b] = log
        }
        primed = true
        val at = (written % WINDOW_FRAMES).toInt()
        envelope[at] = flux
        beatEnvelope[at] = beatFlux
        kickEnvelope[at] = kickFlux
        written++
    }

    /** Seconds of envelope collected, up to the window. */
    val seconds: Float get() = minOf(written, WINDOW_FRAMES.toLong()) / envelopeHz

    /** A discontinuity (a seek): the next step must not read as one big onset. */
    fun discontinuity() {
        primed = false
        hopCount = 0
        bandEnergy.fill(0f)
    }

    /** Forgets everything: a new track. */
    fun reset() {
        discontinuity()
        lowPass.reset()
        highPass.reset()
        bodyPass.reset()
        envelope.fill(0f)
        beatEnvelope.fill(0f)
        kickEnvelope.fill(0f)
        written = 0
    }

    /**
     * The tempo of the last [WINDOW_FRAMES] steps in BPM, or null without
     * enough audio or a clear pulse.
     */
    fun estimate(): Float? {
        val n = minOf(written, WINDOW_FRAMES.toLong()).toInt()
        if (n < envelopeHz * MIN_SECONDS) return null
        // Oldest first, without the mean: autocorrelation of a positive
        // envelope is otherwise dominated by its own DC.
        val start = if (written > WINDOW_FRAMES) (written % WINDOW_FRAMES).toInt() else 0
        val e = work
        val beats = workBeats
        val kicks = workKicks
        var mean = 0f
        for (i in 0 until n) {
            e[i] = envelope[(start + i) % WINDOW_FRAMES]
            beats[i] = beatEnvelope[(start + i) % WINDOW_FRAMES]
            kicks[i] = kickEnvelope[(start + i) % WINDOW_FRAMES]
            mean += e[i]
        }
        mean /= n
        for (i in 0 until n) e[i] -= mean

        val zero = acf(e, n, 0)
        if (!(zero > 1e-9f)) return null

        val minLag = (60f * envelopeHz / MAX_BPM).toInt()
        val maxLag = minOf((60f * envelopeHz / MIN_BPM).toInt() + 1, n / 2)
        if (maxLag <= minLag + 2) return null
        val scores = scores
        for (lag in minLag - 1..maxLag + 1) scores[lag] = acf(e, n, lag)

        // The periodicities: every peak of the autocorrelation in range, the
        // strongest few kept. The beat is one of them, but not always the
        // strongest: a DnB two-step's kicks are 2.5 beats apart, and a rock
        // beat's kick and snare repeat every two.
        var count = 0
        for (lag in minLag..maxLag) {
            val s = scores[lag]
            if (s <= 0f || s < scores[lag - 1] || s < scores[lag + 1]) continue
            if (count < CANDIDATES) {
                candidates[count++] = lag
            } else if (s > scores[candidates[CANDIDATES - 1]]) {
                candidates[CANDIDATES - 1] = lag
            } else {
                continue
            }
            // Strongest first; an equal one stays behind the shorter lag before it.
            var i = count - 1
            while (i > 0 && scores[candidates[i]] > scores[candidates[i - 1]]) {
                val t = candidates[i]; candidates[i] = candidates[i - 1]; candidates[i - 1] = t
                i--
            }
        }
        if (count == 0) return null
        // A pulse, or just texture? Relative to the envelope's own energy.
        if (scores[candidates[0]] / zero < MIN_CONFIDENCE) return null

        // The beat is the grid the kicks and snares land on: each candidate
        // scored by how much onset its beats carry, at its best phase,
        // weighted gently toward common tempi.
        for (k in 0 until count) {
            val p = refined(scores, candidates[k])
            periods[k] = p
            gridScores[k] = gridStrength(beats, n, p, bestPhase(beats, n, p)) * prior(60f * envelopeHz / p)
        }
        // Candidates an octave apart are one beat counted at two levels, so
        // they vote together: 87 and 174 back each other, a DnB two-step's
        // 116 (its kicks 2.5 beats apart) stands alone. Taken one by one, the
        // three scored so alike that the answer flipped from one second to
        // the next. The family that wins sends its strongest member to
        // [octave], which chooses the level.
        var period = 0f
        var bestFamily = 0f
        for (k in 0 until count) {
            var family = 0f
            for (j in 0 until count) if (sameBeat(periods[k], periods[j])) family += gridScores[j]
            if (family > bestFamily || (family == bestFamily && gridScores[k] > 0f && period > 0f && gridScores[k] > scoreOf(period, count))) {
                bestFamily = family
                period = periods[k]
            }
        }
        if (!(period > 0f)) return null
        // Within the winning family, its strongest member.
        for (k in 0 until count) if (sameBeat(periods[k], period) && gridScores[k] > scoreOf(period, count)) period = periods[k]
        return 60f * envelopeHz / octave(beats, kicks, n, period)
    }


    /** The grid score of the candidate with [period], of the first [count]. */
    private fun scoreOf(period: Float, count: Int): Float {
        for (k in 0 until count) if (periods[k] == period) return gridScores[k]
        return 0f
    }

    /** Whether [a] and [b] are one beat at different levels: a power of two apart, within a few percent. */
    private fun sameBeat(a: Float, b: Float): Boolean {
        if (!(a > 0f && b > 0f) || !a.isFinite() || !b.isFinite()) return false
        var ratio = if (a > b) a / b else b / a
        if (!ratio.isFinite()) return false
        while (ratio > 1.5f) ratio /= 2f
        return kotlin.math.abs(ratio - 1f) < SAME_BEAT_TOLERANCE
    }

    /** [lag], refined to a fraction of a step by a parabola through its peak and neighbours. */
    private fun refined(scores: FloatArray, lag: Int): Float {
        val a = scores[lag - 1]
        val b = scores[lag]
        val c = scores[lag + 1]
        val denom = a - 2f * b + c
        val offset = if (denom < 0f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f
        return lag + offset
    }

    /**
     * Half time or double time: of the levels of [start]'s beat in range
     * (twice as fast, twice as slow), the fastest whose beats nearly all
     * carry an onset. A position counts when it holds at least [FILL] of a
     * strong beat's onset; a level counts when [OCCUPIED] of its positions do.
     *
     * Whether there is a hit, not how loud: a kick-snare backbeat is the
     * same tempo with a quiet snare, and comparing their strengths halved a
     * pop song when its snare sat under its kick. A DnB two-step fills three
     * of every four beats at 174, a pop or rock backbeat all four; a
     * breakbeat's or a boom-bap groove's eighth notes only half to five
     * eighths, so they stay at the slower level. Hats are not in [beats], so
     * a song's hats never fill a level of their own.
     *
     * Past [PARITY_BPM], only a kick on every beat (hardcore) makes the
     * level: house's off-beat hats and trance's off-beat bass fill 250 too.
     */
    private fun octave(beats: FloatArray, kicks: FloatArray, n: Int, start: Float): Float {
        // The loops below halve and double the period: never with zero or infinity.
        if (!(start > 0f) || !start.isFinite()) return start
        // A little past either end: a song at 60 measures 59.9 as often as
        // 60.1, and its own level fell out of range and doubled it.
        val minPeriod = 60f * envelopeHz / MAX_BPM / RANGE_SLACK
        val maxPeriod = 60f * envelopeHz / MIN_BPM * RANGE_SLACK
        var fastest = start
        while (fastest / 2f >= minPeriod) fastest /= 2f
        while (fastest < minPeriod) fastest *= 2f
        var levels = 0
        while (levels < MAX_LEVELS && fastest * (1 shl levels) <= maxPeriod) levels++
        if (levels == 0) return inRange(start, minPeriod, maxPeriod)

        // Each beat of the fastest level, at its best phase; a slower level's
        // beats are every second or fourth of them.
        val values = positionValues
        var count = 0
        var at = bestPhase(beats, n, fastest)
        while (at < n && count < values.size) {
            values[count++] = onsetNear(beats, n, at.roundToInt())
            at += fastest
        }
        if (count < MIN_POSITIONS) return inRange(start, minPeriod, maxPeriod)
        // A strong beat: the upper quartile of the slowest level's beats,
        // which land on the strongest onsets. Not of the fastest level's:
        // at 62 BPM that is sixteenth notes, mostly empty, and its quartile
        // was so low that a hat made a beat.
        val slowest = 1 shl (levels - 1)
        var slowCount = 0
        var j = offsetOf(values, count, slowest)
        while (j < count) { sortedValues[slowCount++] = values[j]; j += slowest }
        if (slowCount == 0) return inRange(start, minPeriod, maxPeriod)
        java.util.Arrays.sort(sortedValues, 0, slowCount)
        val strong = sortedValues[(slowCount * 3) / 4]
        if (!(strong > 0f)) return inRange(start, minPeriod, maxPeriod)
        val floor = FILL * strong

        for (level in 0 until levels) {
            val every = 1 shl level
            val bestOffset = offsetOf(values, count, every)
            var filled = 0
            var total = 0
            var i = bestOffset
            while (i < count) {
                if (values[i] >= floor) filled++
                total++
                i += every
            }
            val period = fastest * every
            val bpm = 60f * envelopeHz / period
            if (total >= MIN_POSITIONS && filled >= OCCUPIED * total &&
                (bpm <= PARITY_BPM || kickOnEveryBeat(beats, kicks, n, period * 2f))
            ) return period
        }
        return inRange(start, minPeriod, maxPeriod)
    }

    /** [period] (positive and finite), doubled or halved into [minPeriod]..[maxPeriod]. */
    private fun inRange(period: Float, minPeriod: Float, maxPeriod: Float): Float {
        var p = period
        while (p < minPeriod) p *= 2f
        while (p > maxPeriod) p /= 2f
        return p
    }

    /**
     * A level's own phase, for a level of every [every]th of the fastest
     * level's beats: whichever of them its grid lands on carry the most.
     */
    private fun offsetOf(values: FloatArray, count: Int, every: Int): Int {
        var best = 0
        var bestSum = -1f
        for (offset in 0 until every) {
            var sum = 0f
            var i = offset
            while (i < count) { sum += values[i]; i += every }
            if (sum > bestSum) { bestSum = sum; best = offset }
        }
        return best
    }

    /**
     * Whether the beats of [period] and those halfway between are one sound:
     * a kick on every beat of the level twice as fast. As strong, and as
     * sudden: a four-on-the-floor's off-beat bass mixed to land as hard as
     * its kick passed on strength alone and read 125 as 250, but a bass note
     * swells in over tens of milliseconds where a kick starts in two.
     */
    private fun kickOnEveryBeat(beats: FloatArray, kicks: FloatArray, n: Int, period: Float): Boolean =
        (betweenShare(beats, n, period) >= PARITY_SHARE || betweenShare(kicks, n, period) >= PARITY_SHARE) &&
            attackLikeness(kicks, n, period) >= SAME_ATTACK

    /**
     * How alike the attacks are of [period]'s beats and those halfway
     * between: each grid's [sharpness], the lesser over the greater.
     */
    private fun attackLikeness(onsets: FloatArray, n: Int, period: Float): Float {
        val fast = period / 2f
        val phase = bestPhase(onsets, n, fast)
        var other = phase + fast
        var strongest = -1f
        for (d in -MIDPOINT_SEARCH..MIDPOINT_SEARCH) {
            val s = gridStrength(onsets, n, period, phase + fast + d)
            if (s > strongest) { strongest = s; other = phase + fast + d }
        }
        val a = sharpness(onsets, n, period, phase)
        val b = sharpness(onsets, n, period, other)
        val sharper = max(a, b)
        return if (sharper > 0f) kotlin.math.min(a, b) / sharper else 0f
    }

    /**
     * How sudden the onsets on a grid are: the steepest pair of steps at
     * each beat over all the rise within [ATTACK_SPAN] of it. Near 1 for a
     * kick, which rises at once; lower for a note that swells in.
     */
    private fun sharpness(onsets: FloatArray, n: Int, period: Float, phase: Float): Float {
        var steepest = 0f
        var all = 0f
        var at = phase
        while (at < n) {
            val i = at.roundToInt()
            steepest += onsetNear(onsets, n, i)
            for (j in i - ATTACK_SPAN..i + ATTACK_SPAN) if (j in 0 until n) all += onsets[j]
            at += period
        }
        return if (all > 0f) steepest / all else 0f
    }

    /** The strongest pair of steps within [SLACK] of step [i]: one beat of a grid. */
    private fun onsetNear(beats: FloatArray, n: Int, i: Int): Float {
        var m = 0f
        for (j in i - SLACK..i + SLACK) {
            if (j < 0 || j >= n) continue
            val pair = beats[j] + (if (j + 1 < n) beats[j + 1] else 0f)
            if (pair > m) m = pair
        }
        return m
    }

    /**
     * How alike the two halves of the grid twice as fast as [period] are:
     * its beats that fall on [period]'s, and those halfway between. Near 1
     * when every beat of the faster grid is as strong as the rest.
     *
     * Aligned on the faster grid, not on [period]'s: a DnB two-step's 87
     * grid catches about as much onset on its kick on the "and" as on its
     * snares, and aligned there, its midpoints fell between the real beats
     * and the answer flipped from one second to the next.
     */
    private fun betweenShare(beats: FloatArray, n: Int, period: Float): Float {
        val fast = period / 2f
        val phase = bestPhase(beats, n, fast)
        val a = gridStrength(beats, n, period, phase)
        // The other half's own best phase, close to halfway: a period refined
        // from the autocorrelation is a fraction of a step off, and the half
        // of it lands a step or two from where the onsets are.
        var b = 0f
        for (d in -MIDPOINT_SEARCH..MIDPOINT_SEARCH) b = max(b, gridStrength(beats, n, period, phase + fast + d))
        val stronger = max(a, b)
        if (stronger <= 0f) return 0f
        return kotlin.math.min(a, b) / stronger
    }

    /** The phase, in steps, at which [period]'s grid catches the most onset. */
    private fun bestPhase(beats: FloatArray, n: Int, period: Float): Float {
        var best = 0f
        var bestStrength = -1f
        var phase = 0
        while (phase < period) {
            val s = gridStrength(beats, n, period, phase.toFloat())
            if (s > bestStrength) {
                bestStrength = s
                best = phase.toFloat()
            }
            phase++
        }
        return best
    }

    /**
     * The mean onset at each beat of a grid of [period] steps from [phase],
     * each beat taken as the strongest pair of steps within [SLACK] of it: an
     * onset builds over a few steps, and lands early or late of a fractional
     * grid.
     */
    private fun gridStrength(beats: FloatArray, n: Int, period: Float, phase: Float): Float {
        var sum = 0f
        var count = 0
        var at = phase
        while (at < n) {
            // Two steps at a time: an onset that starts near a step's edge is
            // split across two, and taken one step at a time every other kick
            // of a tempo in step with the envelope (180 BPM, exactly) read as
            // half as strong as the rest.
            sum += onsetNear(beats, n, at.roundToInt())
            count++
            at += period
        }
        return if (count == 0) 0f else sum / count
    }

    private fun acf(e: FloatArray, n: Int, lag: Int): Float {
        var sum = 0f
        for (i in lag until n) sum += e[i] * e[i - lag]
        return sum / (n - lag)
    }

    /** Log-normal tempo prior: 1 at [PRIOR_BPM], falling an octave either side. */
    private fun prior(bpm: Float): Float {
        val octaves = ln(bpm / PRIOR_BPM) / LN2
        return exp(-0.5f * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
    }

    /** RBJ biquad, transposed direct form II. */
    private class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f
        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        fun reset() { z1 = 0f; z2 = 0f }

        companion object {
            private const val Q = 0.70710678f

            fun lowPass(sr: Float, hz: Float): Biquad {
                val k = tan(PI * hz / sr).toFloat()
                val norm = 1f / (1f + k / Q + k * k)
                val b0 = k * k * norm
                return Biquad(b0, 2f * b0, b0, 2f * (k * k - 1f) * norm, (1f - k / Q + k * k) * norm)
            }

            fun highPass(sr: Float, hz: Float): Biquad {
                val k = tan(PI * hz / sr).toFloat()
                val norm = 1f / (1f + k / Q + k * k)
                return Biquad(norm, -2f * norm, norm, 2f * (k * k - 1f) * norm, (1f - k / Q + k * k) * norm)
            }
        }
    }

    companion object {
        /** Onset envelope rate: one step every 5 ms. */
        const val ENVELOPE_HZ = 200

        /** About ten seconds of envelope. */
        const val WINDOW_FRAMES = 2048

        const val MIN_BPM = 60f

        /** Hardcore and frenchcore run past 200; above this, a tempo is its half. */
        const val MAX_BPM = 250f

        /** No answer before this much audio. */
        const val MIN_SECONDS = 6f

        private const val MIN_CONFIDENCE = 0.08f

        /** Float PCM past full scale is clipped here, so its square stays finite. */
        private const val LOUDEST = 16f
        private const val SMOOTH_MS = 20f
        private const val COMPRESSION = 1000f
        private const val PRIOR_BPM = 120f
        private const val PRIOR_OCTAVES = 0.9f
        private const val BANDS = 4
        private const val KICK_BAND = 0
        private const val BODY_BAND = 3

        /** Periodicities weighed as the beat. */
        private const val CANDIDATES = 6

        /**
         * A position holds a beat at this share of a strong beat's onset.
         * Measured on synthetic kits across eleven genres and eight mixes:
         * from 0.15 to 0.25 nothing the old estimator read right went wrong,
         * and this is the middle; real songs have bass and chords on the
         * off-beats that synthetic drums do not, so not the lowest.
         */
        private const val FILL = 0.2f

        /**
         * A level is the beat when this share of its positions hold one:
         * between a breakbeat's or a slow groove's eighth notes (five in
         * eight) and a DnB two-step's beats (three in four).
         */
        private const val OCCUPIED = 0.7f

        /** Past this tempo only a kick on every beat makes a level: hardcore, not house's off-beat hats. */
        private const val PARITY_BPM = 200f

        /** The halfway positions at least this share of the beats: a kick on every one. */
        private const val PARITY_SHARE = 0.8f

        /**
         * ...and their attacks at least this alike. Hardcore's kicks measure
         * 0.98-1, an off-beat bass against its kick 0.74 at most.
         */
        private const val SAME_ATTACK = 0.9f

        /** Steps either side of a beat its attack is measured over: 50 ms. */
        private const val ATTACK_SPAN = 10

        /** Levels of one beat in range: 60 to 250 BPM spans two octaves. */
        private const val MAX_LEVELS = 3

        /** How far past [MIN_BPM] and [MAX_BPM] a level of the beat may lie. */
        private const val RANGE_SLACK = 1.02f

        /** Fewer beats than this in the window say nothing of a level. */
        private const val MIN_POSITIONS = 4

        /** Steps either side of a grid point an onset may land. */
        private const val SLACK = 2

        /** Steps either side of halfway the midpoints' grid is looked for. */
        private const val MIDPOINT_SEARCH = 3

        /** How near a power of two two candidates' periods must be to count as one beat. */
        private const val SAME_BEAT_TOLERANCE = 0.04f
        // The kick carries the pulse in most music; the hats keep it in time
        // where there is none.
        private val BAND_WEIGHT = floatArrayOf(1.0f, 0.6f, 0.4f, 0.6f)
        private val LN2 = ln(2f)
    }
}
