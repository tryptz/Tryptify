package io.github.chronosauros.contour.core.native

/**
 * What a DAC Settings write may be, decided before any frame exists. [FiioCodec.setSetting] is the only place that builds a settings SET and
 * it asks [check] first, so a refused command cannot be turned into bytes at all (there is no second builder).
 *
 * Refused, with an [IllegalArgumentException] that says why:
 *  - a setting that has no write command, or whose command is not in the connected model's own list (a reply is not capability, and neither
 *    is a write: Melody's list has no screen items, the KA15's no gain);
 *  - USB AUDIO (0x20): switching UAC re-enumerates the DAC, so a read-back cannot close the loop; it stays read-only;
 *  - anything on the never-send list ([ProbePlan.FIIO_FORBIDDEN]: 0x00, 0x19-0x1C, 0x21, 0x30, 0x4F). The K13 R2R has no settings list (its
 *    commands are another protocol), so every setting is refused for it by the first rule.
 * and a value outside the setting's options or range.
 */
object DacWrite {
    /** Commands that are never sent, whatever a list says. */
    val NEVER_SEND: Set<Int> = ProbePlan.FIIO_FORBIDDEN

    /** Settings that are read-only on purpose: USB AUDIO. */
    val READ_ONLY: Set<Int> = setOf(0x20)

    /** The command of [s] on [config], or the reason it may not be written; the value is not looked at. */
    fun checkSetting(config: FiioConfig, s: DacSetting): Int {
        val cmd = requireNotNull(s.writeCommand) { "${s.label} has no write command" }
        require(cmd in 0..255) { "${s.label}: bad command $cmd" }
        require(cmd !in NEVER_SEND) { "0x%02x is on the never-send list".format(cmd) }
        require(cmd !in READ_ONLY) { "${s.label} (0x%02x) is read-only: switching it re-enumerates the DAC".format(cmd) }
        require(config.settings.any { it.id == s.id && it.writeCommand == cmd }) { "${s.label} (0x%02x) is not in the ${config.productName} list".format(cmd) }
        return cmd
    }

    /** The highest value a LEVEL accepts at all (the volume has two tops, by VOLUME STEPS; the caller narrows it by what the DAC is set to). */
    fun hardMax(s: DacSetting): Int = maxOf(s.max, s.maxRule?.maxima?.maxOrNull() ?: s.max)

    /** [v] must be an option of a TOGGLE or CHOICE, inside min..max of a LEVEL, inside -max..max of a BALANCE. */
    fun checkValue(s: DacSetting, v: Int) {
        when (s.kind) {
            SettingKind.TOGGLE, SettingKind.CHOICE -> require(v in s.options.indices) { "${s.label}: $v is not an option (0..${s.options.lastIndex})" }
            SettingKind.LEVEL -> require(v in s.min..hardMax(s)) { "${s.label}: $v is outside ${s.min}..${hardMax(s)}" }
            SettingKind.BALANCE -> require(v in -s.max..s.max) { "${s.label}: $v is outside -${s.max}..${s.max}" }
        }
    }

    /** The command of [s] for [v] on [config]: [checkSetting] and [checkValue] in one. */
    fun check(config: FiioConfig, s: DacSetting, v: Int): Int = checkSetting(config, s).also { checkValue(s, v) }

    /**
     * The data bytes: one byte, the option or the level; the balance is two, (left attenuation, right attenuation) as the decoder reads them:
     * "L n" (value -n) = `00 n`, "R n" (value n) = `n 00`, centre `00 00`.
     */
    fun payload(s: DacSetting, v: Int): List<Int> = when (s.kind) {
        SettingKind.BALANCE -> if (v < 0) listOf(0, -v) else listOf(v, 0)
        else -> listOf(v)
    }
}
