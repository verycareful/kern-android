package dev.kern.editors.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What find says when some pages of a PDF could not be searched. */
class UnreadPagesMessageTest {

    @Test
    fun one_page_is_named_in_the_singular() {
        val message = PdfEditorViewModel.unreadPagesMessage(1)
        assertEquals("Could not read the text of 1 page, so it was not searched", message.text)
        assertTrue(message.isError)
    }

    @Test
    fun several_pages_are_counted() {
        assertEquals(
            "Could not read the text of 4 pages, so they were not searched",
            PdfEditorViewModel.unreadPagesMessage(4).text,
        )
    }
}
