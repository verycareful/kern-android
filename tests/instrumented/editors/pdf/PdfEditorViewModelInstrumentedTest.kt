package dev.kern.editors.pdf

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfOp
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The first-edit warning, edit, undo, redo and save through the ViewModel, on a file opened by address. */
@RunWith(AndroidJUnit4::class)
class PdfEditorViewModelInstrumentedTest {

    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var originalBytes: ByteArray
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-test").apply { deleteRecursively(); mkdirs() }
        pdf = TestPdfs.make(File(dir, "report.pdf"), 3)
        originalBytes = pdf.readBytes()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading }
        assertNull(vm.error)
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun settled() = await("the edit to finish") { !vm.editBusy }

    private fun rotateFirstPage() = onMain { vm.requestEdit(PdfOp.Rotate(listOf(0), 90)) }

    @Test
    fun the_first_edit_opens_the_warning_and_changes_nothing() {
        assertEquals(3, vm.pageCount)
        rotateFirstPage()
        assertNotNull(vm.editWarning)
        assertTrue(vm.editWarning!!.destination is CopyDestination.NextToFile)
        assertFalse(vm.dirty)
        assertArrayEquals(originalBytes, pdf.readBytes())
    }

    @Test
    fun cancelling_the_warning_drops_the_edit() {
        rotateFirstPage()
        onMain { vm.cancelEdit() }
        settled()
        assertNull(vm.editWarning)
        assertFalse(vm.dirty)
        assertArrayEquals(originalBytes, pdf.readBytes())
    }

    @Test
    fun saving_a_copy_first_writes_it_beside_the_file_and_then_edits() {
        rotateFirstPage()
        onMain { vm.chooseCopyFirst() }
        await("the edit") { vm.dirty }
        val copy = File(dir, "report (original).pdf")
        assertTrue(copy.exists())
        assertArrayEquals(originalBytes, copy.readBytes())
        assertArrayEquals(originalBytes, pdf.readBytes())
        assertTrue(vm.canUndo)
        assertFalse(vm.canRedo)
    }

    @Test
    fun a_second_session_names_its_copy_with_the_next_number() {
        File(dir, "report (original).pdf").writeText("an earlier copy")
        rotateFirstPage()
        onMain { vm.chooseCopyFirst() }
        await("the edit") { vm.dirty }
        assertTrue(File(dir, "report (original 2).pdf").exists())
        assertEquals("an earlier copy", File(dir, "report (original).pdf").readText())
    }

    @Test
    fun editing_without_a_copy_makes_none_and_a_later_edit_does_not_ask_again() {
        rotateFirstPage()
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        assertFalse(File(dir, "report (original).pdf").exists())
        onMain { vm.requestEdit(PdfOp.Rotate(listOf(1), 90)) }
        assertNull(vm.editWarning)
        settled()
        assertTrue(vm.dirty)
    }

    @Test
    fun undo_and_redo_move_between_the_states() {
        rotateFirstPage()
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        onMain { vm.undo() }
        await("the undo") { !vm.dirty }
        assertTrue(vm.canRedo)
        assertFalse(vm.canUndo)
        onMain { vm.redo() }
        await("the redo") { vm.dirty }
        assertTrue(vm.canUndo)
    }

    @Test
    fun save_appends_to_the_file_and_clears_the_unsaved_state() {
        rotateFirstPage()
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        var result: Pair<Boolean, String?>? = null
        onMain { vm.save { ok, message -> result = ok to message } }
        await("the save") { result != null }
        assertEquals(true to null, result)
        assertFalse(vm.dirty)
        val saved = pdf.readBytes()
        assertTrue(saved.size > originalBytes.size)
        assertArrayEquals(originalBytes, saved.copyOf(originalBytes.size))
        assertEquals(TestPdfs.HEIGHT to TestPdfs.WIDTH, TestPdfs.pageSize(pdf, 0))
        assertTrue(vm.canUndo)
    }

    @Test
    fun saving_with_nothing_edited_changes_nothing() {
        var result: Pair<Boolean, String?>? = null
        onMain { vm.save { ok, message -> result = ok to message } }
        await("the save") { result != null }
        assertEquals(true to null, result)
        assertArrayEquals(originalBytes, pdf.readBytes())
    }

    @Test
    fun a_file_changed_elsewhere_is_not_saved_over() {
        rotateFirstPage()
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        pdf.appendBytes("\n% someone else\n".toByteArray())
        val theirs = pdf.readBytes()
        var result: Pair<Boolean, String?>? = null
        onMain { vm.save { ok, message -> result = ok to message } }
        await("the save") { result != null }
        assertFalse(result!!.first)
        assertTrue(result!!.second!!.contains("changed outside Kern"))
        assertArrayEquals(theirs, pdf.readBytes())
        assertTrue(vm.dirty)
    }

    @Test
    fun the_page_picture_is_drawn_again_after_an_edit() {
        val before = vm.pageVersion(0)
        rotateFirstPage()
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        assertTrue(vm.pageVersion(0) > before)
        assertEquals(0, vm.pageVersion(1))
    }
}
