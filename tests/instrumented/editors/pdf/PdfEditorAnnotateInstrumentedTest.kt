package dev.kern.editors.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Marking text and drawing through the ViewModel: what the screen's layers hand it, and what comes out. */
@RunWith(AndroidJUnit4::class)
class PdfEditorAnnotateInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-annotate-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.make(File(dir, "report.pdf"), 2)
        onMain {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading }
        assertNull(vm.error)
        await("the text") { vm.unavailableReason == null }
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

    private fun page(index: Int): Bitmap = runBlocking { vm.renderPage(index, TestPdfs.WIDTH) }!!

    private fun count(bitmap: Bitmap, matches: (Int) -> Boolean) =
        (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> matches(bitmap.getPixel(x, y)) } }

    private fun isYellow(p: Int) = Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) < 140
    private fun isRed(p: Int) = Color.red(p) > 200 && Color.green(p) < 90 && Color.blue(p) < 90

    private fun acceptTheFirstEditWarning() {
        await("the warning") { vm.editWarning != null }
        onMain { vm.editWithoutCopy() }
        await("the edit") { vm.dirty && !vm.editBusy }
    }

    @Test
    fun choosing_text_and_marking_it_highlights_it_and_one_undo_takes_it_off() {
        val glyphs = runBlocking { vm.pageGlyphs(0) }
        assertTrue("the page has glyphs", glyphs.boxes.size >= "page 1".length)
        onMain {
            vm.startMarkup()
            vm.selectMarkup(MarkupSelection(page = 0, anchor = 0, focus = 3))
            vm.applyMarkup()
        }
        acceptTheFirstEditWarning()

        assertNull("the selection is spent", vm.markupSelection)
        assertTrue(vm.canUndo)
        assertTrue("yellow on the page", count(page(0), ::isYellow) > 100)
        assertEquals("the other page is untouched", 0, count(page(1), ::isYellow))

        onMain { vm.undo() }
        await("the undo") { !vm.canUndo && !vm.editBusy }
        assertEquals(0, count(page(0), ::isYellow))
    }

    @Test
    fun a_stroke_stays_pending_until_it_is_in_the_document() {
        onMain {
            vm.startInk()
            vm.chooseColour(MarkColour.Red)
            vm.addInkStroke(0, listOf(PagePoint(0.1f, 0.6f), PagePoint(0.5f, 0.6f), PagePoint(0.9f, 0.6f)), 4f / 3f)
        }
        assertEquals("shown while the warning waits", 1, vm.pendingInk.single().strokes.size)
        acceptTheFirstEditWarning()
        await("the stroke to be taken in") { vm.pendingInk.isEmpty() }
        assertTrue("red on the page", count(page(0), ::isRed) > 100)

        onMain { vm.undo() }
        await("the undo") { !vm.canUndo && !vm.editBusy }
        assertEquals(0, count(page(0), ::isRed))
    }

    @Test
    fun a_stroke_drawn_while_the_warning_waits_goes_in_after_the_first_as_its_own_edit() {
        onMain {
            vm.startInk()
            vm.chooseColour(MarkColour.Red)
            vm.addInkStroke(0, listOf(PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f)), 4f / 3f)
            vm.addInkStroke(0, listOf(PagePoint(0.1f, 0.7f), PagePoint(0.9f, 0.7f)), 4f / 3f)
        }
        assertEquals("both are on screen", 2, vm.pendingInk.single().strokes.size)
        acceptTheFirstEditWarning()
        await("both strokes to be taken in") { vm.pendingInk.isEmpty() && !vm.editBusy }
        val both = count(page(0), ::isRed)
        assertTrue("two lines of red", both > 200)

        onMain { vm.undo() }
        await("the first undo") { !vm.editBusy && count(page(0), ::isRed) < both }
        assertTrue("one line is left", count(page(0), ::isRed) in 100 until both)
        onMain { vm.undo() }
        await("the second undo") { !vm.canUndo && !vm.editBusy }
        assertEquals(0, count(page(0), ::isRed))
    }

    @Test
    fun cancelling_the_first_edit_drops_the_stroke_and_changes_nothing() {
        onMain {
            vm.startInk()
            vm.addInkStroke(0, listOf(PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f)), 4f / 3f)
        }
        await("the warning") { vm.editWarning != null }
        onMain { vm.cancelEdit() }
        assertTrue(vm.pendingInk.isEmpty())
        assertFalse(vm.dirty)
        assertFalse(vm.canUndo)
    }

    @Test
    fun a_stroke_on_a_second_page_is_kept_apart_from_the_first() {
        onMain {
            vm.startInk()
            vm.chooseColour(MarkColour.Red)
            vm.addInkStroke(0, listOf(PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f)), 4f / 3f)
            vm.addInkStroke(1, listOf(PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f)), 4f / 3f)
        }
        assertEquals(listOf(0, 1), vm.pendingInk.map { it.page })
        acceptTheFirstEditWarning()
        await("both pages to be taken in") { vm.pendingInk.isEmpty() && !vm.editBusy }
        assertTrue(count(page(0), ::isRed) > 100)
        assertTrue(count(page(1), ::isRed) > 100)
    }

    @Test
    fun leaving_the_mode_drops_the_selection() {
        onMain {
            vm.startMarkup()
            vm.selectMarkup(MarkupSelection(0, 0, 2))
            vm.finishAnnotating()
        }
        assertNull(vm.markupSelection)
        assertEquals(PdfEditorViewModel.AnnotateMode.None, vm.annotateMode)
    }
}
