package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.WalkPlay

/**
 * The DAC probe (review builds only): reads everything Contour is allowed to read from whatever DAC is attached and writes it down, so
 * the settings a DAC really has can be found by toggling them in the vendor app between probes and diffing the logs. It never writes.
 * The lists below are the whole allow-list: a command that is not in them cannot be built.
 */
object ProbePlan {
    /** The FiiO active-slot register, read before the first and after the last probe command. */
    const val FIIO_SLOT = 0x16

    /**
     * FiiO frame GETs (`BB 0B seqHi seqLo cmd 00 CRC EE`), in this order: the commands of FiiO Control's own config for the Tiny A, Melody and
     * KA15 (FIIO-CONTROL-APP.md) plus the FWA enum ones. Never 0x00, 0x19 (save), 0x1A, 0x1B, 0x1C, 0x21 (reset to factory), 0x30 (names), 0x4F (clear pairing).
     */
    val FIIO_COMMANDS = listOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12,
        0x1D, 0x1E, 0x20, 0x22, 0x32, 0x33, 0x34,
    )

    /** WalkPlay report 0x4B reads (`80 cmd 00`), from devicePEQ; unproven on the Micro, a no-reply is a valid result. */
    val WALKPLAY_COMMANDS = listOf(0x11, 0x16, 0x19, 0x1B, 0x1D, 0x02)

    val FIIO_FORBIDDEN = setOf(0x00, 0x19, 0x1A, 0x1B, 0x1C, 0x21, 0x30, 0x4F)

    /** Everything a FiiO probe sends, in order: the slot, the commands, the slot again. */
    val FIIO_SEQUENCE: List<Int> = listOf(FIIO_SLOT) + FIIO_COMMANDS + FIIO_SLOT

    /** The GET frame for [cmd] with the host sequence and CRC of the official web app; only a command of the allow-list. */
    fun fiioFrame(codec: FiioCodec, cmd: Int): FiioFrame {
        require(cmd == FIIO_SLOT || cmd in FIIO_COMMANDS) { "Not a probe command: 0x%02x".format(cmd) }
        require(cmd !in FIIO_FORBIDDEN)
        return codec.querySetting(cmd).also { require(it.effect == FiioEffect.READ_ONLY && it.bytes[0] == 0xBB) }
    }

    /** The pause between a FiiO config's probe GETs, or null when the FiiO frame layer is skipped for it (never less than its write gap). */
    fun fiioGapMs(config: FiioConfig): Int? = config.probeGapMs?.let { maxOf(it, config.writeGapMs) }

    /** The WalkPlay read report for [cmd]; only a command of the allow-list, always a READ (0x80). */
    fun walkPlayRead(cmd: Int): ByteArray {
        require(cmd in WALKPLAY_COMMANDS) { "Not a probe command: 0x%02x".format(cmd) }
        return WalkPlay.report(WalkPlay.READ, cmd, WalkPlay.END)
    }

    /** A label safe in a file name and in a log line: letters, digits, dot, dash, underscore. */
    fun safeLabel(label: String): String = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "probe" }
    fun deviceToken(name: String): String = name.trim().replace(Regex("\\s+"), "-")
}

/**
 * The probe's output: one line per request, `PROBE <label> <device> <layer> <cmd> TX <hex>`, then `... RX <hex>`, `... no reply` or
 * `... err <reason>`, and `PROBE <label> done <answered>/<sent> <ms>` at the end. [out] gets every line (logcat and the pull file).
 */
class ProbeReport(label: String, device: String, private val out: (String) -> Unit, private val nowMs: () -> Long = { 0L }) {
    val label = ProbePlan.safeLabel(label)
    private val device = ProbePlan.deviceToken(device)
    private val start = nowMs()
    private class Count { var sent = 0; var answered = 0 }
    private val layers = LinkedHashMap<String, Count>()
    val sent: Int get() = layers.values.sumOf { it.sent }
    val answered: Int get() = layers.values.sumOf { it.answered }
    fun sent(layer: String) = layers[layer]?.sent ?: 0
    fun answered(layer: String) = layers[layer]?.answered ?: 0

    private fun line(rest: String) = out("PROBE $label $rest")
    private fun count(layer: String) = layers.getOrPut(layer) { Count() }

    /** A line that is not a request: what the probe found out or why it stopped. */
    fun note(layer: String, text: String) = line("$device $layer $text")
    fun tx(layer: String, cmd: String, hex: String) { count(layer).sent++; line("$device $layer $cmd TX $hex") }
    fun rx(layer: String, cmd: String, hex: String) { count(layer).answered++; line("$device $layer $cmd RX $hex") }
    fun noReply(layer: String, cmd: String) = line("$device $layer $cmd no reply")
    fun error(layer: String, cmd: String, reason: String) = line("$device $layer $cmd err ${reason.replace('\n', ' ')}")
    /** The whole probe could not run (no DAC, no permission, interface busy): one line, no counts. */
    fun stopped(text: String) = line(text)
    fun summary() = layers.forEach { (layer, c) -> line("summary $layer ${c.answered}/${c.sent}") }
    fun done() { summary(); line("done $answered/$sent ${nowMs() - start}") }
}
