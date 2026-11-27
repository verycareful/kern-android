package dev.kern.editors.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The single-range check the extract dialog and the ViewModel share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfRangeSpecTest {

    private fun error(spec: String) = PdfEditorViewModel.rangeSpecError(spec)

    @Test
    fun a_page_number_or_a_range_is_accepted() {
        assertNull(error("4"))
        assertNull(error("2-7"))
        assertNull(error("5-5"))
    }

    @Test
    fun whitespace_is_ignored() {
        assertNull(error(" 2 - 7 "))
    }

    @Test
    fun nothing_is_refused_with_an_example() {
        assertEquals("Enter a page range, e.g. 1-3.", error(""))
        assertEquals("Enter a page range, e.g. 1-3.", error("  "))
    }

    @Test
    fun a_list_is_refused_rather_than_cut_to_its_first_range() {
        assertNotNull(error("1-3,5"))
        assertEquals(error("1,2"), error("1-3,5"))
    }

    @Test
    fun page_zero_is_refused() {
        assertEquals("Page numbers start at 1.", error("0"))
        assertEquals("Page numbers start at 1.", error("0-3"))
    }

    @Test
    fun a_reversed_range_is_refused() {
        assertEquals("The last page of the range cannot come before the first.", error("5-3"))
    }

    @Test
    fun text_that_is_not_a_range_is_refused() {
        val shape = "Use one range like 1-3, or a single page number like 4."
        assertEquals(shape, error("a-b"))
        assertEquals(shape, error("1-"))
        assertEquals(shape, error("-3"))
        assertEquals(shape, error("1-2-3"))
        assertEquals(shape, error("1234567"))
    }
}
