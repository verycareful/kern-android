package dev.kern.editors.pdf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The warning before the first edit: it asks once, and nothing gets past it without a decision. */
class FirstEditGateTest {

    private val beside = CopyDestination.NextToFile(File("/docs"), "a (original).pdf")
    private val picker = CopyDestination.Picker("a (original).pdf")

    private fun gate(destination: CopyDestination) = FirstEditGate<String> { destination }

    @Test
    fun the_first_edit_waits_and_opens_the_warning() {
        val gate = gate(beside)
        assertEquals(FirstEditGate.Action.Wait, gate.request("rotate"))
        assertEquals(beside, gate.warning?.destination)
        assertFalse(gate.cleared)
    }

    @Test
    fun saving_a_copy_first_writes_it_next_to_the_file_then_runs_the_edit() {
        val gate = gate(beside)
        gate.request("rotate")
        assertEquals(FirstEditGate.Action.CopyNextToFile(beside), gate.chooseCopy())
        assertEquals(FirstEditGate.Action.Run("rotate"), gate.copyDone())
        assertNull(gate.warning)
        assertTrue(gate.cleared)
    }

    @Test
    fun a_file_that_cannot_take_a_copy_beside_it_goes_through_the_picker() {
        val gate = gate(picker)
        gate.request("rotate")
        assertEquals(FirstEditGate.Action.OpenPicker("a (original).pdf"), gate.chooseCopy())
    }

    @Test
    fun a_failed_copy_never_lets_the_edit_through_and_asks_again_through_the_picker() {
        val gate = gate(beside)
        gate.request("rotate")
        gate.chooseCopy()
        gate.copyFailed("disk full")
        assertFalse(gate.cleared)
        assertEquals(CopyDestination.Picker("a (original).pdf"), gate.warning?.destination)
        assertTrue(gate.warning?.problem!!.contains("disk full"))
        assertEquals(FirstEditGate.Action.OpenPicker("a (original).pdf"), gate.chooseCopy())
    }

    @Test
    fun a_closed_picker_keeps_the_warning_and_the_edit_waiting() {
        val gate = gate(picker)
        gate.request("rotate")
        gate.chooseCopy()
        gate.pickerCancelled()
        assertFalse(gate.cleared)
        assertNotNull(gate.warning)
        assertNotNull(gate.warning?.problem)
    }

    @Test
    fun editing_without_a_copy_runs_the_edit_and_stops_asking() {
        val gate = gate(beside)
        gate.request("rotate")
        assertEquals(FirstEditGate.Action.Run("rotate"), gate.chooseWithoutCopy())
        assertEquals(FirstEditGate.Action.Run("reorder"), gate.request("reorder"))
        assertNull(gate.warning)
    }

    @Test
    fun cancelling_drops_the_edit_and_asks_again_next_time() {
        val gate = gate(beside)
        gate.request("rotate")
        gate.cancel()
        assertNull(gate.warning)
        assertFalse(gate.cleared)
        assertEquals(FirstEditGate.Action.Wait, gate.request("rotate"))
        assertNotNull(gate.warning)
    }

    @Test
    fun a_decision_with_no_edit_waiting_runs_nothing() {
        val gate = gate(beside)
        assertEquals(FirstEditGate.Action.Wait, gate.chooseWithoutCopy())
    }
}
