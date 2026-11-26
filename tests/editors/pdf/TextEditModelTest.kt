package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfColour
import dev.kern.pdfbridge.PdfPageFont
import dev.kern.pdfbridge.PdfTextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditModelTest {

    private fun block(id: Int, left: Float, top: Float, right: Float, bottom: Float, editable: Boolean = true) =
        PdfTextBlock(id, PdfBox(left, top, right, bottom), editable, if (editable) null else "NoSize", "text $id", 1)

    @Test
    fun a_tap_inside_a_block_means_that_block() {
        val blocks = listOf(block(0, 0.1f, 0.1f, 0.5f, 0.2f), block(1, 0.1f, 0.5f, 0.5f, 0.6f))
        assertEquals(1, TextEditModel.blockAt(blocks, PagePoint(0.3f, 0.55f))?.id)
    }

    @Test
    fun a_tap_just_outside_a_block_still_means_it_but_a_tap_far_away_means_none() {
        val blocks = listOf(block(0, 0.1f, 0.1f, 0.5f, 0.2f))
        assertEquals(0, TextEditModel.blockAt(blocks, PagePoint(0.1f, 0.2f + TextEditModel.TAP_SLOP / 2))?.id)
        assertNull(TextEditModel.blockAt(blocks, PagePoint(0.1f, 0.4f)))
    }

    @Test
    fun where_blocks_overlap_the_smaller_one_wins() {
        val blocks = listOf(block(0, 0.0f, 0.0f, 1.0f, 1.0f), block(1, 0.2f, 0.2f, 0.4f, 0.3f))
        assertEquals(1, TextEditModel.blockAt(blocks, PagePoint(0.3f, 0.25f))?.id)
        assertEquals(0, TextEditModel.blockAt(blocks, PagePoint(0.8f, 0.8f))?.id)
    }

    @Test
    fun a_block_that_cannot_be_changed_is_still_found_so_its_reason_can_be_said() {
        val blocks = listOf(block(0, 0.1f, 0.1f, 0.5f, 0.2f, editable = false))
        assertEquals(false, TextEditModel.blockAt(blocks, PagePoint(0.2f, 0.15f))?.editable)
    }

    @Test
    fun every_reason_the_engine_gives_has_its_own_sentence() {
        val names = listOf(
            "NoGlyphs", "NoSize", "NotOnPage", "Type3Font", "VerticalWriting", "TaggedFormContent", "ClippingRenderMode",
            "SplitAcrossStreams", "StraddlesStreams", "UnterminatedTextObject", "StateChangedBeforeEnd",
        )
        val said = names.map { TextEditModel.refusal(it) }
        assertTrue("no reason falls through to the unnamed sentence", said.none { it.startsWith("Kern cannot change this text") })
        assertTrue(said.all { it.endsWith(".") })
    }

    @Test
    fun a_reason_the_app_does_not_know_is_passed_on_by_name() {
        assertEquals("Kern cannot change this text (Brand New).", TextEditModel.refusal("Brand New"))
        assertEquals("Kern cannot change this text.", TextEditModel.refusal(null))
    }

    @Test
    fun the_letters_follow_the_height_of_a_line() {
        assertEquals(30f, TextEditModel.letterPx(boxHeightPx = 80f, lines = 2), 0.001f)
        assertEquals(30f, TextEditModel.letterPx(boxHeightPx = 40f, lines = 1), 0.001f)
    }

    @Test
    fun the_letters_are_never_smaller_than_can_be_read() {
        assertEquals(TextEditModel.MIN_LETTER_PX, TextEditModel.letterPx(boxHeightPx = 5f, lines = 1), 0.001f)
    }

    @Test
    fun a_block_with_no_lines_is_sized_as_one_line() {
        assertEquals(30f, TextEditModel.letterPx(boxHeightPx = 40f, lines = 0), 0.001f)
    }

    @Test
    fun the_letter_no_font_has_is_named_from_the_engines_message() {
        assertEquals(
            "No font on this device has the letter U+E000, so Kern cannot put it in this text.",
            TextEditModel.missingLetter("the font has no glyph for U+E000"),
        )
        assertNull(TextEditModel.missingLetter("the file is damaged"))
        assertNull(TextEditModel.missingLetter(null))
    }

    @Test
    fun a_fonts_problem_is_told_with_its_reason() {
        assertEquals(
            "Kern could not read the fonts of this device (no access). Text can be changed only with letters its own font has.",
            TextEditModel.fontsProblemMessage("no access"),
        )
    }

    @Test
    fun the_notes_of_an_edit_become_one_message_with_capital_letters() {
        assertNull(TextEditModel.noteFor(emptyList()))
        assertEquals(
            "Substitute font used for: Arial (sans)\nSubstitute font used for: Times (serif)",
            TextEditModel.noteFor(listOf("substitute font used for: Arial (sans)", "substitute font used for: Times (serif)")),
        )
    }

    @Test
    fun new_text_runs_to_the_right_edge_of_the_page_text() {
        val blocks = listOf(block(0, 0.1f, 0.1f, 0.8f, 0.2f), block(1, 0.1f, 0.3f, 0.6f, 0.4f))
        assertEquals(0.5f, TextEditModel.columnWidth(blocks, 0.3f), 1e-6f)
    }

    @Test
    fun new_text_past_the_page_text_runs_to_a_margin_as_wide_as_the_left_one() {
        val blocks = listOf(block(0, 0.12f, 0.1f, 0.5f, 0.2f))
        assertEquals(0.88f - 0.45f, TextEditModel.columnWidth(blocks, 0.45f), 1e-6f)
    }

    @Test
    fun new_text_on_a_page_with_no_text_keeps_the_empty_page_margin() {
        assertEquals(1f - TextEditModel.EMPTY_PAGE_MARGIN - 0.2f, TextEditModel.columnWidth(emptyList(), 0.2f), 1e-6f)
    }

    @Test
    fun new_text_near_the_right_edge_gets_the_narrowest_box_or_what_is_left_of_the_page() {
        val blocks = listOf(block(0, 0.1f, 0.1f, 0.9f, 0.2f))
        assertEquals(TextEditModel.MIN_NEW_WIDTH, TextEditModel.columnWidth(blocks, 0.85f), 1e-6f)
        assertEquals(0.04f, TextEditModel.columnWidth(blocks, 0.96f), 1e-6f)
    }

    @Test
    fun a_dragged_width_takes_the_width_of_a_block_it_comes_near() {
        val blocks = listOf(block(0, 0.1f, 0.1f, 0.5f, 0.2f), block(1, 0.1f, 0.3f, 0.7f, 0.4f))
        assertEquals(0.4f, TextEditModel.snapWidth(0.405f, 0.2f, blocks), 1e-6f)
        assertEquals(0.6f, TextEditModel.snapWidth(0.595f, 0.2f, blocks), 1e-6f)
        assertEquals(0.5f, TextEditModel.snapWidth(0.5f, 0.2f, blocks), 1e-6f)
    }

    @Test
    fun a_dragged_width_stays_on_the_page_and_does_not_snap_to_a_block_that_would_leave_it() {
        val blocks = listOf(block(0, 0.0f, 0.1f, 0.6f, 0.2f))
        assertEquals(0.5f, TextEditModel.snapWidth(0.6f, 0.5f, blocks), 1e-6f)
        assertEquals(TextEditModel.MIN_NEW_WIDTH, TextEditModel.snapWidth(0.01f, 0.2f, emptyList()), 1e-6f)
    }

    @Test
    fun a_subset_font_is_shown_without_its_tag() {
        assertEquals("Garamond", TextEditModel.fontName("ABCDEF+Garamond"))
        assertEquals("Helvetica", TextEditModel.fontName("Helvetica"))
        assertEquals("abcdef+Odd", TextEditModel.fontName("abcdef+Odd"))
    }

    @Test
    fun sizes_are_shown_whole_or_to_a_tenth() {
        assertEquals("11 pt", TextEditModel.sizeLabel(11.0))
        assertEquals("10.5 pt", TextEditModel.sizeLabel(10.5))
        assertEquals("12 pt", TextEditModel.sizeLabel(11.998))
    }

    @Test
    fun the_font_chip_names_the_followed_font_or_the_default_on_an_empty_page() {
        val font = PdfPageFont("ABCDEF+Arial", true, 11.0, PdfColour(0.0, 0.0, 0.0), listOf(0), 10, true)
        assertEquals("Arial", TextEditModel.fontLabel(font))
        assertEquals(TextEditModel.EMPTY_PAGE_FACE, TextEditModel.fontLabel(null))
    }

    @Test
    fun a_box_is_kept_on_the_page_whichever_way_it_is_moved() {
        assertEquals(0f, TextEditModel.clampLeft(-0.2f, 0.4f), 0f)
        assertEquals(0.3f, TextEditModel.clampLeft(0.3f, 0.4f), 1e-6f)
        assertEquals(0.6f, TextEditModel.clampLeft(0.9f, 0.4f), 1e-6f)
        assertEquals(0f, TextEditModel.clampLeft(0.5f, 1.2f), 0f)
    }

    @Test
    fun a_box_dragged_back_to_where_the_block_stands_sends_nothing() {
        assertEquals(null, TextEditModel.changedOrNull(0.3f, 0.3f))
        assertEquals(null, TextEditModel.changedOrNull(0.3004f, 0.3f))
        assertEquals(0.35f, TextEditModel.changedOrNull(0.35f, 0.3f))
    }

    @Test
    fun a_text_drawn_as_pictures_of_letters_is_said_so_in_plain_words() {
        val said = TextEditModel.refusal("Type3Font")
        assertTrue(said, said.contains("Type 3") && said.contains("pictures of letters"))
    }
}
