package dev.kern.editors.pdf

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfOp
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A PDF whose cross-reference had to be rebuilt: Kern offers a repaired copy, writes it as a new file, and the copy can be edited. */
@RunWith(AndroidJUnit4::class)
class PdfEditorRepairedCopyInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var pdf: File

    @Before
    fun open() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-repair-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        pdf = TestPdfs.withRebuiltXref(File(dir, "damaged.pdf"), 2)
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    private fun viewModelOn(file: File): PdfEditorViewModel {
        lateinit var vm: PdfEditorViewModel
        onMain {
            vm = PdfEditorViewModel(app)
            vm.start("file://${file.absolutePath}")
        }
        await("the PDF to open") { !vm.loading }
        assertNull(vm.error)
        await("the engine to open the file or refuse it") {
            vm.canSelectText || vm.unavailableReason?.startsWith("Search, selection and links are unavailable") == true
        }
        return vm
    }

    @Test
    fun the_engine_refuses_the_file_for_editing_and_an_edit_offers_a_repaired_copy() {
        val vm = viewModelOn(pdf)
        assertTrue(vm.needsRepairedCopy)
        var taken = true
        onMain { taken = vm.requestEdit(PdfOp.Rotate(listOf(0), 90)) }
        assertFalse(taken)
        assertTrue(vm.repairOffer)
        assertNull("no warning is asked for an edit that cannot be made", vm.editWarning)
        onMain { vm.dismissRepairOffer() }
        assertFalse(vm.repairOffer)
    }

    @Test
    fun the_repaired_copy_is_a_new_file_that_opens_for_editing_and_the_file_is_untouched() {
        val before = pdf.readBytes()
        val vm = viewModelOn(pdf)
        val target = File(dir, "damaged (repaired).pdf").apply { createNewFile() }
        onMain { vm.saveRepairedCopy(Uri.fromFile(target)) }
        await("the copy") { vm.openCopy != null || vm.toolError != null }
        assertNull(vm.toolError)
        assertEquals(Uri.fromFile(target), vm.openCopy)
        assertArrayEquals("the damaged file is not changed", before, pdf.readBytes())

        val copy = viewModelOn(target)
        assertFalse(copy.needsRepairedCopy)
        assertTrue("the copy's text can be read and edited", copy.canSelectText)
        assertEquals(2, copy.pageCount)
    }

    @Test
    fun save_compacted_copy_works_on_a_file_the_engine_will_not_edit() {
        val vm = viewModelOn(pdf)
        val target = File(dir, "damaged (compacted).pdf").apply { createNewFile() }
        onMain { vm.saveCompactedTo(Uri.fromFile(target)) }
        await("the copy") { (vm.toolMessage != null || vm.toolError != null) && !vm.editBusy }
        assertNull(vm.toolError)
        assertEquals("Saved a compacted copy", vm.toolMessage)
        assertTrue(target.length() > 0)
    }
}
