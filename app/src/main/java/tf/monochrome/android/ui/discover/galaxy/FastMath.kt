package tf.monochrome.android.ui.discover.galaxy

import kotlin.math.floor

/**
 * sin, to within 2e-4, without a call into libm: range-reduced to a quarter
 * turn and a seventh-order polynomial. For the motion that runs through
 * thousands of points a frame — the links' swirl and dust — where the
 * difference is a hundredth of a pixel and the call was most of the cost.
 */
internal fun fastSin(x: Float): Float {
    var a = x - TWO_PI * floor((x + PI_F) / TWO_PI)
    if (a > HALF_PI) a = PI_F - a else if (a < -HALF_PI) a = -PI_F - a
    val a2 = a * a
    return a * (1f + a2 * (-0.16666667f + a2 * (0.008333333f + a2 * -0.00019841270f)))
}

private const val PI_F = 3.1415927f
private const val TWO_PI = 6.2831855f
private const val HALF_PI = 1.5707964f
