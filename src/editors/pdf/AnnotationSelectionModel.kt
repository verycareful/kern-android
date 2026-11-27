package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfAnnotation
import dev.kern.pdfbridge.PdfAnnotationKind
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfColour
import dev.kern.pdfbridge.PdfPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** An annotation chosen by a tap: the page it is on (0-based) and what the engine read of it. */
data class SelectedAnnotation(val page: Int, val annotation: PdfAnnotation)

/** What can be done to a selected annotation, and why something cannot. */
data class AnnotationActions(
    val recolour: Boolean,
    val move: Boolean,
    val delete: Boolean,
    val editText: Boolean,
    /** Said to the user when [recolour], [move] or [delete] is out of reach; null when nothing is. */
    val limit: String?,
)

/**
 * Which annotation a tap means, and what may be done to it. Everything here is measured in
 * fractions of the page, y down, as the engine reports them.
 */
object AnnotationSelectionModel {

    /** Subtypes that are not something a reader marked up: a link is followed, a form field is not edited here, a popup is only a window for another annotation. */
    private val NOT_SELECTABLE = setOf("Link", "Widget", "Popup")

    /** Flag values of an annotation (PDF 32000-1, Table 165, bit n is value 2 to the power n minus 1). */
    private const val FLAG_HIDDEN = 1L shl 1
    private const val FLAG_NO_VIEW = 1L shl 5
    private const val FLAG_LOCKED = 1L shl 7
    private const val FLAG_LOCKED_CONTENTS = 1L shl 9

    /** Whether a touch on [annotation] means it: not a link, form field or popup, and not one the file hides. */
    fun isSelectable(annotation: PdfAnnotation): Boolean {
        val subtype = (annotation.kind as? PdfAnnotationKind.Other)?.subtype
        return subtype !in NOT_SELECTABLE && annotation.flags and (FLAG_HIDDEN or FLAG_NO_VIEW) == 0L
    }

    /**
     * The annotation a touch at [point] means, or null. [annotations] are in the order the page draws
     * them, so the last that is touched is the one on top. [slop] is how far, in page widths, a touch
     * may miss a thin thing (a note's icon, an ink stroke) and still mean it; [pageAspect] is the
     * page's height over its width, which makes the two axes comparable.
     */
    fun hit(annotations: List<PdfAnnotation>, point: PagePoint, pageAspect: Float, slop: Float): PdfAnnotation? =
        annotations.asReversed().firstOrNull { isSelectable(it) && touches(it, point, pageAspect, slop) }

    private fun touches(a: PdfAnnotation, point: PagePoint, aspect: Float, slop: Float): Boolean = when (a.kind) {
        PdfAnnotationKind.Highlight, PdfAnnotationKind.Underline, PdfAnnotationKind.StrikeOut ->
            if (a.quads.isEmpty()) contains(a.rect, point, 0f, aspect) else a.quads.any { contains(it, point, 0f, aspect) }
        PdfAnnotationKind.Ink ->
            if (a.strokes.isEmpty()) contains(a.rect, point, slop, aspect)
            else a.strokes.any { distanceToStroke(it, point, aspect) <= slop }
        PdfAnnotationKind.Note, is PdfAnnotationKind.Other -> contains(a.rect, point, slop, aspect)
    }

    /** Whether [point] is in [box] grown by [margin] page widths on every side. */
    fun contains(box: PdfBox, point: PagePoint, margin: Float, pageAspect: Float): Boolean {
        val marginY = margin / pageAspect
        return point.x in (box.left - margin)..(box.right + margin) &&
            point.y in (box.top - marginY)..(box.bottom + marginY)
    }

    private fun distanceToStroke(stroke: List<PdfPoint>, point: PagePoint, aspect: Float): Float {
        if (stroke.isEmpty()) return Float.MAX_VALUE
        if (stroke.size == 1) return distance(stroke[0], stroke[0], point, aspect)
        return stroke.zipWithNext().minOf { (a, b) -> distance(a, b, point, aspect) }
    }

    /** The distance, in page widths, from [p] to the segment [a] to [b]. */
    private fun distance(a: PdfPoint, b: PdfPoint, p: PagePoint, aspect: Float): Float {
        val ax = a.x.toFloat()
        val ay = a.y.toFloat() * aspect
        val bx = b.x.toFloat()
        val by = b.y.toFloat() * aspect
        val px = p.x
        val py = p.y * aspect
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    /** What may be done to [annotation]. A kind the engine does not draw keeps the picture it came with. */
    fun actionsOf(annotation: PdfAnnotation): AnnotationActions {
        val named = annotation.id != null
        val locked = annotation.flags and FLAG_LOCKED != 0L
        val textLocked = annotation.flags and FLAG_LOCKED_CONTENTS != 0L
        val note = annotation.kind == PdfAnnotationKind.Note
        val marked = annotation.kind in MARKUP_KINDS
        val editable = named && !locked && annotation.drawnByLibrary
        return AnnotationActions(
            recolour = editable,
            move = editable && (note || annotation.kind == PdfAnnotationKind.Ink),
            delete = named && !locked,
            editText = named && !locked && !textLocked && (note || marked),
            limit = when {
                !named -> "Kern cannot change this annotation: the file stores it in a form Kern cannot address."
                locked -> "This annotation is locked in the file, so Kern cannot change it."
                !annotation.drawnByLibrary -> "This is a ${nameOf(annotation)} annotation. Kern can delete it but cannot recolour or move it."
                textLocked && (note || marked) -> "The text of this ${nameOf(annotation).lowercase()} is locked in the file."
                else -> null
            },
        )
    }

    /** The kinds that mark text: each can carry a note, the text of the annotation. */
    private val MARKUP_KINDS = setOf(PdfAnnotationKind.Highlight, PdfAnnotationKind.Underline, PdfAnnotationKind.StrikeOut)

    /** What the chip that changes the text of [annotation] says: a note's text is edited, a mark has a note added or changed. */
    fun noteChipLabel(annotation: PdfAnnotation): String = when {
        annotation.kind == PdfAnnotationKind.Note -> "Edit text"
        annotation.contents.isNullOrBlank() -> "Add note"
        else -> "Edit note"
    }

    /** What the user calls [annotation]. */
    fun nameOf(annotation: PdfAnnotation): String = when (val kind = annotation.kind) {
        PdfAnnotationKind.Highlight -> "Highlight"
        PdfAnnotationKind.Underline -> "Underline"
        PdfAnnotationKind.StrikeOut -> "Strikeout"
        PdfAnnotationKind.Ink -> "Drawing"
        PdfAnnotationKind.Note -> "Note"
        is PdfAnnotationKind.Other -> kind.subtype
    }

    /**
     * How far, as fractions of the page, [box] may be moved when the finger moved [dx] and [dy]: the
     * box stays on the page.
     */
    fun clampMove(box: PdfBox, dx: Float, dy: Float): PagePoint = PagePoint(
        dx.coerceIn(-box.left, max(0f, 1f - box.right)),
        dy.coerceIn(-box.top, max(0f, 1f - box.bottom)),
    )

    /** The box a [move] of [box] by [by] leaves. */
    fun moved(box: PdfBox, by: PagePoint): PdfBox =
        PdfBox(box.left + by.x, box.top + by.y, box.right + by.x, box.bottom + by.y)

    /** A move smaller than this, in page widths, is a touch that did not move. */
    private const val MIN_MOVE = 0.001f

    fun isMove(by: PagePoint): Boolean = max(abs(by.x), abs(by.y)) >= MIN_MOVE

    /** The offered colour [colour] is, or null when the file's colour is none of them. */
    fun offeredColour(colour: PdfColour?): MarkColour? {
        if (colour == null) return null
        return MarkColour.entries.firstOrNull {
            abs(it.red - colour.red) <= COLOUR_TOLERANCE && abs(it.green - colour.green) <= COLOUR_TOLERANCE &&
                abs(it.blue - colour.blue) <= COLOUR_TOLERANCE
        }
    }

    private const val COLOUR_TOLERANCE = 0.02
}
