package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganisePagesModelTest {

    @Test
    fun moving_a_page_forward_shifts_the_ones_it_passes_back() {
        assertEquals(listOf(1, 2, 0, 3), PageOrder.move(4, from = 0, to = 2))
    }

    @Test
    fun moving_a_page_back_shifts_the_ones_it_passes_forward() {
        assertEquals(listOf(0, 3, 1, 2), PageOrder.move(4, from = 3, to = 1))
    }

    @Test
    fun moving_a_page_onto_itself_keeps_the_order() {
        assertEquals(listOf(0, 1, 2), PageOrder.move(3, from = 1, to = 1))
    }

    @Test
    fun moving_to_either_end() {
        assertEquals(listOf(2, 0, 1), PageOrder.move(3, from = 2, to = 0))
        assertEquals(listOf(1, 2, 0), PageOrder.move(3, from = 0, to = 2))
    }

    @Test
    fun a_move_lists_every_page_once() {
        val order = PageOrder.move(9, from = 2, to = 7)
        assertEquals((0 until 9).toList(), order.sorted())
    }

    @Test(expected = IllegalArgumentException::class)
    fun moving_a_page_that_is_not_there_is_refused() {
        PageOrder.move(3, from = 3, to = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun moving_to_a_position_that_is_not_there_is_refused() {
        PageOrder.move(3, from = 0, to = -1)
    }

    @Test
    fun some_but_not_all_pages_can_be_deleted() {
        assertTrue(PageOrder.canRemove(3, setOf(1)))
        assertTrue(PageOrder.canRemove(3, setOf(0, 2)))
    }

    @Test
    fun every_page_cannot_be_deleted() {
        assertFalse(PageOrder.canRemove(3, setOf(0, 1, 2)))
        assertFalse(PageOrder.canRemove(1, setOf(0)))
    }

    @Test
    fun nothing_selected_deletes_nothing() {
        assertFalse(PageOrder.canRemove(3, emptySet()))
    }

    @Test
    fun a_page_that_is_not_in_the_document_cannot_be_deleted() {
        assertFalse(PageOrder.canRemove(3, setOf(3)))
    }

    @Test
    fun rotate_and_delete_name_the_pages_in_order() {
        assertEquals(PdfOp.Rotate(listOf(0, 2, 5), 90), PageOrder.rotate(setOf(5, 0, 2), PageOrder.ROTATE_RIGHT))
        assertEquals(PdfOp.Remove(listOf(1, 4)), PageOrder.remove(setOf(4, 1)))
    }

    @Test
    fun rotating_left_is_three_quarter_turns_clockwise() {
        assertEquals(270, PageOrder.ROTATE_LEFT)
        assertEquals(90, PageOrder.ROTATE_RIGHT)
    }

    @Test
    fun toggling_adds_then_removes() {
        val one = PageOrder.toggle(emptySet(), 2)
        assertEquals(setOf(2), one)
        assertEquals(setOf(2, 4), PageOrder.toggle(one, 4))
        assertEquals(emptySet<Int>(), PageOrder.toggle(one, 2))
    }
}
