package dev.kern.editors.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfAnnotation
import dev.kern.pdfbridge.PdfAnnotationKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Notes and the selection of annotations through the ViewModel: add, choose, recolour, move, change the text, delete, save, reopen. */
@RunWith(AndroidJUnit4::class)
class PdfEditorNoteInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var vm: PdfEditorViewModel

    private val aspect = TestPdfs.HEIGHT.toFloat() / TestPdfs.WIDTH
    private val reach = 0.05f

    @Before
    fun open() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-note-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        pdf = TestPdfs.make(File(dir, "report.pdf"), 2)
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

    private fun page(index: Int): Bitmap = runBlocking { vm.renderPage(index, TestPdfs.WIDTH) }!!

    private fun count(bitmap: Bitmap, matches: (Int) -> Boolean) =
        (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> matches(bitmap.getPixel(x, y)) } }

    private fun isYellow(p: Int) = Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) < 140
    private fun isRed(p: Int) = Color.red(p) > 200 && Color.green(p) < 90 && Color.blue(p) < 90

    private fun notes(model: PdfEditorViewModel = vm, page: Int = 0): List<PdfAnnotation> =
        runBlocking { model.annotationsOf(page) }.filter { it.kind == PdfAnnotationKind.Note }

    /** Adds a note reading [text] at [at] on page 0, taking the first-edit warning when it comes, and waits for it to be chosen. */
    private fun addNote(text: String, at: PagePoint = PagePoint(0.3f, 0.5f)) {
        onMain {
            vm.startNote()
            vm.placeNote(0, at)
            vm.commitNoteText(text)
        }
        if (!vm.dirty) {
            await("the warning") { vm.editWarning != null }
            onMain { vm.editWithoutCopy() }
        }
        await("the note to be chosen") { vm.selectedAnnotation != null && !vm.editBusy }
    }

    private fun waitForEdit() = await("the edit") { !vm.editBusy }

    @Test
    fun a_note_that_was_added_is_chosen_with_its_text_and_is_on_the_page() {
        addNote("Check this")
        val chosen = vm.selectedAnnotation!!
        assertEquals(0, chosen.page)
        assertEquals(PdfAnnotationKind.Note, chosen.annotation.kind)
        assertEquals("Placing the note leaves the mode, so its bar and outline show", PdfEditorViewModel.AnnotateMode.None, vm.annotateMode)
        assertEquals("Check this", chosen.annotation.contents)
        assertNotNull(chosen.annotation.id)
        assertEquals(0.3f, chosen.annotation.rect.left, 0.02f)
        assertEquals(1, notes().size)
        assertTrue("yellow on the page", count(page(0), ::isYellow) > 20)
        assertEquals("the other page is untouched", 0, count(page(1), ::isYellow))
    }

    @Test
    fun an_empty_text_adds_nothing() {
        onMain {
            vm.startNote()
            vm.placeNote(0, PagePoint(0.3f, 0.5f))
            vm.commitNoteText("   ")
        }
        assertNull(vm.noteRequest)
        assertFalse(vm.dirty)
        assertNull(vm.editWarning)
    }

    @Test
    fun a_tap_on_the_note_chooses_it_and_a_tap_beside_it_drops_the_choice() {
        addNote("Check this")
        onMain { vm.deselect() }
        val rect = notes().single().rect
        val onIt = PagePoint((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
        onMain { vm.tapPage(0, onIt, aspect, reach) }
        await("the note to be chosen") { vm.selectedAnnotation != null }
        onMain { vm.tapPage(0, PagePoint(0.9f, 0.9f), aspect, reach) }
        await("the choice to be dropped") { vm.selectedAnnotation == null }
    }

    @Test
    fun recolouring_the_note_keeps_it_chosen_and_one_undo_puts_the_colour_back() {
        addNote("Check this")
        assertEquals(0, count(page(0), ::isRed))
        onMain { vm.recolourSelected(MarkColour.Red) }
        waitForEdit()
        await("the colour") { AnnotationSelectionModel.offeredColour(vm.selectedAnnotation?.annotation?.colour) == MarkColour.Red }
        assertTrue("red on the page", count(page(0), ::isRed) > 20)
        assertEquals("the note's yellow is gone", 0, count(page(0), ::isYellow))

        onMain { vm.undo() }
        await("the undo") { !vm.editBusy && count(page(0), ::isRed) == 0 }
        assertTrue(count(page(0), ::isYellow) > 20)
        assertNull("an undo drops the choice", vm.selectedAnnotation)
    }

    @Test
    fun moving_the_note_moves_it_by_that_much_and_keeps_it_chosen() {
        addNote("Check this")
        val before = vm.selectedAnnotation!!.annotation.rect
        onMain { vm.carry(PagePoint(0.2f, 0.1f)); vm.moveSelected(vm.carriedOffset) }
        waitForEdit()
        await("the move") { vm.selectedAnnotation?.annotation?.rect?.left?.let { it > before.left + 0.1f } == true }
        val after = vm.selectedAnnotation!!.annotation.rect
        assertEquals(before.left + 0.2f, after.left, 0.01f)
        assertEquals(before.top + 0.1f, after.top, 0.01f)
        assertEquals(PagePoint(0f, 0f), vm.carriedOffset)
        assertEquals(1, notes().size)
    }

    @Test
    fun a_move_is_kept_on_the_page() {
        addNote("Check this")
        onMain { vm.moveSelected(PagePoint(5f, 5f)) }
        waitForEdit()
        await("the move") { vm.selectedAnnotation?.annotation?.rect?.left?.let { it > 0.5f } == true }
        val rect = vm.selectedAnnotation!!.annotation.rect
        assertTrue(rect.right <= 1.001f && rect.bottom <= 1.001f)
    }

    @Test
    fun changing_the_text_of_the_note_shows_the_old_text_first_and_keeps_it_chosen() {
        addNote("Check this")
        onMain { vm.editSelectedNoteText() }
        assertEquals("Check this", vm.noteRequest!!.initial)
        onMain { vm.commitNoteText("Checked") }
        waitForEdit()
        await("the text") { vm.selectedAnnotation?.annotation?.contents == "Checked" }
        assertEquals("Checked", notes().single().contents)
    }

    @Test
    fun clearing_the_text_of_a_note_leaves_it_as_it_was() {
        addNote("Check this")
        onMain { vm.editSelectedNoteText(); vm.commitNoteText("") }
        waitForEdit()
        assertEquals("Check this", notes().single().contents)
    }

    @Test
    fun deleting_the_note_takes_it_off_the_page_and_one_undo_brings_it_back() {
        addNote("Check this")
        onMain { vm.deleteSelected() }
        waitForEdit()
        await("the deletion") { notes().isEmpty() }
        assertNull(vm.selectedAnnotation)
        assertEquals(0, count(page(0), ::isYellow))

        onMain { vm.undo() }
        await("the undo") { !vm.editBusy && notes().size == 1 }
        assertTrue(count(page(0), ::isYellow) > 20)
    }

    @Test
    fun the_note_survives_a_save_and_a_reopen_with_its_text_and_place() {
        addNote("Check this", PagePoint(0.4f, 0.6f))
        onMain { vm.recolourSelected(MarkColour.Red) }
        waitForEdit()
        await("the colour") { AnnotationSelectionModel.offeredColour(vm.selectedAnnotation?.annotation?.colour) == MarkColour.Red }
        var saved: Pair<Boolean, String?>? = null
        onMain { vm.save { ok, message -> saved = ok to message } }
        await("the save") { saved != null }
        assertEquals(true to null, saved)

        File(app.cacheDir, "pdf-session").deleteRecursively()
        val reopened = openViewModel()
        val note = notes(reopened).single()
        assertEquals("Check this", note.contents)
        assertEquals(0.4f, note.rect.left, 0.02f)
        assertEquals(MarkColour.Red, AnnotationSelectionModel.offeredColour(note.colour))
        vm = reopened
        assertTrue("red on the reopened page", count(page(0), ::isRed) > 20)
    }

    @Test
    fun starting_to_mark_or_draw_drops_the_choice() {
        addNote("Check this")
        onMain { vm.startInk() }
        assertNull(vm.selectedAnnotation)
    }

    @Test
    fun another_edit_of_the_document_drops_the_choice() {
        addNote("Check this")
        onMain { vm.requestEdit(dev.kern.pdfbridge.PdfOp.Rotate(listOf(1), 90)) }
        await("the edit") { vm.canUndo && !vm.editBusy && vm.selectedAnnotation == null }
        assertEquals("the note is still there", 1, notes().size)
    }
}
