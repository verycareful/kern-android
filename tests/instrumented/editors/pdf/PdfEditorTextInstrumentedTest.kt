package dev.kern.editors.pdf

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfOp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Find, selection text and links through the ViewModel: the engine session opens with the PDF, not with the first edit. */
@RunWith(AndroidJUnit4::class)
class PdfEditorTextInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-text-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        pdf = TestPdfs.make(File(dir, "report.pdf"), 3)
        onMain {
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

    @Test
    fun the_text_is_ready_without_any_edit() {
        await("the text") { vm.unavailableReason == null }
        assertTrue(vm.canSelectText)
        val lines = runBlocking { vm.pageLines(1) }
        assertEquals(listOf("page 2"), lines.map { it.text })
    }

    @Test
    fun find_matches_every_page_and_follows_a_reorder() {
        await("the text") { vm.unavailableReason == null }
        onMain { vm.find.open(); vm.find.query = "page 1" }
        await("the match") { vm.pdfMatches.size == 1 }
        assertEquals(0, vm.pdfMatches.single().page)

        onMain { vm.requestEdit(PdfOp.Reorder(listOf(1, 2, 0))) }
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        await("the match to move") { vm.pdfMatches.singleOrNull()?.page == 2 }
    }

    @Test
    fun the_first_edit_uses_the_session_that_is_already_open() {
        await("the text") { vm.unavailableReason == null }
        val sessions = File(app.cacheDir, "pdf-session")
        assertEquals(1, sessions.listFiles().orEmpty().size)
        onMain { vm.requestEdit(PdfOp.Rotate(listOf(0), 90)) }
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty }
        assertEquals(1, sessions.listFiles().orEmpty().size)
    }
}
