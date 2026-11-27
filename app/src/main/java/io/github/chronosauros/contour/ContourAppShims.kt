// Stand-ins for the three things Contour's USB files use from Contour's own
// app, so that those files (third_party/contour/android/app, Apache-2.0)
// compile here without a change. Only DacSnapshot.refusalText reaches them,
// and DAC EQ does not call it: it shows the DAC's own issues instead.
@file:Suppress("ClassName", "ConstPropertyName")

package io.github.chronosauros.contour

/** Contour's generated resources: the one string id its USB files name. */
internal object R {
    object string {
        const val dac_import_not_exact = 0
    }
}
