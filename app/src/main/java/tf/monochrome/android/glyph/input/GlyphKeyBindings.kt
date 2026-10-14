// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.input

import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * The keyboard keys that play the four lanes: two a lane, as StepMania's key
 * config gives every column a primary and a secondary key.
 *
 * Keys are Android key codes (`KeyEvent.KEYCODE_*`), the number a hardware
 * keyboard reports whatever its layout prints on the cap. [NONE] marks a slot
 * with nothing on it. A key is only ever on one slot: [bind] takes it off
 * wherever it was, so one key can never press two lanes.
 */
data class GlyphKeyBindings(
    /** By lane ordinal (left, down, up, right), each [SLOTS] long. */
    val keys: List<List<Int>>,
) {
    init {
        require(keys.size == GlyphLane.entries.size && keys.all { it.size == SLOTS }) {
            "four lanes of $SLOTS keys"
        }
    }

    /** The lane [code] plays, or null when it plays none. */
    fun laneFor(code: Int): GlyphLane? {
        if (code == NONE) return null
        return GlyphLane.entries.firstOrNull { code in keys[it.ordinal] }
    }

    fun key(lane: GlyphLane, slot: Int): Int = keys[lane.ordinal][slot]

    /**
     * [code] on [lane]'s [slot]. Where it was before, that slot is emptied,
     * and the result says which lane lost it so the screen can tell the player.
     */
    fun bind(lane: GlyphLane, slot: Int, code: Int): Rebound {
        var movedFrom: GlyphLane? = null
        val next = keys.mapIndexed { laneIndex, slots ->
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
        return Rebound(GlyphKeyBindings(next), movedFrom)
    }

    data class Rebound(val bindings: GlyphKeyBindings, val movedFrom: GlyphLane?)

    /** `21,32;20,34;19,38;22,39`, for the preferences. */
    fun encode(): String = keys.joinToString(";") { it.joinToString(",") }

    companion object {
        const val SLOTS = 2
        const val NONE = 0

        // Android's KEYCODE_DPAD_* for the arrow keys, then D F J K.
        private const val LEFT_ARROW = 21
        private const val DOWN_ARROW = 20
        private const val UP_ARROW = 19
        private const val RIGHT_ARROW = 22
        private const val KEY_D = 32
        private const val KEY_F = 34
        private const val KEY_J = 38
        private const val KEY_K = 39

        /** The arrow keys, and D F J K under the fingers of both hands. */
        val DEFAULT = GlyphKeyBindings(
            listOf(
                listOf(LEFT_ARROW, KEY_D),
                listOf(DOWN_ARROW, KEY_F),
                listOf(UP_ARROW, KEY_J),
                listOf(RIGHT_ARROW, KEY_K),
            ),
        )

        /** Read back from [encode]; anything malformed is the default. */
        fun decode(text: String?): GlyphKeyBindings {
            if (text.isNullOrBlank()) return DEFAULT
            val lanes = text.split(';').map { lane ->
                lane.split(',').map { it.trim().toIntOrNull() ?: return DEFAULT }
            }
            if (lanes.size != GlyphLane.entries.size || lanes.any { it.size != SLOTS }) return DEFAULT
            return GlyphKeyBindings(lanes)
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
