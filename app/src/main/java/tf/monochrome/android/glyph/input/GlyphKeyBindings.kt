// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.input

import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * The keyboard keys that play the lanes: two a lane, as StepMania's key
 * config gives every column a primary and a secondary key.
 *
 * Each course has its own set, since the four dance panels and pop'n's nine
 * buttons sit under different fingers: dance keeps the arrows and D F J K,
 * pop'n takes A S D F, Space, J K L and the semicolon, with the number row
 * as the second keys.
 *
 * Keys are Android key codes (`KeyEvent.KEYCODE_*`), the number a hardware
 * keyboard reports whatever its layout prints on the cap. [NONE] marks a slot
 * with nothing on it. Within a course a key is only ever on one slot: [bind]
 * takes it off wherever it was, so one key can never press two lanes.
 */
data class GlyphKeyBindings(
    /** By course, then by lane ordinal, each [SLOTS] long. */
    val keys: Map<CourseType, List<List<Int>>>,
) {
    init {
        require(CourseType.entries.all { course -> keys[course]?.let { laneKeys -> laneKeys.size == course.laneCount && laneKeys.all { it.size == SLOTS } } == true }) {
            "every course's lanes, $SLOTS keys each"
        }
    }

    /** The lane [code] plays in [course], or null when it plays none. */
    fun laneFor(code: Int, course: CourseType = CourseType.DANCE): GlyphLane? {
        if (code == NONE) return null
        val laneKeys = keys.getValue(course)
        return GlyphLane.forCourse(course).firstOrNull { code in laneKeys[it.ordinal] }
    }

    fun key(lane: GlyphLane, slot: Int, course: CourseType = CourseType.DANCE): Int =
        keys.getValue(course)[lane.ordinal][slot]

    /**
     * [code] on [lane]'s [slot] in [course]. Where it was before in that
     * course, that slot is emptied, and the result says which lane lost it so
     * the screen can tell the player.
     */
    fun bind(lane: GlyphLane, slot: Int, code: Int, course: CourseType = CourseType.DANCE): Rebound {
        var movedFrom: GlyphLane? = null
        val next = keys.getValue(course).mapIndexed { laneIndex, slots ->
            slots.mapIndexed { slotIndex, current ->
                val target = laneIndex == lane.ordinal && slotIndex == slot
                when {
                    target -> code
                    current == code && code != NONE -> {
                        if (laneIndex != lane.ordinal) movedFrom = GlyphLane.entries[laneIndex]
                        NONE
                    }
                    else -> current
                }
            }
        }
        return Rebound(GlyphKeyBindings(keys + (course to next)), movedFrom)
    }

    /** [course]'s keys back to their defaults; the other course's are kept. */
    fun reset(course: CourseType): GlyphKeyBindings = GlyphKeyBindings(keys + (course to DEFAULT.keys.getValue(course)))

    data class Rebound(val bindings: GlyphKeyBindings, val movedFrom: GlyphLane?)

    /**
     * `21,32;20,34;19,38;22,39|29,8;47,9;...`, for the preferences: the dance
     * lanes as they were always written, then pop'n's after a bar, so a
     * preference saved before pop'n still reads.
     */
    fun encode(): String = CourseType.entries.joinToString("|") { course ->
        keys.getValue(course).joinToString(";") { it.joinToString(",") }
    }

    companion object {
        const val SLOTS = 2
        const val NONE = 0

        // Android's KEYCODE_DPAD_* for the arrow keys, then D F J K.
        private const val LEFT_ARROW = 21
        private const val DOWN_ARROW = 20
        private const val UP_ARROW = 19
        private const val RIGHT_ARROW = 22
        private const val KEY_A = 29
        private const val KEY_D = 32
        private const val KEY_F = 34
        private const val KEY_J = 38
        private const val KEY_K = 39
        private const val KEY_L = 40
        private const val KEY_S = 47
        private const val KEY_SEMICOLON = 74
        private const val KEY_SPACE = 62

        /** KEYCODE_1; the row runs on to KEYCODE_9 = 16. */
        private const val KEY_1 = 8

        /** The arrow keys, and D F J K under the fingers of both hands. */
        private val DANCE_DEFAULT = listOf(
            listOf(LEFT_ARROW, KEY_D),
            listOf(DOWN_ARROW, KEY_F),
            listOf(UP_ARROW, KEY_J),
            listOf(RIGHT_ARROW, KEY_K),
        )

        /** The home row with the red button on Space, as pop'n's keyboard players lay it out; the number row behind. */
        private val POPN_DEFAULT = listOf(KEY_A, KEY_S, KEY_D, KEY_F, KEY_SPACE, KEY_J, KEY_K, KEY_L, KEY_SEMICOLON)
            .mapIndexed { index, key -> listOf(key, KEY_1 + index) }

        val DEFAULT = GlyphKeyBindings(
            mapOf(CourseType.DANCE to DANCE_DEFAULT, CourseType.POPN to POPN_DEFAULT),
        )

        /** What [reset] puts back, as a label: the keys a course starts with. */
        fun defaultLabel(course: CourseType): String = when (course) {
            CourseType.DANCE -> "Reset to the arrows and D F J K"
            CourseType.POPN -> "Reset to A S D F, Space, J K L ;"
        }

        /**
         * Read back from [encode]; anything malformed is the default. A line
         * with only the dance lanes, from before pop'n, keeps them and takes
         * pop'n's defaults.
         */
        fun decode(text: String?): GlyphKeyBindings {
            if (text.isNullOrBlank()) return DEFAULT
            val parts = text.split('|')
            if (parts.size > CourseType.entries.size) return DEFAULT
            val keys = DEFAULT.keys.toMutableMap()
            for ((index, part) in parts.withIndex()) {
                val course = CourseType.entries[index]
                val lanes = part.split(';').map { lane ->
                    lane.split(',').map { it.trim().toIntOrNull() ?: return DEFAULT }
                }
                if (lanes.size != course.laneCount || lanes.any { it.size != SLOTS }) return DEFAULT
                keys[course] = lanes
            }
            return GlyphKeyBindings(keys)
        }

        /**
         * What a key cap says. Kept here rather than asking Android's
         * KeyEvent.keyCodeToString, which reads "KEYCODE_DPAD_LEFT" and is
         * a stub in the JVM tests.
         */
        fun label(code: Int): String = when (code) {
            NONE -> "—"
            LEFT_ARROW -> "←"
            DOWN_ARROW -> "↓"
            UP_ARROW -> "↑"
            RIGHT_ARROW -> "→"
            in 29..54 -> ('A' + (code - 29)).toString()
            in 7..16 -> ('0' + (code - 7)).toString()
            in 144..153 -> "Num ${code - 144}"
            62 -> "Space"
            66 -> "Enter"
            61 -> "Tab"
            67 -> "Backspace"
            59 -> "L Shift"
            60 -> "R Shift"
            113 -> "L Ctrl"
            114 -> "R Ctrl"
            57 -> "L Alt"
            58 -> "R Alt"
            55 -> ","
            56 -> "."
            76 -> "/"
            74 -> ";"
            75 -> "'"
            71 -> "["
            72 -> "]"
            73 -> "\\"
            68 -> "`"
            69 -> "-"
            70 -> "="
            160 -> "Num Enter"
            in 131..142 -> "F${code - 130}"
            else -> "Key $code"
        }

        /** Keys that never bind: they leave a screen or work the system. */
        fun isReserved(code: Int): Boolean = code in RESERVED

        // Back, Home, Escape, the volume keys, power and the menu key.
        private val RESERVED = setOf(4, 3, 111, 24, 25, 164, 26, 82)
    }
}
