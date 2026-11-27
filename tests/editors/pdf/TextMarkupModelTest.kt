package dev.kern.editors.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMarkupModelTest {

    private fun glyph(left: Float, top: Float, width: Float = 0.05f, height: Float = 0.04f) =
        PageRect(left, top, left + width, top + height)

    /** Two lines of three glyphs: the first at y 0.10, the second at y 0.20. */
    private val twoLines = PageGlyphs.of(
        listOf(
            PdfTextLine("abc", PageRect(0.1f, 0.10f, 0.25f, 0.14f), listOf(glyph(0.10f, 0.10f), glyph(0.15f, 0.10f), glyph(0.20f, 0.10f))),
            PdfTextLine("def", PageRect(0.1f, 0.20f, 0.25f, 0.24f), listOf(glyph(0.10f, 0.20f), glyph(0.15f, 0.20f), glyph(0.20f, 0.20f))),
        ),
    )

    @Test
    fun a_touch_on_a_glyph_is_that_glyph() {
        assertEquals(1, twoLines.hit(PagePoint(0.17f, 0.12f), 1.4f))
        assertEquals(5, twoLines.hit(PagePoint(0.22f, 0.22f), 1.4f))
    }

    @Test
    fun a_touch_beside_the_text_is_the_nearest_glyph_on_the_nearest_line() {
        assertEquals(2, twoLines.hit(PagePoint(0.9f, 0.115f), 1.4f))
        assertEquals(3, twoLines.hit(PagePoint(0.0f, 0.215f), 1.4f))
    }

    @Test
    fun a_touch_just_above_a_line_reaches_that_line_not_the_one_above() {
        assertEquals(4, twoLines.hit(PagePoint(0.17f, 0.18f), 1.4f))
    }

    @Test
    fun a_page_without_glyphs_has_nothing_to_hit() {
        val none = PageGlyphs.of(listOf(PdfTextLine("x", PageRect(0f, 0f, 1f, 1f))))
        assertTrue(none.isEmpty)
        assertNull(none.hit(PagePoint(0.5f, 0.5f), 1f))
    }

    @Test
    fun a_glyph_of_no_size_is_never_hit() {
        val glyphs = PageGlyphs.of(
            listOf(PdfTextLine("ab", PageRect(0.1f, 0.1f, 0.2f, 0.14f), listOf(PageRect(0.1f, 0.1f, 0.1f, 0.1f), glyph(0.12f, 0.10f)))),
        )
        assertEquals(1, glyphs.boxes.size)
        assertEquals(0, glyphs.hit(PagePoint(0.1f, 0.1f), 1f))
    }

    @Test
    fun a_range_on_one_line_is_one_box() {
        val quads = twoLines.quads(0, 2)
        assertEquals(listOf(PageRect(0.10f, 0.10f, 0.25f, 0.14f)), quads)
    }

    @Test
    fun a_range_over_two_lines_is_one_box_for_each_in_reading_order() {
        val quads = twoLines.quads(1, 4)
        assertEquals(2, quads.size)
        assertEquals(0.15f, quads[0].left, 1e-6f)
        assertEquals(0.25f, quads[0].right, 1e-6f)
        assertEquals(0.10f, quads[1].left, 1e-6f)
        assertEquals(0.20f, quads[1].right, 1e-6f)
    }

    @Test
    fun the_ends_of_a_range_may_be_given_either_way_round() {
        assertEquals(twoLines.quads(1, 4), twoLines.quads(4, 1))
    }

    @Test
    fun a_range_that_is_split_on_the_page_marks_only_the_glyphs_it_holds() {
        // Logical order a, b, c, where c is drawn far to the left of the others (right-to-left text in a line).
        val glyphs = PageGlyphs.of(
            listOf(
                PdfTextLine(
                    "abc", PageRect(0.1f, 0.1f, 0.9f, 0.14f),
                    listOf(glyph(0.80f, 0.10f), glyph(0.85f, 0.10f), glyph(0.10f, 0.10f)),
                ),
            ),
        )
        val quads = glyphs.quads(0, 2)
        assertEquals(2, quads.size)
        assertEquals(0.10f, quads[0].left, 1e-6f)
        assertEquals(0.15f, quads[0].right, 1e-6f)
        assertEquals(0.80f, quads[1].left, 1e-6f)
        assertEquals(0.90f, quads[1].right, 1e-6f)
    }

    @Test
    fun a_space_between_words_does_not_cut_a_run() {
        val glyphs = PageGlyphs.of(
            listOf(PdfTextLine("a b", PageRect(0.1f, 0.1f, 0.3f, 0.14f), listOf(glyph(0.10f, 0.10f), glyph(0.17f, 0.10f)))),
        )
        assertEquals(1, glyphs.quads(0, 1).size)
    }

    @Test
    fun moving_an_end_never_passes_the_other() {
        val selection = MarkupSelection(page = 0, anchor = 1, focus = 3)
        assertEquals(1 to 4, selection.withEnd(4).let { it.first to it.last })
        assertEquals(1 to 1, selection.withEnd(0).let { it.first to it.last })
        assertEquals(2 to 3, selection.withStart(2).let { it.first to it.last })
        assertEquals(3 to 3, selection.withStart(5).let { it.first to it.last })
    }

    @Test
    fun a_selection_dragged_backwards_covers_the_same_glyphs() {
        val selection = MarkupSelection(page = 0, anchor = 4, focus = 1)
        assertEquals(1, selection.first)
        assertEquals(4, selection.last)
        assertEquals(twoLines.quads(1, 4), selection.quads(twoLines))
    }

    @Test
    fun thinning_keeps_the_ends_and_drops_points_that_are_too_close() {
        val raw = listOf(PagePoint(0.1f, 0.1f), PagePoint(0.1001f, 0.1f), PagePoint(0.1002f, 0.1f), PagePoint(0.2f, 0.1f))
        val thinned = InkStroke.thin(raw, 1f)
        assertEquals(listOf(PagePoint(0.1f, 0.1f), PagePoint(0.2f, 0.1f)), thinned)
    }

    @Test
    fun a_single_touch_stays_a_single_point() {
        val dot = listOf(PagePoint(0.4f, 0.4f))
        assertEquals(dot, InkStroke.smooth(dot, 1.4f))
    }

    @Test
    fun a_stroke_that_never_moved_ends_where_it_began() {
        val still = listOf(PagePoint(0.4f, 0.4f), PagePoint(0.4f, 0.4f), PagePoint(0.4f, 0.4f))
        assertEquals(listOf(PagePoint(0.4f, 0.4f)), InkStroke.smooth(still, 1.4f))
    }

    @Test
    fun smoothing_keeps_where_the_pen_went_down_and_came_up_and_rounds_the_corner() {
        val corner = listOf(PagePoint(0.1f, 0.1f), PagePoint(0.5f, 0.1f), PagePoint(0.5f, 0.5f))
        val smooth = InkStroke.smooth(corner, 1f)
        assertEquals(corner.first(), smooth.first())
        assertEquals(corner.last(), smooth.last())
        assertTrue(smooth.size > corner.size)
        assertTrue("the sharp corner point is gone", smooth.none { it == PagePoint(0.5f, 0.1f) })
    }
}
