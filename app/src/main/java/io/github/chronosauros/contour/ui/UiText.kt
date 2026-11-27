// A stand-in for Contour's UiText (see ContourAppShims.kt): the two shapes
// its USB files build, kept as data. Nothing in Tryptify shows one.
package io.github.chronosauros.contour.ui

sealed interface UiText {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Core(val raw: List<String>) : UiText

    companion object {
        fun res(id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

fun coreTexts(raw: List<String>): UiText = UiText.Core(raw)
