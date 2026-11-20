package tf.monochrome.android.audio.dsp

/**
 * Where each channel of an interleaved stream sits, for the processors that
 * have to treat channels differently: the EQs (which ear's curve), and the
 * mixer (which channels travel as a stereo pair).
 *
 * The orders are the ones Android plays, which is what Media3 builds the
 * AudioTrack from (`Util.getAudioTrackChannelConfig`): the channel-mask bits
 * in ascending order. So 5.1 is FL FR FC LFE BL BR and 7.1.4 is
 * FL FR FC LFE BL BR SL SR TFL TFR TBL TBR — every left channel immediately
 * followed by its right partner. Sixteen channels are 9.1.6 in FFmpeg's order
 * (the decoder's): FL FR FC LFE BL BR FLC FRC SL SR TFL TFR TBL TBR TSL TSR,
 * the same reading DownmixProcessor folds it by. Counts with no layout (9, 11,
 * 13–15) are read as consecutive left/right pairs, with a trailing odd
 * channel in the centre; that is the only reading that keeps pairs together
 * for any source that pairs its channels at all.
 *
 * Pure Kotlin, so the mapping is unit tested rather than trusted.
 */
object ChannelLayout {

    /** Widest stream the DSP accepts: a 9.1.6 bed. */
    const val MAX_CHANNELS = 16

    enum class Side { LEFT, RIGHT, CENTER }

    /**
     * One mixer lane: a left/right pair, or a single channel ([second] = -1)
     * that has no partner — centre, LFE, back centre.
     */
    data class Lane(val first: Int, val second: Int) {
        val isMono: Boolean get() = second < 0
    }

    private val L = Side.LEFT
    private val R = Side.RIGHT
    private val C = Side.CENTER

    /** The layouts Android defines, by channel count. LFE counts as centre. */
    private val KNOWN: Map<Int, Array<Side>> = mapOf(
        1 to arrayOf(C),                                // FC
        2 to arrayOf(L, R),                             // FL FR
        3 to arrayOf(L, R, C),                          // FL FR FC
        4 to arrayOf(L, R, L, R),                       // FL FR BL BR
        5 to arrayOf(L, R, C, L, R),                    // FL FR FC BL BR
        6 to arrayOf(L, R, C, C, L, R),                 // 5.1
        7 to arrayOf(L, R, C, C, L, R, C),              // 5.1 + BC
        8 to arrayOf(L, R, C, C, L, R, L, R),           // 7.1
        10 to arrayOf(L, R, C, C, L, R, L, R, L, R),    // 5.1.4
        12 to arrayOf(L, R, C, C, L, R, L, R, L, R, L, R), // 7.1.4
        16 to arrayOf(L, R, C, C, L, R, L, R, L, R, L, R, L, R, L, R), // 9.1.6
    )

    /** Which side each channel of a [count]-channel stream is on. */
    fun sides(count: Int): Array<Side> {
        require(count in 1..MAX_CHANNELS) { "unsupported channel count $count" }
        KNOWN[count]?.let { return it.copyOf() }
        return Array(count) { i ->
            when {
                i == count - 1 && count % 2 == 1 -> C
                i % 2 == 0 -> L
                else -> R
            }
        }
    }

    /** Whether channel [index] of a [count]-channel stream is the LFE. */
    fun isLfe(count: Int, index: Int): Boolean = count in LFE_COUNTS && index == 3

    /**
     * The mixer's lanes: each left channel followed by a right one travels as
     * a pair, and everything else on its own. Covers every channel exactly
     * once, in order.
     */
    fun lanes(count: Int): List<Lane> {
        val sides = sides(count)
        val lanes = ArrayList<Lane>(count)
        var i = 0
        while (i < count) {
            if (sides[i] == L && i + 1 < count && sides[i + 1] == R) {
                lanes += Lane(i, i + 1)
                i += 2
            } else {
                lanes += Lane(i, -1)
                i += 1
            }
        }
        return lanes
    }

    /**
     * A name for each of [lanes]' channel groups, in the same order — what the
     * mixer strip a group is routed to is called while it plays: "Front",
     * "Centre", "LFE", "Surround", "Top Front"…. Counts with no Android
     * layout are named by the channels they carry.
     */
    fun laneLabels(count: Int): List<String> {
        KNOWN_LABELS[count]?.let { return it }
        return lanes(count).map { lane ->
            if (lane.isMono) "Ch ${lane.first + 1}" else "Ch ${lane.first + 1}–${lane.second + 1}"
        }
    }

    private val KNOWN_LABELS: Map<Int, List<String>> = mapOf(
        2 to listOf("Front"),
        3 to listOf("Front", "Centre"),
        4 to listOf("Front", "Surround"),
        5 to listOf("Front", "Centre", "Surround"),
        6 to listOf("Front", "Centre", "LFE", "Surround"),
        7 to listOf("Front", "Centre", "LFE", "Surround", "Rear Centre"),
        8 to listOf("Front", "Centre", "LFE", "Rear", "Side"),
        10 to listOf("Front", "Centre", "LFE", "Surround", "Top Front", "Top Rear"),
        12 to listOf("Front", "Centre", "LFE", "Rear", "Side", "Top Front", "Top Rear"),
        16 to listOf(
            "Front", "Centre", "LFE", "Rear", "Front Wide", "Side",
            "Top Front", "Top Rear", "Top Side",
        ),
    )

    /**
     * Which speaker a channel is, as the native mixer names them
     * (`RoomRole` in snapin_processor.h, the spatial renderer's speaker
     * roles): what a Binaural Room on the master of a wide stream places each
     * channel by. The numbers cross JNI, so they are held to the native side
     * by `binaural_room_test` and to this table by `ChannelLayoutTest`.
     */
    object Role {
        const val NONE = -1
        const val L = 0
        const val R = 1
        const val C = 2
        const val LFE = 3
        /** A 5.x layout's surrounds, at 110 degrees. */
        const val LS = 4
        const val RS = 5
        /** A 7.x layout's side surrounds, at 90. */
        const val LSS = 6
        const val RSS = 7
        /** Its rear surrounds, at 135. */
        const val LRS = 8
        const val RRS = 9
        const val LTF = 10
        const val RTF = 11
        const val LTR = 12
        const val RTR = 13
        const val LTS = 14
        const val RTS = 15
        const val LW = 16
        const val RW = 17
        const val BACK_CENTRE = 18
    }

    private val ROLES: Map<Int, IntArray> = with(Role) {
        mapOf(
            2 to intArrayOf(L, R),
            3 to intArrayOf(L, R, C),
            4 to intArrayOf(L, R, LS, RS),
            5 to intArrayOf(L, R, C, LS, RS),
            6 to intArrayOf(L, R, C, LFE, LS, RS),
            7 to intArrayOf(L, R, C, LFE, LS, RS, BACK_CENTRE),
            8 to intArrayOf(L, R, C, LFE, LRS, RRS, LSS, RSS),
            10 to intArrayOf(L, R, C, LFE, LS, RS, LTF, RTF, LTR, RTR),
            12 to intArrayOf(L, R, C, LFE, LRS, RRS, LSS, RSS, LTF, RTF, LTR, RTR),
            // FFmpeg's 9.1.6: FLC/FRC are its front wides.
            16 to intArrayOf(L, R, C, LFE, LRS, RRS, LW, RW, LSS, RSS, LTF, RTF, LTR, RTR, LTS, RTS),
        )
    }

    /**
     * The speaker each channel of a [count]-channel stream is ([Role]), in
     * the stream's order. A count with no layout of its own (1, 9, 11,
     * 13–15) has none: every channel is [Role.NONE], and nothing places it.
     */
    fun roles(count: Int): IntArray {
        require(count in 1..MAX_CHANNELS) { "unsupported channel count $count" }
        return ROLES[count]?.copyOf() ?: IntArray(count) { Role.NONE }
    }

    /** Counts whose Android layout carries an LFE, always at index 3. */
    private val LFE_COUNTS = setOf(6, 7, 8, 10, 12, 16)
}
