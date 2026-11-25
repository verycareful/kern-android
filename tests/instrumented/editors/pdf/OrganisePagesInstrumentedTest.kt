package dev.kern.editors.pdf

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfOp
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reorder, rotate and delete through the ViewModel, saved to the file and read back from disk.
 * Each page of the test file has its own width, so the order of the pages can be read from the saved file.
 */
@RunWith(AndroidJUnit4::class)
class OrganisePagesInstrumentedTest {

    private val widths = listOf(200, 210, 220, 230)

    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "organise-test").apply { deleteRecursively(); mkdirs() }
        pdf = TestPdfs.makeSized(File(dir, "book.pdf"), widths)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading }
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

    /** Applies [op] without a copy of the file first, waits for it, and saves it to the file. */
    private fun editAndSave(op: PdfOp) {
        onMain { vm.requestEdit(op) }
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty && !vm.editBusy }
        save()
    }

    private fun save() {
        var result: Pair<Boolean, String?>? = null
        onMain { vm.save { ok, message -> result = ok to message } }
        await("the save") { result != null }
        assertEquals(true to null, result)
    }

    @Test
    fun moving_a_page_changes_the_order_in_the_saved_file() {
        editAndSave(PdfOp.Reorder(PageOrder.move(4, from = 0, to = 2)))
        assertEquals(listOf(210, 220, 200, 230), TestPdfs.pageWidths(pdf))
        assertEquals(4, vm.pageCount)
    }

    @Test
    fun moving_a_page_back_changes_the_order_in_the_saved_file() {
        editAndSave(PdfOp.Reorder(PageOrder.move(4, from = 3, to = 1)))
        assertEquals(listOf(200, 230, 210, 220), TestPdfs.pageWidths(pdf))
    }

    @Test
    fun rotating_pages_turns_only_those_pages() {
        editAndSave(PageOrder.rotate(setOf(1, 3), PageOrder.ROTATE_RIGHT))
        val sizes = (0 until 4).map { TestPdfs.pageSize(pdf, it) }
        assertEquals(
            listOf(200 to TestPdfs.HEIGHT, TestPdfs.HEIGHT to 210, 220 to TestPdfs.HEIGHT, TestPdfs.HEIGHT to 230),
            sizes,
        )
    }

    @Test
    fun rotating_left_turns_the_page_the_other_way() {
        editAndSave(PageOrder.rotate(setOf(0), PageOrder.ROTATE_LEFT))
        assertEquals(TestPdfs.HEIGHT to 200, TestPdfs.pageSize(pdf, 0))
    }

    @Test
    fun deleting_pages_leaves_the_others_in_order() {
        editAndSave(PageOrder.remove(setOf(1, 2)))
        assertEquals(listOf(200, 230), TestPdfs.pageWidths(pdf))
        assertEquals(2, vm.pageCount)
    }

    @Test
    fun an_undone_move_leaves_the_saved_order_as_it_was() {
        onMain { vm.requestEdit(PdfOp.Reorder(PageOrder.move(4, from = 0, to = 3))) }
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty && !vm.editBusy }
        onMain { vm.undo() }
        await("the undo") { !vm.dirty && !vm.editBusy }
        save()
        assertEquals(widths, TestPdfs.pageWidths(pdf))
        assertTrue(vm.canRedo)
    }
}
