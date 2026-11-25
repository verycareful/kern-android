package dev.kern.editors.pdf

import kotlin.math.max
import kotlin.math.min

/**
 * The glyphs of one page in reading order, for choosing text to mark. A glyph is addressed by
 * its position in [boxes]: the lines in reading order, each line's glyphs in logical order (which
 * is not left to right in right-to-left or mixed text). Glyphs of no size are not in the list,
 * because no touch can land on them.
 */
class PageGlyphs private constructor(
    val boxes: List<PageRect>,
    private val lineOf: IntArray,
) {
    val isEmpty: Boolean get() = boxes.isEmpty()

    /**
     * The glyph a touch at [point] means. A touch on a glyph is that glyph. Otherwise the touch
     * means the glyph nearest to it on the nearest line, so a finger a little above or beside the
     * text still reaches it. [pageAspect] is the page's height over its width, which makes the two
     * axes comparable.
     */
    fun hit(point: PagePoint, pageAspect: Float): Int? {
        if (boxes.isEmpty()) return null
        boxes.indices.firstOrNull { contains(boxes[it], point) }?.let { return it }
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in boxes.indices) {
            val d = distance(boxes[i], point, pageAspect)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * The boxes that mark the glyphs from [first] to [last], whichever is first in reading order:
     * one box for each run of neighbouring glyphs on a line. A run is cut where the gap between two
     * glyphs is wider than a glyph is high, which is how a range that is split on screen (a
     * right-to-left phrase inside left-to-right text) marks only the glyphs it holds.
     */
    fun quads(first: Int, last: Int): List<PageRect> {
        if (boxes.isEmpty()) return emptyList()
        val from = min(first, last).coerceIn(0, boxes.size - 1)
        val to = max(first, last).coerceIn(0, boxes.size - 1)
        val quads = ArrayList<PageRect>()
        var start = from
        while (start <= to) {
            var end = start
            while (end < to && lineOf[end + 1] == lineOf[start]) end++
            quads += runQuads(start, end)
            start = end + 1
        }
        return quads
    }

    /** The runs of [start]..[end], glyphs of one line, as boxes. */
    private fun runQuads(start: Int, end: Int): List<PageRect> {
        val sorted = (start..end).map { boxes[it] }.sortedBy { it.left }
        val result = ArrayList<PageRect>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            val gap = next.left - current.right
            if (gap <= RUN_GAP_FACTOR * (current.bottom - current.top)) {
                current = PageRect(
                    min(current.left, next.left), min(current.top, next.top),
                    max(current.right, next.right), max(current.bottom, next.bottom),
                )
            } else {
                result += current
                current = next
            }
        }
        result += current
        return result
    }

    private fun contains(box: PageRect, p: PagePoint) = p.x in box.left..box.right && p.y in box.top..box.bottom

    /** The distance from [p] to [box], with y scaled by [aspect] so that a unit is the same length on both axes. */
    private fun distance(box: PageRect, p: PagePoint, aspect: Float): Float {
        val dx = max(max(box.left - p.x, p.x - box.right), 0f)
        val dy = max(max(box.top - p.y, p.y - box.bottom), 0f) * aspect
        // The vertical distance counts for more: the finger is then on the line the glyph is on.
        return dx + dy * LINE_WEIGHT
    }

    companion object {
        /** A gap wider than this many glyph heights ends a run. */
        private const val RUN_GAP_FACTOR = 1.5f

        /** How much farther a glyph on another line is than one on the touched line, for the same distance. */
        private const val LINE_WEIGHT = 3f

        fun of(lines: List<PdfTextLine>): PageGlyphs {
            val boxes = ArrayList<PageRect>()
            val lineOf = ArrayList<Int>()
            lines.forEachIndexed { index, line ->
                for (glyph in line.glyphs) {
                    if (glyph.right > glyph.left && glyph.bottom > glyph.top) {
                        boxes += glyph
                        lineOf += index
                    }
                }
            }
            return PageGlyphs(boxes, lineOf.toIntArray())
        }
    }
}

/**
 * The text a user chose to mark: the glyphs from [anchor] to [focus] of [page]. The two are
 * kept as the user set them: [anchor] stays put while a handle moves [focus], so the selection can
 * be turned round.
 */
data class MarkupSelection(val page: Int, val anchor: Int, val focus: Int) {
    val first: Int get() = min(anchor, focus)
    val last: Int get() = max(anchor, focus)

    fun quads(glyphs: PageGlyphs): List<PageRect> = glyphs.quads(first, last)

    /** The same selection with its last glyph moved to [glyph], which is never before the first. */
    fun withEnd(glyph: Int): MarkupSelection = copy(anchor = first, focus = max(glyph, first))

    /** The same selection with its first glyph moved to [glyph], which is never after the last. */
    fun withStart(glyph: Int): MarkupSelection = copy(anchor = min(glyph, last), focus = last)
}

/** The distance in page fractions that two points of a stroke must be apart to be both kept. */
private const val MIN_POINT_GAP = 0.002

/**
 * Freehand strokes as the pen drew them arrive as raw touch points: many, uneven, with jitter.
 * [smooth] thins them and rounds the corners, keeping the first and last point where the pen
 * went down and came up.
 */
object InkStroke {

    /** Chaikin passes: each doubles the points and cuts the corners by a quarter. */
    private const val SMOOTHING_PASSES = 2
    private const val CORNER_CUT = 0.25

    fun smooth(raw: List<PagePoint>, pageAspect: Float): List<PagePoint> {
        val thinned = thin(raw, pageAspect)
        if (thinned.size < 3) return thinned
        var points = thinned
        repeat(SMOOTHING_PASSES) { points = chaikin(points) }
        return points
    }

    /** Drops each point that is closer than [MIN_POINT_GAP] to the one kept before it; the last point always stays. */
    internal fun thin(raw: List<PagePoint>, pageAspect: Float): List<PagePoint> {
        if (raw.size < 2) return raw
        val kept = ArrayList<PagePoint>()
        kept += raw.first()
        for (i in 1 until raw.size - 1) {
            if (gap(kept.last(), raw[i], pageAspect) >= MIN_POINT_GAP) kept += raw[i]
        }
        val end = raw.last()
        if (gap(kept.last(), end, pageAspect) > 0.0) kept += end
        return kept
    }

    private fun gap(a: PagePoint, b: PagePoint, aspect: Float): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble() * aspect
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun chaikin(points: List<PagePoint>): List<PagePoint> {
        val out = ArrayList<PagePoint>(points.size * 2)
        out += points.first()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            out += PagePoint(
                (a.x + (b.x - a.x) * CORNER_CUT).toFloat(),
                (a.y + (b.y - a.y) * CORNER_CUT).toFloat(),
            )
            out += PagePoint(
                (a.x + (b.x - a.x) * (1 - CORNER_CUT)).toFloat(),
                (a.y + (b.y - a.y) * (1 - CORNER_CUT)).toFloat(),
            )
        }
        out += points.last()
        return out
    }
}

/** The pen's width, in PDF points (1/72 inch). */
enum class InkWidth(val label: String, val points: Double) {
    Thin("Thin", 1.5),
    Medium("Medium", 3.0),
    Thick("Thick", 6.0),
}

/** The colours offered for markup and ink, as red, green, blue from 0 to 1. */
enum class MarkColour(val label: String, val red: Double, val green: Double, val blue: Double) {
    Yellow("Yellow", 1.0, 0.92, 0.0),
    Green("Green", 0.35, 0.85, 0.35),
    Blue("Blue", 0.3, 0.6, 1.0),
    Pink("Pink", 1.0, 0.45, 0.7),
    Red("Red", 0.9, 0.15, 0.15),
    Black("Black", 0.0, 0.0, 0.0),
}
