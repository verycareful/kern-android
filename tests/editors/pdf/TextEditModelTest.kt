package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfTextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditModelTest {

    private fun block(id: Int, left: Float, top: Float, right: Float, bottom: Float, editable: Boolean = true) =
        PdfTextBlock(id, PdfBox(left, top, right, bottom), editable, if (editable) null else "NoSize", "text $id")

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
}
