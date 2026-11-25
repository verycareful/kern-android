package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfAnnotation
import dev.kern.pdfbridge.PdfAnnotationId
import dev.kern.pdfbridge.PdfAnnotationKind
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfColour
import dev.kern.pdfbridge.PdfPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSelectionModelTest {

    private val id = PdfAnnotationId(12, 0)

    private fun annotation(
        kind: PdfAnnotationKind,
        rect: PdfBox = PdfBox(0.4f, 0.4f, 0.5f, 0.45f),
        quads: List<PdfBox> = emptyList(),
        strokes: List<List<PdfPoint>> = emptyList(),
        id: PdfAnnotationId? = this.id,
        drawnByLibrary: Boolean = true,
        contents: String? = null,
        colour: PdfColour? = null,
        flags: Long = 4,
    ) = PdfAnnotation(id, kind, drawnByLibrary, rect, quads, strokes, contents, colour, flags)

    private val aspect = 1.4f
    private val slop = 0.03f

    @Test
    fun a_touch_on_a_note_icon_or_just_beside_it_is_the_note() {
        val note = annotation(PdfAnnotationKind.Note)
        assertSame(note, AnnotationSelectionModel.hit(listOf(note), PagePoint(0.45f, 0.42f), aspect, slop))
        assertSame(note, AnnotationSelectionModel.hit(listOf(note), PagePoint(0.52f, 0.42f), aspect, slop))
        assertNull(AnnotationSelectionModel.hit(listOf(note), PagePoint(0.6f, 0.42f), aspect, slop))
    }

    @Test
    fun the_reach_beyond_a_box_is_the_same_distance_up_as_across() {
        val box = PdfBox(0.4f, 0.4f, 0.5f, 0.45f)
        // 0.03 page widths is 0.03 / 1.4 of the height.
        assertTrue(AnnotationSelectionModel.contains(box, PagePoint(0.45f, 0.45f + 0.02f), 0.03f, aspect))
        assertFalse(AnnotationSelectionModel.contains(box, PagePoint(0.45f, 0.45f + 0.03f), 0.03f, aspect))
    }

    @Test
    fun a_markup_is_touched_only_on_its_text_not_in_the_gap_between_its_lines() {
        val highlight = annotation(
            PdfAnnotationKind.Highlight,
            rect = PdfBox(0.1f, 0.1f, 0.6f, 0.3f),
            quads = listOf(PdfBox(0.1f, 0.1f, 0.6f, 0.14f), PdfBox(0.1f, 0.26f, 0.4f, 0.30f)),
        )
        assertSame(highlight, AnnotationSelectionModel.hit(listOf(highlight), PagePoint(0.3f, 0.12f), aspect, slop))
        assertNull(AnnotationSelectionModel.hit(listOf(highlight), PagePoint(0.3f, 0.2f), aspect, slop))
    }

    @Test
    fun a_touch_near_an_ink_stroke_is_the_ink_but_a_touch_in_its_empty_box_is_not() {
        val ink = annotation(
            PdfAnnotationKind.Ink,
            rect = PdfBox(0.1f, 0.1f, 0.5f, 0.5f),
            strokes = listOf(listOf(PdfPoint(0.1, 0.1), PdfPoint(0.5, 0.1), PdfPoint(0.5, 0.5))),
        )
        assertSame(ink, AnnotationSelectionModel.hit(listOf(ink), PagePoint(0.3f, 0.11f), aspect, slop))
        assertSame(ink, AnnotationSelectionModel.hit(listOf(ink), PagePoint(0.51f, 0.3f), aspect, slop))
        assertNull(AnnotationSelectionModel.hit(listOf(ink), PagePoint(0.3f, 0.4f), aspect, slop))
    }

    @Test
    fun a_single_point_stroke_is_touched_near_that_point() {
        val dot = annotation(PdfAnnotationKind.Ink, rect = PdfBox(0.2f, 0.2f, 0.21f, 0.21f), strokes = listOf(listOf(PdfPoint(0.2, 0.2))))
        assertNotNull(AnnotationSelectionModel.hit(listOf(dot), PagePoint(0.21f, 0.2f), aspect, slop))
        assertNull(AnnotationSelectionModel.hit(listOf(dot), PagePoint(0.3f, 0.2f), aspect, slop))
    }

    @Test
    fun where_two_annotations_overlap_the_one_drawn_last_is_chosen() {
        val under = annotation(PdfAnnotationKind.Note, rect = PdfBox(0.4f, 0.4f, 0.5f, 0.45f), id = PdfAnnotationId(1, 0))
        val over = annotation(PdfAnnotationKind.Note, rect = PdfBox(0.42f, 0.4f, 0.52f, 0.45f), id = PdfAnnotationId(2, 0))
        assertSame(over, AnnotationSelectionModel.hit(listOf(under, over), PagePoint(0.45f, 0.42f), aspect, slop))
        assertSame(under, AnnotationSelectionModel.hit(listOf(under, over), PagePoint(0.405f, 0.42f), aspect, 0f))
    }

    @Test
    fun a_note_can_be_recoloured_moved_deleted_and_have_its_text_changed() {
        val actions = AnnotationSelectionModel.actionsOf(annotation(PdfAnnotationKind.Note))
        assertTrue(actions.recolour && actions.move && actions.delete && actions.editText)
        assertNull(actions.limit)
    }

    @Test
    fun ink_moves_but_a_markup_does_not_and_neither_has_text_to_edit() {
        val ink = AnnotationSelectionModel.actionsOf(annotation(PdfAnnotationKind.Ink))
        assertTrue(ink.recolour && ink.move && ink.delete)
        assertFalse(ink.editText)
        val highlight = AnnotationSelectionModel.actionsOf(annotation(PdfAnnotationKind.Highlight))
        assertTrue(highlight.recolour && highlight.delete)
        assertFalse(highlight.move || highlight.editText)
    }

    @Test
    fun an_annotation_with_no_name_can_be_read_but_nothing_is_offered_and_the_reason_is_given() {
        val actions = AnnotationSelectionModel.actionsOf(annotation(PdfAnnotationKind.Note, id = null))
        assertFalse(actions.recolour || actions.move || actions.delete || actions.editText)
        assertNotNull(actions.limit)
    }

    @Test
    fun a_kind_the_library_does_not_draw_can_be_deleted_but_not_recoloured_or_moved() {
        val stamp = annotation(PdfAnnotationKind.Other("Stamp"), drawnByLibrary = false)
        val actions = AnnotationSelectionModel.actionsOf(stamp)
        assertTrue(actions.delete)
        assertFalse(actions.recolour || actions.move || actions.editText)
        assertTrue(actions.limit!!.contains("Stamp"))
    }

    @Test
    fun a_link_a_form_field_and_a_popup_are_never_chosen() {
        for (subtype in listOf("Link", "Widget", "Popup")) {
            val other = annotation(PdfAnnotationKind.Other(subtype), drawnByLibrary = false)
            assertFalse(subtype, AnnotationSelectionModel.isSelectable(other))
            assertNull(subtype, AnnotationSelectionModel.hit(listOf(other), PagePoint(0.45f, 0.42f), aspect, slop))
        }
    }

    @Test
    fun a_link_over_a_note_leaves_the_touch_to_the_note_and_a_link_alone_to_the_link() {
        val note = annotation(PdfAnnotationKind.Note)
        val link = annotation(PdfAnnotationKind.Other("Link"), rect = PdfBox(0.3f, 0.3f, 0.7f, 0.6f), drawnByLibrary = false)
        assertSame(note, AnnotationSelectionModel.hit(listOf(note, link), PagePoint(0.45f, 0.42f), aspect, slop))
        assertNull(AnnotationSelectionModel.hit(listOf(link), PagePoint(0.45f, 0.42f), aspect, slop))
    }

    @Test
    fun an_annotation_the_file_hides_is_never_chosen() {
        val hidden = annotation(PdfAnnotationKind.Note, flags = 2)
        val noView = annotation(PdfAnnotationKind.Note, flags = 32)
        val printOnly = annotation(PdfAnnotationKind.Note, flags = 4 or 8 or 16)
        assertFalse(AnnotationSelectionModel.isSelectable(hidden))
        assertFalse(AnnotationSelectionModel.isSelectable(noView))
        assertTrue(AnnotationSelectionModel.isSelectable(printOnly))
    }

    @Test
    fun a_locked_annotation_can_be_read_but_not_recoloured_moved_deleted_or_have_its_text_changed() {
        val actions = AnnotationSelectionModel.actionsOf(annotation(PdfAnnotationKind.Note, flags = 4 or 128))
        assertFalse(actions.recolour || actions.move || actions.delete || actions.editText)
        assertTrue(actions.limit!!.contains("locked"))
    }

    @Test
    fun a_note_with_locked_text_can_still_be_recoloured_moved_and_deleted() {
        val actions = AnnotationSelectionModel.actionsOf(annotation(PdfAnnotationKind.Note, flags = 4 or 512))
        assertTrue(actions.recolour && actions.move && actions.delete)
        assertFalse(actions.editText)
        assertTrue(actions.limit!!.contains("text of this note is locked"))
    }

    @Test
    fun a_move_stops_at_the_edges_of_the_page() {
        val box = PdfBox(0.1f, 0.2f, 0.3f, 0.4f)
        val toTheLeft = AnnotationSelectionModel.clampMove(box, -0.5f, 0f)
        assertEquals(-0.1f, toTheLeft.x, 1e-6f)
        val toTheCorner = AnnotationSelectionModel.clampMove(box, 0.9f, 0.9f)
        assertEquals(0.7f, toTheCorner.x, 1e-6f)
        assertEquals(0.6f, toTheCorner.y, 1e-6f)
        val inside = AnnotationSelectionModel.clampMove(box, 0.05f, -0.05f)
        assertEquals(PagePoint(0.05f, -0.05f), inside)
    }

    @Test
    fun a_moved_box_keeps_its_size() {
        val moved = AnnotationSelectionModel.moved(PdfBox(0.1f, 0.2f, 0.3f, 0.4f), PagePoint(0.1f, -0.1f))
        assertEquals(PdfBox(0.2f, 0.1f, 0.4f, 0.3f), moved)
    }

    @Test
    fun a_shift_of_less_than_a_thousandth_of_the_page_is_no_move() {
        assertFalse(AnnotationSelectionModel.isMove(PagePoint(0.0005f, -0.0005f)))
        assertTrue(AnnotationSelectionModel.isMove(PagePoint(0f, 0.01f)))
    }

    @Test
    fun a_colour_from_the_file_is_matched_to_the_one_offered_or_none() {
        assertEquals(MarkColour.Yellow, AnnotationSelectionModel.offeredColour(PdfColour(1.0, 0.92, 0.0)))
        assertEquals(MarkColour.Black, AnnotationSelectionModel.offeredColour(PdfColour(0.0, 0.0, 0.0)))
        assertNull(AnnotationSelectionModel.offeredColour(PdfColour(0.5, 0.5, 0.5)))
        assertNull(AnnotationSelectionModel.offeredColour(null))
    }

    @Test
    fun an_annotation_is_named_for_what_it_is() {
        assertEquals("Note", AnnotationSelectionModel.nameOf(annotation(PdfAnnotationKind.Note)))
        assertEquals("Drawing", AnnotationSelectionModel.nameOf(annotation(PdfAnnotationKind.Ink)))
        assertEquals("Strikeout", AnnotationSelectionModel.nameOf(annotation(PdfAnnotationKind.StrikeOut)))
        assertEquals("Stamp", AnnotationSelectionModel.nameOf(annotation(PdfAnnotationKind.Other("Stamp"))))
    }
}
