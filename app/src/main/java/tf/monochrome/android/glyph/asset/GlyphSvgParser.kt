// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The glyph pack's SVG subset, parsed to [GlyphVector].
 *
 * Deliberately narrow. The pack is generated, so its vocabulary is known and
 * fixed — `svg`, `g`, `rect`, `path`, `circle`, plus a `title` that is metadata
 * — and a parser that accepts exactly that is far easier to trust than a
 * general one. Anything outside the subset raises [ParseException] at load time
 * rather than drawing something wrong at 165 Hz.
 *
 * DOM rather than `XmlPullParser` on purpose: `javax.xml.parsers` exists on both
 * Android and the JVM, so the same code path that runs on a phone is the one
 * the tests exercise. The files are a few kilobytes each and are parsed once
 * per process, so the DOM's overhead is irrelevant.
 */
object GlyphSvgParser {

    class ParseException(message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    // Reused across a whole prewarm pass. DocumentBuilder is not thread-safe,
    // so the factory is shared and a builder is made per parse.
    private val documentBuilderFactory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // The pack has no external references; refusing them keeps a
            // malformed or hostile file from reaching the network or the disk.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }

    fun parse(input: InputStream): GlyphVector {
        val document = try {
            documentBuilderFactory.newDocumentBuilder().parse(input)
        } catch (failure: Exception) {
            throw ParseException("could not read SVG: ${failure.message}", failure)
        }
        val root = document.documentElement
            ?: throw ParseException("SVG has no root element")
        if (root.tagName != "svg") throw ParseException("root is <${root.tagName}>, not <svg>")

        val viewBox = root.getAttribute("viewBox").trim()
        if (viewBox.isEmpty()) throw ParseException("SVG has no viewBox")
        val bounds = viewBox.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (bounds.size != 4) throw ParseException("malformed viewBox '$viewBox'")
        // A non-zero viewBox origin would need every coordinate shifted; the
        // pack never emits one, so it is rejected rather than half-supported.
        if (bounds[0] != 0f || bounds[1] != 0f) {
            throw ParseException("viewBox origin must be 0 0, was '$viewBox'")
        }

        val shapes = ArrayList<GlyphShape>()
        collect(root, Inherited(), shapes)
        return GlyphVector(
            viewportWidth = bounds[2],
            viewportHeight = bounds[3],
            shapes = shapes,
        )
    }

    /**
     * Presentation attributes a `<g>` passes to its children.
     *
     * Only what the pack actually inherits. Group opacity multiplies rather
     * than replaces, so a half-transparent group holding a half-transparent
     * rect draws at a quarter — which is what the preview sheets show.
     */
    private data class Inherited(
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: Float? = null,
        val strokeLineCap: String? = null,
        val strokeLineJoin: String? = null,
        val strokeDashArray: String? = null,
        val fillOpacity: Float = 1f,
        val opacity: Float = 1f,
        val transform: GlyphTransform? = null,
    )

    private fun collect(parent: Element, inherited: Inherited, out: MutableList<GlyphShape>) {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val merged = inherit(element, inherited)

            when (element.tagName) {
                "g" -> collect(element, merged, out)
                "rect" -> out += rect(element, merged)
                "circle" -> out += circle(element, merged)
                "path" -> out += path(element, merged)
                // Accessibility metadata, not artwork.
                "title", "desc", "metadata" -> Unit
                else -> throw ParseException("unsupported element <${element.tagName}>")
            }
        }
    }

    private fun inherit(element: Element, inherited: Inherited): Inherited = Inherited(
        fill = element.attributeOrNull("fill") ?: inherited.fill,
        stroke = element.attributeOrNull("stroke") ?: inherited.stroke,
        strokeWidth = element.floatOrNull("stroke-width") ?: inherited.strokeWidth,
        strokeLineCap = element.attributeOrNull("stroke-linecap") ?: inherited.strokeLineCap,
        strokeLineJoin = element.attributeOrNull("stroke-linejoin") ?: inherited.strokeLineJoin,
        strokeDashArray = element.attributeOrNull("stroke-dasharray") ?: inherited.strokeDashArray,
        fillOpacity = (element.floatOrNull("fill-opacity") ?: 1f) * inherited.fillOpacity,
        opacity = (element.floatOrNull("opacity") ?: 1f) * inherited.opacity,
        // A transform on a group and one on its child would need composition;
        // the pack never nests them, so the inner one wins and the case is
        // documented rather than silently mis-drawn.
        transform = parseTransform(element.attributeOrNull("transform")) ?: inherited.transform,
    )

    private fun rect(element: Element, style: Inherited): GlyphShape.Rect {
        val width = element.floatOrNull("width")
            ?: throw ParseException("<rect> has no width")
        val height = element.floatOrNull("height")
            ?: throw ParseException("<rect> has no height")
        return GlyphShape.Rect(
            x = element.floatOrNull("x") ?: 0f,
            y = element.floatOrNull("y") ?: 0f,
            width = width,
            height = height,
            cornerRadius = element.floatOrNull("rx") ?: element.floatOrNull("ry") ?: 0f,
            fill = paint(style.fill, style.fillOpacity),
            stroke = stroke(style),
            opacity = style.opacity,
            transform = style.transform,
        )
    }

    private fun circle(element: Element, style: Inherited): GlyphShape.Circle {
        val radius = element.floatOrNull("r") ?: throw ParseException("<circle> has no r")
        return GlyphShape.Circle(
            centerX = element.floatOrNull("cx") ?: 0f,
            centerY = element.floatOrNull("cy") ?: 0f,
            radius = radius,
            fill = paint(style.fill, style.fillOpacity),
            stroke = stroke(style),
            opacity = style.opacity,
            transform = style.transform,
        )
    }

    private fun path(element: Element, style: Inherited): GlyphShape.PathShape {
        val data = element.attributeOrNull("d") ?: throw ParseException("<path> has no d")
        return GlyphShape.PathShape(
            commands = GlyphPathParser.parse(data),
            fill = paint(style.fill, style.fillOpacity),
            stroke = stroke(style),
            opacity = style.opacity,
            transform = style.transform,
        )
    }

    private fun stroke(style: Inherited): GlyphStroke? {
        val paint = paint(style.stroke, 1f) ?: return null
        val width = style.strokeWidth ?: 1f
        if (width <= 0f) return null
        return GlyphStroke(
            paint = paint,
            width = width,
            cap = when (style.strokeLineCap) {
                "round" -> GlyphStroke.Cap.ROUND
                "square" -> GlyphStroke.Cap.SQUARE
                else -> GlyphStroke.Cap.BUTT
            },
            join = when (style.strokeLineJoin) {
                "round" -> GlyphStroke.Join.ROUND
                "bevel" -> GlyphStroke.Join.BEVEL
                else -> GlyphStroke.Join.MITER
            },
            dashIntervals = style.strokeDashArray
                ?.split(Regex("[\\s,]+"))
                ?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.isNotEmpty() }
                .orEmpty(),
        )
    }

    /**
     * `fill="none"` and an absent fill are different: absent means black by
     * SVG's own default. The pack always states its fills, so an absent one is
     * treated as none — a generated file that forgot a fill should come out
     * invisible rather than as an unexplained black box over the artwork.
     */
    private fun paint(value: String?, alpha: Float): GlyphPaint? = when {
        value == null || value == "none" || value == "transparent" -> null
        value == "currentColor" -> GlyphPaint.CurrentColor(alpha)
        value.startsWith("#") -> GlyphPaint.Solid(parseHexColor(value), alpha)
        else -> throw ParseException("unsupported paint '$value'")
    }

    private fun parseHexColor(value: String): Int {
        val digits = value.removePrefix("#")
        val expanded = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            8 -> digits
            else -> throw ParseException("unsupported colour '$value'")
        }
        val parsed = expanded.toLongOrNull(16)
            ?: throw ParseException("unsupported colour '$value'")
        return if (expanded.length == 8) parsed.toInt() else (0xFF000000L or parsed).toInt()
    }

    private fun parseTransform(value: String?): GlyphTransform? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open <= 0 || close != text.lastIndex) {
            throw ParseException("malformed transform '$text'")
        }
        val name = text.substring(0, open).trim()
        val arguments = text.substring(open + 1, close)
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }
            .map { it.toFloatOrNull() ?: throw ParseException("malformed transform '$text'") }

        return when (name) {
            "translate" -> when (arguments.size) {
                1 -> GlyphTransform.Translate(arguments[0], 0f)
                2 -> GlyphTransform.Translate(arguments[0], arguments[1])
                else -> throw ParseException("translate takes one or two numbers: '$text'")
            }
            "rotate" -> when (arguments.size) {
                1 -> GlyphTransform.Rotate(arguments[0], 0f, 0f)
                3 -> GlyphTransform.Rotate(arguments[0], arguments[1], arguments[2])
                else -> throw ParseException("rotate takes one or three numbers: '$text'")
            }
            else -> throw ParseException("unsupported transform '$name'")
        }
    }

    private fun Element.attributeOrNull(name: String): String? =
        getAttribute(name).takeIf { it.isNotEmpty() }

    private fun Element.floatOrNull(name: String): Float? =
        attributeOrNull(name)?.let { raw ->
            raw.toFloatOrNull() ?: throw ParseException("malformed $name='$raw'")
        }
}
