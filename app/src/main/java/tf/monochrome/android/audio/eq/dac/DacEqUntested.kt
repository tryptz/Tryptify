package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.DeviceTarget

/**
 * DACs that Contour does not know and that the DAC EQ page drives all the
 * same, with the commands of a DAC Contour has proven: a sibling on the same
 * chip family and command scheme.
 *
 * This is the app's own list, beside Contour's and never inside it
 * (third_party/contour is not edited). An entry is an inference, and the page
 * treats it as one: it says the DAC is untested, it sends nothing until a
 * read of the DAC has parsed exactly as the sibling's would, and every send
 * is proven by read-back like any other. An entry leaves this list for
 * Contour's own when Contour takes the DAC in.
 *
 * What makes an entry defensible, and what each one here rests on, is in
 * AGENTS.md ("DAC EQ"). Do not add one on a product name alone.
 */
object DacEqUntested {

    /**
     * @property name what the page calls the DAC.
     * @property vendorId the USB vendor id it must have.
     * @property productIds the USB product ids it may have. Empty only while
     *   none is known; the name alone then decides, within [vendorId].
     * @property named what its USB product name must contain, compared
     *   without case, spaces or punctuation ("TP35 Pro" finds "EPZ-TP35 PRO").
     *   A device with no product name matches nothing.
     * @property driveAs the proven DAC whose commands it is given.
     * @property gainDb its own gain range where that is narrower than the
     *   sibling's; null = the sibling's.
     * @property resetsDacSettings a send may put the DAC's own settings (its
     *   filter, its gain mode) back to their defaults: the page says so.
     */
    class Entry(
        val name: String,
        val vendorId: Int,
        val productIds: Set<Int>,
        val named: String,
        val driveAs: DeviceTarget,
        val gainDb: ClosedFloatingPointRange<Double>? = null,
        val resetsDacSettings: Boolean = false,
    ) {
        fun matches(vendorId: Int, productId: Int, productName: String?): Boolean {
            if (vendorId != this.vendorId) return false
            if (productIds.isNotEmpty() && productId !in productIds) return false
            val name = productName?.let(::plain).orEmpty()
            return name.isNotEmpty() && plain(named) in name
        }

        /** The limits the page holds an EQ to: the sibling's, under this DAC's name and inside its own gain range. */
        fun caps(): DeviceCapabilities = driveAs.caps.let { theirs ->
            theirs.copy(
                name = name,
                gainMinDb = maxOf(theirs.gainMinDb, gainDb?.start ?: theirs.gainMinDb),
                gainMaxDb = minOf(theirs.gainMaxDb, gainDb?.endInclusive ?: theirs.gainMaxDb),
            )
        }
    }

    /**
     * The EPZ TP35 Pro (USB 3302:43E6, "TP35 Pro"), as the Tanchjim Space II.
     *
     * What this rests on:
     * - devicePEQ, whose protocol work Contour ports, lists 3302:43E6 as
     *   "TP35 Pro" in WalkPlay's "SchemeNo16" group (ten bands, +-10 dB,
     *   peaks and both shelves), the Space II's scheme, and says of a unit it
     *   measured that "its frequency sweep passed".
     * - devicePEQ's recording of a real TP35 Pro (firmware 1.5, 2026-05-03):
     *   Contour's Space II code reads its replies, and the frames it would
     *   send are the frames that unit was sent, byte for byte but for one
     *   flag in the last (`DacEqTp35ProCaptureTest`, which says which).
     * - The Space II, and not the EPZ TP65 next to it: the TP65 reads its
     *   preamp with a request no other WalkPlay recording contains, this
     *   one's included, and is never sent a preamp or a save.
     *
     * What it does not rest on: anyone having sent an EQ to a TP35 Pro with
     * this code. Contour's maintainer has said the TP35 Pro is planned
     * (its issue 6, 2026-11-25); when it is in Contour, this entry goes.
     *
     * [resetsDacSettings]: the Space II's send has two frames (`01 05`,
     * `01 17`) that devicePEQ stopped sending on 2026-11-11, after they put a
     * sibling's DAC filter, work mode and gain mode back to their defaults on
     * every send. The recorded TP35 Pro was sent both.
     *
     * The plain EPZ TP35 is another product and, by its maker's own
     * comparison, has no EQ of its own: it is not here.
     */
    val EPZ_TP35_PRO = Entry(
        name = "EPZ TP35 Pro",
        vendorId = 0x3302,
        productIds = setOf(0x43E6),
        named = "TP35 Pro",
        driveAs = DeviceTarget.SPACE_2,
        gainDb = -10.0..10.0,
        resetsDacSettings = true,
    )

    val all: List<Entry> = listOf(EPZ_TP35_PRO)

    /** The entry for a device Contour itself does not know; null for one it does, and for any other. */
    fun find(vendorId: Int, productId: Int, productName: String?): Entry? =
        if (DeviceTarget.findDevice(vendorId, productId, productName) != null) null
        else all.firstOrNull { it.matches(vendorId, productId, productName) }

    /** Contour's DAC for a device, or the proven sibling an untested one is driven as. */
    fun target(vendorId: Int, productId: Int, productName: String?): DeviceTarget? =
        DeviceTarget.findDevice(vendorId, productId, productName)
            ?: find(vendorId, productId, productName)?.driveAs

    private fun plain(text: String) = text.filter { it.isLetterOrDigit() }.uppercase()
}
