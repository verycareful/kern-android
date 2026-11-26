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

    /** Taps [point] of page 0, where there is no text, and waits for the field for new text to open. */
    private fun openNewText(point: PagePoint, model: PdfEditorViewModel = vm): PdfEditorViewModel.NewText {
        onMain { model.startTextEdit(); model.tapTextBlock(0, point) }
        await("the new-text field to open") { model.newText != null }
        return model.newText!!
    }

    /** Puts [text] in the open new-text field and adds it, taking the first-edit warning when it comes. */
    private fun add(text: String, model: PdfEditorViewModel = vm) {
        onMain { model.changeTextDraft(text); model.commitTextEdit() }
        if (!model.dirty) {
            await("the warning") { model.editWarning != null }
            onMain { model.editWithoutCopy() }
        }
        await("the new text") { model.dirty && !model.editBusy && model.newText == null }
    }

    @Test
    fun a_tap_on_empty_space_opens_new_text_that_follows_the_font_of_the_page() {
        val point = PagePoint(0.1f, 0.5f)
        val open = openNewText(point)
        assertNull(vm.textEdit)
        assertEquals("", vm.textDraft)
        assertEquals(0, open.page)
        assertEquals(point, open.at)
        assertEquals("Helvetica", open.font?.font)
        assertEquals(24.0, open.effectiveSize, 0.01)
        assertEquals(TextEditModel.columnWidth(blocks(), point.x), open.width, 1e-6f)
        assertFalse("opening the field changes nothing", vm.dirty)
    }

    @Test
    fun new_text_is_put_on_the_page_and_undo_takes_it_off() {
        openNewText(PagePoint(0.1f, 0.5f))
        add("Hello there")
        assertEquals("the box is as wide as the page's text, so the words wrap there", listOf("page 1", "Hello", "there"), texts())
        assertTrue(vm.canUndo)
        onMain { vm.undo() }
        await("the undo") { !vm.editBusy && texts() == listOf("page 1") }
    }

    @Test
    fun new_text_in_a_box_dragged_wider_stays_on_one_line() {
        openNewText(PagePoint(0.1f, 0.5f))
        onMain { vm.resizeNewText(0.8f) }
        assertEquals(0.8f, vm.newText!!.width, 1e-6f)
        add("Hello there")
        assertEquals(listOf("page 1", "Hello there"), texts())
    }

    @Test
    fun an_empty_new_text_field_puts_nothing_on_the_page() {
        openNewText(PagePoint(0.1f, 0.5f))
        onMain { vm.commitTextEdit() }
        assertNull(vm.newText)
        assertFalse(vm.dirty)
        assertNull(vm.editWarning)
    }

    @Test
    fun new_text_in_a_size_the_user_chose_is_set_in_it() {
        openNewText(PagePoint(0.1f, 0.5f))
        onMain { vm.chooseNewTextSize(12.0) }
        assertEquals(12.0, vm.newText!!.effectiveSize, 0.0)
        add("small")
        val small = runBlocking { vm.pageLines(0) }.single { it.text == "small" }
        val page = runBlocking { vm.pageLines(0) }.single { it.text == "page 1" }
        assertTrue(
            "12 pt text is about half as tall as the 24 pt line",
            (small.rect.bottom - small.rect.top) < 0.7f * (page.rect.bottom - page.rect.top),
        )
    }

    @Test
    fun a_dragged_width_snaps_to_the_width_of_a_block_of_the_page() {
        openNewText(PagePoint(0.1f, 0.5f))
        val block = blocks().single().box
        val blockWidth = block.right - block.left
        onMain { vm.resizeNewText(blockWidth + TextEditModel.WIDTH_SNAP / 2) }
        assertEquals(blockWidth, vm.newText!!.width, 1e-6f)
    }

    @Test
    fun new_text_on_a_page_with_no_text_is_set_in_the_default_size_or_the_one_chosen() {
        pdf = TestPdfs.blank(File(dir, "blank.pdf"))
        val blank = openViewModel()
        val open = openNewText(PagePoint(0.2f, 0.2f), blank)
        assertNull("there is no font to follow", open.font)
        assertTrue(open.fonts.fonts.isEmpty())
        assertEquals(TextEditModel.EMPTY_PAGE_SIZE, open.effectiveSize, 0.0)
        onMain { blank.chooseNewTextSize(18.0) }
        add("On a blank page", blank)
        assertEquals(listOf("On a blank page"), texts(blank))
    }

    @Test
    fun leaving_the_mode_closes_an_open_new_text_field() {
        openNewText(PagePoint(0.1f, 0.5f))
        onMain { vm.finishAnnotating() }
        assertNull(vm.newText)
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
        val note = vm.toolMessage
        assertTrue("the user is told a substitute font was used: $note", note?.startsWith("Substitute font used for") == true)
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
