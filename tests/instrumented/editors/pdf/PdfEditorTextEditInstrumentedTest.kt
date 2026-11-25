package dev.kern.editors.pdf

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfTextBlock
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Changing the text of a block through the ViewModel: read the blocks, open one, change it, undo, save, reopen. */
@RunWith(AndroidJUnit4::class)
class PdfEditorTextEditInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-textedit-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        pdf = TestPdfs.withEditableText(File(dir, "report.pdf"), 2)
        vm = openViewModel()
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun openViewModel(): PdfEditorViewModel {
        lateinit var opened: PdfEditorViewModel
        onMain {
            opened = PdfEditorViewModel(app)
            opened.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !opened.loading }
        assertNull(opened.error)
        await("the text") { opened.unavailableReason == null }
        return opened
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun blocks(model: PdfEditorViewModel = vm, page: Int = 0): List<PdfTextBlock> =
        runBlocking { model.blocksOf(page) }!!.blocks

    private fun texts(model: PdfEditorViewModel = vm, page: Int = 0): List<String> =
        runBlocking { model.pageLines(page) }.map { it.text }

    private fun centre(box: PdfBox) = PagePoint((box.left + box.right) / 2, (box.top + box.bottom) / 2)

    /** Taps the only block of page 0 and waits for its field to open. */
    private fun openBlock(model: PdfEditorViewModel = vm) {
        val box = blocks(model).single().box
        onMain {
            model.startTextEdit()
            model.tapTextBlock(0, centre(box))
        }
        await("the field to open") { model.textEdit != null }
    }

    /** Puts [text] in the open field and applies it, taking the first-edit warning when it comes. */
    private fun change(text: String) {
        onMain { vm.changeTextDraft(text); vm.commitTextEdit() }
        if (!vm.dirty) {
            await("the warning") { vm.editWarning != null }
            onMain { vm.editWithoutCopy() }
        }
        await("the edit") { vm.dirty && !vm.editBusy && vm.textEdit == null }
    }

    @Test
    fun the_blocks_of_a_page_are_read_with_their_text_and_a_revision() {
        val read = runBlocking { vm.blocksOf(0) }!!
        val block = read.blocks.single()
        assertEquals("page 1", block.text)
        assertTrue("the block is refused: ${block.reason}", block.editable)
        assertNull(block.reason)
        assertTrue("the block is where the text is drawn", block.box.left < 0.2f && block.box.top < 0.2f)
    }

    @Test
    fun a_tap_on_a_block_opens_it_with_its_text_in_the_field() {
        openBlock()
        val open = vm.textEdit!!
        assertEquals(0, open.page)
        assertEquals("page 1", vm.textDraft)
        assertFalse("opening a block changes nothing", vm.dirty)
    }

    @Test
    fun a_tap_on_empty_space_says_new_text_cannot_be_put_there_yet() {
        onMain { vm.startTextEdit(); vm.tapTextBlock(0, PagePoint(0.8f, 0.9f)) }
        await("the message") { vm.toolMessage != null }
        assertEquals(TextEditModel.NOTHING_THERE, vm.toolMessage)
        assertNull(vm.textEdit)
        assertFalse(vm.dirty)
    }

    @Test
    fun an_unchanged_field_applied_sends_nothing() {
        openBlock()
        onMain { vm.commitTextEdit() }
        assertNull(vm.textEdit)
        assertFalse(vm.dirty)
        assertNull(vm.editWarning)
    }

    @Test
    fun cancelling_leaves_the_page_as_it_was() {
        openBlock()
        onMain { vm.changeTextDraft("something else"); vm.cancelTextEdit() }
        assertNull(vm.textEdit)
        assertFalse(vm.dirty)
        assertEquals(listOf("page 1"), texts())
    }

    @Test
    fun changing_the_text_puts_the_new_words_on_the_page_and_undo_brings_the_old_ones_back() {
        openBlock()
        change("page 7")
        assertEquals(listOf("page 7"), texts())
        assertEquals("the other page is untouched", listOf("page 2"), texts(page = 1))
        assertTrue(vm.canUndo)

        onMain { vm.undo() }
        await("the undo") { !vm.editBusy && !vm.canUndo }
        assertEquals(listOf("page 1"), texts())
        onMain { vm.redo() }
        await("the redo") { !vm.editBusy && vm.canUndo }
        assertEquals(listOf("page 7"), texts())
    }

    @Test
    fun an_emptied_field_takes_the_text_out_of_the_block() {
        openBlock()
        change("")
        assertEquals(emptyList<String>(), texts())
        assertTrue(blocks().isEmpty())
    }

    @Test
    fun the_new_text_survives_a_save_and_a_reopen() {
        openBlock()
        change("page 7")
        var saved: Pair<Boolean, String?>? = null
        onMain { vm.save { ok, message -> saved = ok to message } }
        await("the save") { saved != null }
        assertEquals(true to null, saved)

        File(app.cacheDir, "pdf-session").deleteRecursively()
        val reopened = openViewModel()
        assertEquals(listOf("page 7"), texts(reopened))
        assertEquals(listOf("page 2"), texts(reopened, 1))
    }

    @Test
    fun an_edit_drops_what_was_read_so_the_next_tap_uses_the_new_revision() {
        val before = runBlocking { vm.blocksOf(0) }!!.revision
        openBlock()
        change("page 7")
        val after = runBlocking { vm.blocksOf(0) }!!
        assertTrue("the revision moved on", after.revision != before)
        assertEquals("page 7", after.blocks.single().text)
    }

    @Test
    fun leaving_the_mode_closes_an_open_field() {
        openBlock()
        onMain { vm.finishAnnotating() }
        assertNull(vm.textEdit)
    }

    @Test
    fun letters_the_font_cannot_show_are_set_in_a_system_font() {
        openBlock()
        change("\u65e5\u672c")
        assertEquals(listOf("\u65e5\u672c"), texts())
        assertNull("the edit did not fail", vm.toolError)
    }

    @Test
    fun a_letter_no_font_has_is_named_to_the_user() {
        openBlock()
        onMain { vm.changeTextDraft("page \uE000"); vm.commitTextEdit() }
        await("the warning") { vm.editWarning != null }
        onMain { vm.editWithoutCopy() }
        await("the failure") { vm.toolError != null && !vm.editBusy }
        assertTrue(vm.toolError!!, vm.toolError!!.startsWith("No font on this device has the letter U+E000"))
        assertFalse(vm.dirty)
        assertEquals("the page keeps its text", listOf("page 1"), texts())
    }
}
